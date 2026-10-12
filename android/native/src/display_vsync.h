// Display vsync timing for the emulator's vblank lock (display_vsync.cpp).
#pragma once

namespace thps::display_vsync {

// Starts the AChoreographer thread that reports every display vsync to the SDK presenter.
void Start();

}  // namespace thps::display_vsync
