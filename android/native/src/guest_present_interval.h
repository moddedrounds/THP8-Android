// The game's presentation interval (Android port).
//
// Every frame, sub_82327EE0 (the render thread's frame start) compares the wanted interval - a
// script-registered game variable at 0x826E4F68 (0 = immediate, 1 = every vblank, 2 = every second
// vblank; registered by sub_82329D88) - with the applied one (0x826E4FB8) and, when they differ,
// calls sub_823B8110(device, interval) with 1, 2 or 0x80000000 (immediate). The D3D layer then waits
// that many vblanks per present. With interval 2 the game runs at an even 30 however light the
// frame is; that was the "random" drop to 30 seen in the 60 fps mode.
//
// android_present_interval_max caps what the game may ask for (0 = leave it alone); the 60 fps
// modes set it to 1 so the frame rate cap alone decides. Every change is logged.

#pragma once

#include <cstdint>

#include <rex/cvar.h>
#include <rex/hook.h>
#include <rex/logging/macros.h>

REXCVAR_DEFINE_UINT32(android_present_interval_max, 0, "Android",
                      "Highest presentation interval the game may set (1 = every vblank; 0 = the "
                      "game's own choice). The 60 fps modes use 1")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

extern "C" REX_FUNC(__imp__sub_823B8110);

#if !defined(__APPLE__)
REX_HOOK_RAW(sub_823B8110) {
  REX_FUNC_PROLOGUE();
  const uint32_t wanted = ctx.r4.u32;
  const uint32_t max = REXCVAR_GET(android_present_interval_max);
  if (max && wanted != 0x80000000u && wanted > max) {
    ctx.r4.u64 = max;
  }
  REXLOG_INFO("present interval: game set {:#x}, applied {:#x}", wanted, ctx.r4.u32);
  __imp__sub_823B8110(ctx, base);
}
#endif
