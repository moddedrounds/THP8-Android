// Project8Recomp - Android entry point.
//
// SDLActivity (Java) loads librexruntime.so and libmain.so and calls SDL_main on its own native
// thread. Everything the desktop entry point (windowed_app_main_sdl.cpp) does, this does too, plus
// the one thing only Android needs: handing the SDK the JavaVM, the activity and the folder the
// native libraries were unpacked to before any subsystem starts.

#include <atomic>
#include <rex/system/util/object_table.h>
#include <rex/system/xthread.h>
#include <rex/system/kernel_state.h>
#include <pthread.h>
#include <vector>
#include <condition_variable>
#include <rex/runtime.h>
#include <SDL3/SDL_events.h>
#include <thread>
#include <mutex>
#include <SDL3/SDL_hints.h>
#include <SDL3/SDL_system.h>

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <cstdlib>
#include <map>
#include <memory>
#include <string>

#include <rex/cvar.h>
#include <rex/filesystem.h>
#include <rex/logging.h>
#include <rex/main_android.h>
#include <rex/memory.h>
#include <rex/system.h>
#include <rex/thread.h>
#include <rex/ui/windowed_app.h>
#include <rex/ui/windowed_app_context_sdl.h>

#include "custom_driver.h"
#include "display_vsync.h"
#include "thread_affinity.h"

namespace {

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "thps_p8", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "thps_p8", __VA_ARGS__)

// Where the loader found this very library. With extractNativeLibs the .so files live there, and so
// do the GPU plugin the SDK dlopen()s by name.
std::string NativeLibraryDir() {
  Dl_info info{};
  if (dladdr(reinterpret_cast<void*>(&NativeLibraryDir), &info) && info.dli_fname) {
    std::string path(info.dli_fname);
    const size_t slash = path.find_last_of('/');
    if (slash != std::string::npos) {
      return path.substr(0, slash);
    }
  }
  return {};
}

bool PrepareAndroidContext() {
  auto* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
  auto activity = static_cast<jobject>(SDL_GetAndroidActivity());  // local reference
  if (!env || !activity) {
    LOGE("no JNIEnv/activity: SDL has not finished starting");
    return false;
  }
  JavaVM* vm = nullptr;
  if (env->GetJavaVM(&vm) != JNI_OK || !vm) {
    env->DeleteLocalRef(activity);
    LOGE("GetJavaVM failed");
    return false;
  }
  const std::string libs = NativeLibraryDir();
  rex::SetAndroidApplicationContext(vm, activity, libs.c_str());  // takes its own global ref
  env->DeleteLocalRef(activity);

  // The SDK declares one AndroidInitialize() per subsystem and never calls them itself. Without the
  // memory one the guest address space reservation fails with "Unable to reserve the 4gb guest
  // address space" (ASharedMemory is never resolved).
  rex::memory::AndroidInitialize();
  rex::thread::AndroidInitialize();
  rex::filesystem::AndroidInitialize();
  if (!rex::InitializeAndroidSystemForApplicationContext()) {
    LOGE("the SDK rejected the Android context");
    return false;
  }
  LOGI("Android context ready; native libraries in %s", libs.c_str());
  return true;
}

void ShutdownAndroidContext() {
  rex::filesystem::AndroidShutdown();
  rex::thread::AndroidShutdown();
  rex::memory::AndroidShutdown();
  rex::ShutdownAndroidSystem();
}

// The emulated console keeps running (and drawing) when the app is not visible, so a game left in
// the background took a core and a share of the GPU from whatever ran in front (a second port, at
// 35-45 instead of 60 fps). While in the background the game's frame loop parks in a per-frame hook
// (rex_wait_while_in_background, called by the game hooks) and audio + the command processor pause.
// Cooperative on purpose: suspending guest threads with signals left the main one spinning.
namespace {
std::mutex g_pause_mutex;
std::condition_variable g_pause_cv;
std::atomic<bool> g_in_background{false};
bool g_want_paused = false;
bool g_stop_pause_worker = false;

// Audio / graphics pause and resume can block, so they run here, never on Android's UI thread.
void PauseWorker() {
  pthread_setname_np(pthread_self(), "bg pause");
  bool paused = false;
  std::unique_lock<std::mutex> lock(g_pause_mutex);
  for (;;) {
    g_pause_cv.wait(lock, [&] { return g_stop_pause_worker || g_want_paused != paused; });
    const bool pause = g_stop_pause_worker ? false : g_want_paused;
    if (pause != paused) {
      rex::Runtime* runtime = rex::Runtime::instance();
      if (pause) {
        // Park only after audio / graphics acknowledged their pause: their workers must not be
        // parked while those calls wait for them.
        if (runtime && runtime->audio_system()) runtime->audio_system()->Pause();
        if (runtime && runtime->graphics_system()) runtime->graphics_system()->Pause();
        g_in_background = true;
        LOGI("game paused in the background");
      } else {
        if (runtime && runtime->graphics_system()) runtime->graphics_system()->Resume();
        if (runtime && runtime->audio_system()) runtime->audio_system()->Resume();
        g_in_background = false;
        g_pause_cv.notify_all();  // wakes the parked frame loop
        LOGI("game resumed");
      }
      paused = pause;
    }
    if (g_stop_pause_worker && !paused) {
      return;
    }
  }
}

bool SDLCALL BackgroundWatch(void*, SDL_Event* event) {
  if (event->type == SDL_EVENT_DID_ENTER_BACKGROUND || event->type == SDL_EVENT_WILL_ENTER_FOREGROUND) {
    std::lock_guard<std::mutex> lock(g_pause_mutex);
    g_want_paused = event->type == SDL_EVENT_DID_ENTER_BACKGROUND;
    g_pause_cv.notify_all();
  }
  return true;
}
}  // namespace

// Called once per frame by the game hooks: blocks while the app is in the background.
extern "C" void rex_wait_while_in_background() {
  if (!g_in_background.load(std::memory_order_relaxed)) {
    return;
  }
  std::unique_lock<std::mutex> lock(g_pause_mutex);
  g_pause_cv.wait(lock, [] { return !g_in_background.load() || g_stop_pause_worker; });
}

// The desktop RunWindowedApp(), asking for the app by name: on Android the SDK registers apps in a
// table (XE_UI_WINDOWED_APPS_IN_LIBRARY) instead of defining GetWindowedAppCreator().
int RunGame(int argc, char** argv) {
  auto remaining = rex::cvar::Init(argc, argv);
  rex::cvar::ApplyEnvironment();
  rex::InitLoggingEarly();
  thps::driver::Setup(NativeLibraryDir());

  // A 16:9 game: without this SDL opens the window in whatever orientation the device happens to be
  // in, which can be portrait.
  SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");

  int result;
  {
    rex::ui::SDLWindowedAppContext app_context;
    if (!app_context.Initialize()) {
      LOGE("SDL windowed app context failed to initialise");
      return EXIT_FAILURE;
    }
    auto creator = rex::ui::WindowedApp::GetCreator("thps_p8");
    if (!creator) {
      LOGE("app 'thps_p8' is not registered (REX_DEFINE_APP missing from libmain.so?)");
      return EXIT_FAILURE;
    }
    std::unique_ptr<rex::ui::WindowedApp> app = creator(app_context);

    const auto& option_names = app->GetPositionalOptions();
    std::map<std::string, std::string> parsed;
    const size_t count = std::min(remaining.size(), option_names.size());
    for (size_t i = 0; i < count; ++i) {
      parsed[option_names[i]] = remaining[i];
    }
    app->SetParsedArguments(std::move(parsed));

    if (app->OnInitialize()) {
      thps::driver::MarkSuccess();
      // After initialisation so the game's threads exist and the log is open; the watcher also
      // picks up threads created later.
      auto affinity = thps::affinity::Start();
      std::thread pause_worker(PauseWorker);
      rex::system::XThread::delay_park_hook = &rex_wait_while_in_background;
      SDL_AddEventWatch(BackgroundWatch, nullptr);
      thps::display_vsync::Start();
      result = app_context.RunMainMessageLoop();
      SDL_RemoveEventWatch(BackgroundWatch, nullptr);
      rex::system::XThread::delay_park_hook = nullptr;
      {
        std::lock_guard<std::mutex> lock(g_pause_mutex);
        g_want_paused = false;
        g_stop_pause_worker = true;
      }
      g_pause_cv.notify_all();
      pause_worker.join();
      affinity.reset();
    } else {
      result = EXIT_FAILURE;
    }
    app->InvokeOnDestroy();
  }
  return result;
}

}  // namespace

extern "C" __attribute__((visibility("default"))) int SDL_main(int argc, char* argv[]) {
  if (!PrepareAndroidContext()) {
    return EXIT_FAILURE;
  }
  const int result = RunGame(argc, argv);
  ShutdownAndroidContext();
  LOGI("SDL_main finished with %d", result);
  return result;
}
