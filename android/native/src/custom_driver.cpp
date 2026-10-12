// Project8Recomp - optional custom Vulkan driver (Mesa Turnip) through libadrenotools.
//
// With --android_vulkan_driver=<folder> the app looks for <app internal files>/drivers/<folder>/
// (GameActivity copies drivers there from the external files/drivers/ folder, because a library can
// only be dlopen'd from internal storage), opens a patched libvulkan.so that loads that driver
// instead of the system one, and hands its handle to the SDK through REX_VULKAN_LOADER_HANDLE.
// Anything going wrong leaves the stock driver in place.

#include "custom_driver.h"

#include <SDL3/SDL_system.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdlib.h>

#include <cstdio>
#include <sstream>
#include <string>

#include <rex/cvar.h>

#if defined(THPS_HAVE_ADRENOTOOLS)
#include <adrenotools/driver.h>
#endif

REXCVAR_DEFINE_STRING(android_vulkan_driver, "", "Android",
                      "Folder name under <internal files>/drivers/ holding a custom Vulkan driver "
                      "(libadrenotools). Empty = the system driver.");
REXCVAR_DEFINE_STRING(android_vulkan_driver_lib, "vulkan.ad07xx.so", "Android",
                      "Library file name of the custom Vulkan driver inside its folder.");
REXCVAR_DEFINE_STRING(android_vulkan_driver_env, "", "Android",
                      "KEY=VALUE pairs (separated by ';') to put in the environment before the "
                      "custom driver loads (e.g. TU_DEBUG=sysmem).");
REXCVAR_DEFINE_BOOL(android_gpu_turbo, false, "Android",
                    "Ask libadrenotools to keep the GPU at its highest clocks (needs a custom driver).");

namespace thps::driver {
namespace {
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "thps_driver", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "thps_driver", __VA_ARGS__)

// Consecutive launches that started loading the custom driver and never got the graphics up (a crash
// or an init failure). After two the stock driver is used until one launch succeeds again.
constexpr int kMaxFailedLaunches = 2;

std::string FailCounterPath() {
  const char* internal = SDL_GetAndroidInternalStoragePath();
  return internal ? std::string(internal) + "/drivers/.failed_launches" : std::string();
}

int ReadFailCounter() {
  const std::string path = FailCounterPath();
  if (path.empty()) {
    return 0;
  }
  int n = 0;
  if (FILE* f = fopen(path.c_str(), "r")) {
    if (fscanf(f, "%d", &n) != 1) {
      n = 0;
    }
    fclose(f);
  }
  return n;
}

void WriteFailCounter(int n) {
  const std::string path = FailCounterPath();
  if (path.empty()) {
    return;
  }
  if (FILE* f = fopen(path.c_str(), "w")) {
    fprintf(f, "%d\n", n);
    fclose(f);
  }
}
}  // namespace

void MarkSuccess() {
  if (!REXCVAR_GET(android_vulkan_driver).empty() && ReadFailCounter() != 0) {
    WriteFailCounter(0);
  }
}

bool Setup(const std::string& native_library_dir) {
  const std::string name = REXCVAR_GET(android_vulkan_driver);
  if (name.empty()) {
    return false;
  }
#if !defined(THPS_HAVE_ADRENOTOOLS)
  LOGE("custom driver '%s' requested, but this build has no libadrenotools", name.c_str());
  return false;
#else
  const char* internal = SDL_GetAndroidInternalStoragePath();
  if (!internal) {
    LOGE("no internal storage path");
    return false;
  }
  const int failed = ReadFailCounter();
  if (failed >= kMaxFailedLaunches) {
    LOGE("custom driver '%s' failed to start %d launches in a row; using the system driver "
         "(delete %s to retry)", name.c_str(), failed, FailCounterPath().c_str());
    return false;
  }
  std::string dir = std::string(internal) + "/drivers/" + name + "/";
  const std::string lib = REXCVAR_GET(android_vulkan_driver_lib);
  const std::string path = dir + lib;
  if (FILE* f = fopen(path.c_str(), "rb")) {
    fclose(f);
  } else {
    LOGE("custom driver library not found: %s", path.c_str());
    return false;
  }

  std::stringstream env(REXCVAR_GET(android_vulkan_driver_env));
  for (std::string item; std::getline(env, item, ';');) {
    const size_t eq = item.find('=');
    if (eq != std::string::npos && eq > 0) {
      setenv(item.substr(0, eq).c_str(), item.substr(eq + 1).c_str(), 1);
      LOGI("driver env: %s", item.c_str());
    }
  }

  WriteFailCounter(failed + 1);  // cleared by MarkSuccess() once the graphics are up
  void* handle = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr,
                                            (native_library_dir + "/").c_str(), dir.c_str(),
                                            lib.c_str(), nullptr, nullptr);
  if (!handle) {
    LOGE("adrenotools_open_libvulkan failed for %s", path.c_str());
    return false;
  }
  if (REXCVAR_GET(android_gpu_turbo)) {
    adrenotools_set_turbo(true);
  }
  char text[40];
  snprintf(text, sizeof(text), "0x%llx", static_cast<unsigned long long>(
                                              reinterpret_cast<uintptr_t>(handle)));
  setenv("REX_VULKAN_LOADER_HANDLE", text, 1);
  LOGI("custom Vulkan driver %s loaded (loader handle %s)", path.c_str(), text);
  return true;
#endif
}

}  // namespace thps::driver
