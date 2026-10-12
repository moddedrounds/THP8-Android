// Internal render resolution (Android port).
//
// The game picks its back buffer size itself: sub_8235F2A8 (called once, from the D3D device setup
// sub_82360410) calls XGetVideoMode and then copies width / height / a third mode word out of a table
// of display modes (0x820178F8, 740-byte entries) into the presentation parameters at r3+0 / +4 /
// +16. The reported video mode does not change the choice, so the hook scales the picked size
// instead; the engine derives its screen-space scales from these fields (sub_82360410 +7676/+7680).

#pragma once

#include <algorithm>
#include <cstdint>

#include <rex/cvar.h>
#include <rex/hook.h>
#include <rex/logging/macros.h>

REXCVAR_DEFINE_INT32(android_render_scale, 100, "Android",
                     "Internal render resolution in percent of the game's own back buffer size "
                     "(1280x720): 50-100, applied at startup")
    .range(25, 100);

REXCVAR_DEFINE_INT32(android_video_mode_index, -1, "Android",
                     "Force an entry of the game's display mode table (0x820178F8, 740-byte entries; the "
                     "game picks 4/6/8/9/10/11 from a quality value). -1 = the game's choice. Experimental")
    .range(-1, 15);

extern "C" REX_FUNC(__imp__sub_8235F2A8);

#if !defined(__APPLE__)
REX_HOOK_RAW(sub_8235F2A8) {
  REX_FUNC_PROLOGUE();
  const uint32_t params = ctx.r3.u32;
  __imp__sub_8235F2A8(ctx, base);
  constexpr uint32_t kTable = 0x820178F8u, kEntry = 740, kIndexWord = 0x827EFFE8u;
  {
    static bool dumped = false;
    if (!dumped) {
      dumped = true;
      for (uint32_t i = 0; i < 16; ++i) {
        const uint32_t e = kTable + i * kEntry;
        REXLOG_INFO("display mode table [{}]: {}x{} word {:#x} | +0 {:#x} +16 {:#x} +20 {:#x} +24 {:#x}", i,
                    REX_LOAD_U32(e + 4), REX_LOAD_U32(e + 8), REX_LOAD_U32(e + 12), REX_LOAD_U32(e + 0),
                    REX_LOAD_U32(e + 16), REX_LOAD_U32(e + 20), REX_LOAD_U32(e + 24));
      }
      REXLOG_INFO("display mode: game chose index {}", REX_LOAD_U32(kIndexWord));
    }
  }
  if (const int forced = REXCVAR_GET(android_video_mode_index); forced >= 0) {
    const uint32_t e = kTable + uint32_t(forced) * kEntry;
    REX_STORE_U32(kIndexWord, uint32_t(forced));
    REX_STORE_U32(params + 0, REX_LOAD_U32(e + 4));
    REX_STORE_U32(params + 4, REX_LOAD_U32(e + 8));
    REX_STORE_U32(params + 16, REX_LOAD_U32(e + 12));
    REXLOG_INFO("display mode: forced index {}", forced);
  }
  const uint32_t width = REX_LOAD_U32(params + 0);
  const uint32_t height = REX_LOAD_U32(params + 4);
  const int scale = REXCVAR_GET(android_render_scale);
  uint32_t new_width = width, new_height = height;
  if (scale < 100 && width && height) {
    // Nearest multiple of 8 (the game's own 584 lines are not a multiple of 16), which keeps the
    // aspect ratio within ~1 %.
    new_width = std::max<uint32_t>(320, (width * scale / 100 + 4) & ~7u);
    new_height = std::max<uint32_t>(176, (height * scale / 100 + 4) & ~7u);
    REX_STORE_U32(params + 0, new_width);
    REX_STORE_U32(params + 4, new_height);
  }
  REXLOG_INFO("render resolution: game picked {}x{} (mode word {:#x}), using {}x{} ({}%)", width,
              height, REX_LOAD_U32(params + 16), new_width, new_height, scale);
}
#endif
