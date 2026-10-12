// Settings changed while the game runs: the in-game menu (GameActivity, Java) and, for tests,
// files/live_cvars.txt (one "name=value" per line, applied when the file changes).
//
// Both end in rex::cvar::SetFlagByName, so only settings the engine reads live take effect at once
// (swap cap, frame generation, upscaler override, affinity mode, spin windows); the resolution is
// picked by the game once at startup and is applied by the menu with a restart.

#include "live_settings.h"

#include <android/log.h>
#include <jni.h>
#include <sys/stat.h>

#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>

#include <rex/cvar.h>
#include <rex/system/kernel_state.h>

namespace thps::live {
namespace {

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "thps_live", __VA_ARGS__)

constexpr const char* kFile = "/sdcard/Android/data/org.project8recomp.android/files/live_cvars.txt";

bool Set(const std::string& name, const std::string& value) {
  const bool ok = rex::cvar::SetFlagByName(name, value);
  LOGI("%s = %s%s", name.c_str(), value.c_str(), ok ? "" : " (rejected)");
  return ok;
}

}  // namespace

void PollFile() {
  static timespec last = {};
  static bool seen = false;
  struct stat st = {};
  if (stat(kFile, &st) != 0) {
    seen = false;
    return;
  }
  if (seen && st.st_mtim.tv_sec == last.tv_sec && st.st_mtim.tv_nsec == last.tv_nsec) {
    return;
  }
  seen = true;
  last = st.st_mtim;
  FILE* f = std::fopen(kFile, "r");
  if (!f) {
    return;
  }
  char line[256];
  while (std::fgets(line, sizeof(line), f)) {
    std::string s(line);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r' || s.back() == ' ')) s.pop_back();
    if (s.rfind("--", 0) == 0) s = s.substr(2);
    if (!s.empty() && s[0] == '!') {
      // "!command args": run a console command (tests: e.g. "!cheat_zone factory" with
      // --dev_console=true). The file is re-read only when it changes, so it runs once.
      const size_t sp = s.find(' ');
      const std::string name = s.substr(1, sp == std::string::npos ? std::string::npos : sp - 1);
      const std::string args = sp == std::string::npos ? std::string() : s.substr(sp + 1);
      const bool ok = rex::cvar::InvokeCommand(name, args);
      LOGI("command %s %s%s", name.c_str(), args.c_str(), ok ? "" : " (unknown)");
      continue;
    }
    const size_t eq = s.find('=');
    if (s.empty() || s[0] == '#' || eq == std::string::npos) continue;
    Set(s.substr(0, eq), s.substr(eq + 1));
  }
  std::fclose(f);
}

}  // namespace thps::live

extern "C" JNIEXPORT jboolean JNICALL Java_org_project8recomp_android_GameActivity_nativeSetCvar(
    JNIEnv* env, jclass, jstring name, jstring value) {
  const char* n = env->GetStringUTFChars(name, nullptr);
  const char* v = env->GetStringUTFChars(value, nullptr);
  const bool ok = thps::live::Set(n, v);
  env->ReleaseStringUTFChars(name, n);
  env->ReleaseStringUTFChars(value, v);
  return ok ? JNI_TRUE : JNI_FALSE;
}

// The menu is open / closed: tell the game the Xbox Guide opened / closed (XN_SYS_UI), which games
// answer by pausing gameplay.
extern "C" JNIEXPORT void JNICALL Java_org_project8recomp_android_GameActivity_nativeSystemUi(
    JNIEnv*, jclass, jboolean open) {
  if (auto* kernel = rex::system::kernel_state()) {
    kernel->BroadcastNotification(0x00000009 /* XN_SYS_UI */, open ? 1 : 0);
    __android_log_print(ANDROID_LOG_INFO, "thps_live", "XN_SYS_UI = %d", open ? 1 : 0);
  }
}
