// Settings changed while the game runs (see live_settings.cpp).

#pragma once

namespace thps::live {

// Applies files/live_cvars.txt when it changed since the last call (cheap: one stat()).
void PollFile();

}  // namespace thps::live
