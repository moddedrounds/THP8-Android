// Stop the guest's second frame-wait loop from burning a core.
//
// guest_swap_wait.h covers the spin at 0x82327FCC in sub_82327EE0, which polls the frame counter at
// 0x82970A10 through sub_82397688. The same function has a second spin a little further on:
//
//     loc_823280D0:
//         bl    sub_82397698        ; -> 64-bit counter at 0x82970A08
//         ld    r11, -25608(r31)    ; -> deadline at 0x82779BF8
//         cmpld cr6, r3, r11
//         blt   cr6, loc_823280D0   ; spin while counter < deadline
//
// sub_82397698 is a leaf that only loads that counter. The loop busy-waits for it exactly like the
// first one does, and an on-device profile of the Android port (Snapdragon 8 Gen 2) showed the guest
// render thread spending ~36% of its cycles in sub_82327EE0 even with guest_swap_wait on.
//
// This hook sleeps briefly when it is called from that loop and the counter has not reached the
// deadline yet, then returns the true current counter value. Nothing is faked: the guest still
// sees the real counter and still decides for itself when the wait is over; it just polls at
// ~1/guest_counter2_wait_us instead of as fast as the CPU can go. Default off, like every lever that
// touches guest behaviour.
//
// The same loop is the game's own frame limiter: 0x82970A08 counts vblanks, and after each wait the
// deadline becomes counter + the frame interval at 0x82779C00 (copied from the script variable at
// 0x826E4F68: 1 = every vblank, 2 = every second vblank). The game sets 2 in menus, cutscenes and
// some heavy stretches, which holds it at an even 30 however light the frame is.
// guest_frame_interval_max caps the interval used for the next deadline (0 = the game's own); the
// game's variable is re-read every frame, so its own value comes back as soon as the cap is off.

#pragma once

#include <chrono>
#include <cstdint>
#include <thread>

#include <rex/cvar.h>
#include <rex/hook.h>

REXCVAR_DEFINE_BOOL(guest_counter2_wait, false, "Guest",
                    "Sleep briefly while the guest polls its second frame counter "
                    "(sub_82397698 from the loop at 0x823280D0) instead of spinning");
REXCVAR_DEFINE_UINT32(guest_counter2_wait_us, 150, "Guest",
                      "Microseconds slept per poll of the second frame counter while it is "
                      "below its deadline");
REXCVAR_DEFINE_UINT32(guest_frame_interval_max, 0, "Guest",
                      "Highest number of vblanks the game's own frame limiter may wait per frame "
                      "(1 = up to 60 fps everywhere, incl. menus and cutscenes; 0 = the game's own)")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

namespace thps::counter2 {

inline constexpr uint32_t kCounter = 0x82970A08;
inline constexpr uint32_t kDeadline = 0x82779BF8;
inline constexpr uint32_t kLoopReturn = 0x823280D4;  // lr of the call inside the spin
inline constexpr uint32_t kFinalReturn = 0x823280E4;  // lr of the read before deadline = counter + interval
inline constexpr uint32_t kInterval = 0x82779C00;     // the limiter's frame interval in vblanks

}  // namespace thps::counter2

// Generated guest functions expose weak ELF aliases for title hooks; Mach-O has none (see the
// other hook headers).
#if !defined(__APPLE__)
#if defined(__ANDROID__)
// android/native/src/android_main.cpp: blocks while the app is in the background.
extern "C" void rex_wait_while_in_background();
#endif

REX_HOOK_RAW(sub_82397698) {
  REX_FUNC_PROLOGUE();
#if defined(__ANDROID__)
  rex_wait_while_in_background();  // poll loops park while in the background
#endif

  if (REXCVAR_GET(guest_counter2_wait) && ctx.lr == thps::counter2::kLoopReturn &&
      REX_LOAD_U64(thps::counter2::kCounter) < REX_LOAD_U64(thps::counter2::kDeadline)) {
    std::this_thread::sleep_for(std::chrono::microseconds(REXCVAR_GET(guest_counter2_wait_us)));
  }

  if (ctx.lr == thps::counter2::kFinalReturn) {
    const uint32_t max = REXCVAR_GET(guest_frame_interval_max);
    if (max && REX_LOAD_U32(thps::counter2::kInterval) > max) {
      REX_STORE_U32(thps::counter2::kInterval, max);
    }
  }

  // The original body: lis r11,-32105 ; ld r3,2568(r11) ; blr
  ctx.r3.u64 = REX_LOAD_U64(thps::counter2::kCounter);
}
#endif
