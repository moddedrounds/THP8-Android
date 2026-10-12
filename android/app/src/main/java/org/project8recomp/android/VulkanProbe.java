package org.project8recomp.android;

/** Thin wrapper over libthps8probe.so (native/src/vulkan_probe.cpp). Needs neither the SDK nor the game. */
final class VulkanProbe {
    static {
        System.loadLibrary("thps8probe");
    }

    private VulkanProbe() {}

    /** Human-readable report of the device's Vulkan GPU, driver and the features the SDK requires. */
    static native String run();
}
