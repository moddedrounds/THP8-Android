// Registers the title's app class for the Android build.
//
// Same as src/game/src/main.cpp, which cannot be reused as is: it includes the generated header by
// the desktop's relative path, and on Android the generated sources live in a separate directory
// (android/codegen) because the Android codegen template differs from the desktop one.
//
// The hook headers must be included here: each defines REX_HOOK_RAW() overrides of the generated
// functions and their cvars, exactly as on the desktop.

#include "thps_p8_init.h"

#include "guest_counter2_wait.h"
#include "guest_ring_wait.h"
#include "guest_swap_wait.h"
#include "guest_u8x4_unpack.h"
#include "guest_vertex_unpack.h"
#include "guest_wait_sleep.h"
#include "guest_resolution.h"
#include "guest_background_pause.h"
#include "guest_present_interval.h"
#include "thps_p8_app.h"

REX_DEFINE_APP(thps_p8, ThpsP8App::Create)
