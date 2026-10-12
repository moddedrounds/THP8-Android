// Two guest busy-waits that held performance cores (Android port, measured on the AYN Thor).
//
// 1. GPU fence wait. sub_823B58A0 (D3D "block until the GPU passed fence X") loops calling
//    sub_823BF8B0, a short delay plus a GPU-progress watchdog check that returns 1 while the wait
//    should go on. The game's render thread sat in it ~10-20 ms of every frame, spinning at full
//    speed on a big core.
// 2. Job wait. sub_821E6928 spins while the busy word of job r3 (table at 0x82731F38) is non-zero (it
//    returns when it reads 0); the main thread uses it to wait for its worker jobs.
//
// Qualcomm core_ctl keeps only 3-4 of the 5 performance cores online at times, and with these two
// spinning the GPU command processor was left runnable but without a core for up to ~50 ms - the
// remaining slow frames. Both hooks keep the exact semantics (they call the generated function,
// which still decides) and only sleep briefly between polls once a wait is under way.

#pragma once

#include <chrono>
#include <cstdint>
#include <thread>

#include <rex/cvar.h>
#include <rex/hook.h>

REXCVAR_DEFINE_BOOL(android_guest_wait_sleep, true, "Android",
                    "Sleep briefly (instead of spinning) in the guest's GPU-fence and job-completion "
                    "waits, so they do not hold performance cores the command processor needs");
REXCVAR_DEFINE_UINT32(android_guest_wait_sleep_us, 50, "Android",
                      "Sleep per poll once a guest fence/job wait is under way (microseconds)");

extern "C" REX_FUNC(__imp__sub_823BF8B0);
extern "C" REX_FUNC(__imp__sub_821E6928);

#if !defined(__APPLE__)
// Fence wait body: returns 1 while the caller should keep waiting.
REX_HOOK_RAW(sub_823BF8B0) {
  REX_FUNC_PROLOGUE();
  __imp__sub_823BF8B0(ctx, base);
  if (ctx.r3.u32 == 1 && REXCVAR_GET(android_guest_wait_sleep)) {
    std::this_thread::sleep_for(std::chrono::microseconds(REXCVAR_GET(android_guest_wait_sleep_us)));
  }
}

// Job wait: poll the job's completion word with sleeps, then let the generated function run (it
// returns at once, leaving the registers exactly as the original would).
REX_HOOK_RAW(sub_821E6928) {
  REX_FUNC_PROLOGUE();
  if (ctx.r3.u32 != 0xFFFFFFFFu && REXCVAR_GET(android_guest_wait_sleep)) {
    const uint32_t table = REX_LOAD_U32(0x82731F38u + 8);
    const uint32_t word = table + (ctx.r3.u32 << 5);
    uint32_t polls = 0;
    while (REX_LOAD_U32(word) != 0) {  // busy word: the job is done when it reads 0
      if (++polls < 256) {
        std::this_thread::yield();
      } else {
        std::this_thread::sleep_for(std::chrono::microseconds(20));
      }
    }
  }
  __imp__sub_821E6928(ctx, base);
}
#endif
