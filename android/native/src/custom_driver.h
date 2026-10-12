// Project8Recomp - optional custom Vulkan driver (see custom_driver.cpp).
#pragma once

#include <string>

namespace thps::driver {

// If --android_vulkan_driver names a driver folder, loads it and tells the SDK to use it. Returns
// whether a custom driver is in use. Call after the cvars are parsed and before Vulkan starts.
bool Setup(const std::string& native_library_dir);

// Call once the game's graphics are up with the custom driver: clears the failed-launch counter.
void MarkSuccess();

}  // namespace thps::driver
