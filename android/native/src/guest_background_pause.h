// Background pause (Android port).
//
// sub_82327EE0 is the render thread's frame start (see guest_present_interval.h). While the app is in
// the background the frame loop parks here (android_main.cpp, rex_wait_while_in_background), so the
// emulated console stops using the CPU and GPU instead of running on behind the next app.

#pragma once

#include <rex/hook.h>

extern "C" void rex_wait_while_in_background();
extern "C" REX_FUNC(__imp__sub_82327EE0);

#if !defined(__APPLE__)
REX_HOOK_RAW(sub_82327EE0) {
  REX_FUNC_PROLOGUE();
  rex_wait_while_in_background();
  __imp__sub_82327EE0(ctx, base);
}
#endif
