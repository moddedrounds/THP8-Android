// Vulkan capability probe.
//
// Reports which GPU and driver the device offers and whether it has the Vulkan features the
// ReXGlue Xbox 360 GPU emulation requires (src/ui/vulkan/vulkan_device.cpp in the SDK). Depends only
// on the NDK, so it runs before - and without - the game.

#include <jni.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cinttypes>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

std::string Fmt(const char* format, ...) __attribute__((format(printf, 1, 2)));
std::string Fmt(const char* format, ...) {
  char buffer[512];
  va_list args;
  va_start(args, format);
  vsnprintf(buffer, sizeof(buffer), format, args);
  va_end(args);
  return buffer;
}

std::string Version(uint32_t v) {
  return Fmt("%u.%u.%u", VK_API_VERSION_MAJOR(v), VK_API_VERSION_MINOR(v), VK_API_VERSION_PATCH(v));
}

bool HasExtension(const std::vector<VkExtensionProperties>& list, const char* name) {
  return std::any_of(list.begin(), list.end(),
                     [&](const VkExtensionProperties& e) { return !strcmp(e.extensionName, name); });
}

const char* DeviceType(VkPhysicalDeviceType type) {
  switch (type) {
    case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
    case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete";
    case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual";
    case VK_PHYSICAL_DEVICE_TYPE_CPU: return "cpu";
    default: return "other";
  }
}

// One line per feature: ok / MISSING. `required` marks what the SDK refuses to start without.
void Feature(std::string& out, const char* name, VkBool32 supported, bool required) {
  out += Fmt("  %-34s %s%s\n", name, supported ? "yes" : "NO", (!supported && required) ? "   <-- REQUIRED" : "");
}

std::string Probe() {
  std::string out;
  out += "Vulkan probe for Project8Recomp\n";

  uint32_t instance_version = VK_API_VERSION_1_0;
  auto enumerate_version = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
      vkGetInstanceProcAddr(nullptr, "vkEnumerateInstanceVersion"));
  if (enumerate_version) {
    enumerate_version(&instance_version);
  }
  out += Fmt("Instance version: %s\n", Version(instance_version).c_str());

  uint32_t instance_extension_count = 0;
  vkEnumerateInstanceExtensionProperties(nullptr, &instance_extension_count, nullptr);
  std::vector<VkExtensionProperties> instance_extensions(instance_extension_count);
  vkEnumerateInstanceExtensionProperties(nullptr, &instance_extension_count, instance_extensions.data());
  out += Fmt("VK_KHR_surface: %s, VK_KHR_android_surface: %s\n",
             HasExtension(instance_extensions, "VK_KHR_surface") ? "yes" : "NO",
             HasExtension(instance_extensions, "VK_KHR_android_surface") ? "yes" : "NO");

  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.pApplicationName = "thps8probe";
  app.apiVersion = std::min(instance_version, uint32_t(VK_API_VERSION_1_1));
  VkInstanceCreateInfo instance_info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  instance_info.pApplicationInfo = &app;
  VkInstance instance = VK_NULL_HANDLE;
  VkResult result = vkCreateInstance(&instance_info, nullptr, &instance);
  if (result != VK_SUCCESS) {
    out += Fmt("vkCreateInstance FAILED (%d)\n", int(result));
    return out;
  }

  uint32_t device_count = 0;
  vkEnumeratePhysicalDevices(instance, &device_count, nullptr);
  std::vector<VkPhysicalDevice> devices(device_count);
  vkEnumeratePhysicalDevices(instance, &device_count, devices.data());
  out += Fmt("Physical devices: %u\n", device_count);

  int index = 0;
  for (VkPhysicalDevice device : devices) {
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(device, &props);
    out += Fmt("\n== Device %d: %s ==\n", index++, props.deviceName);
    out += Fmt("  type %s, vendor 0x%04X, device 0x%04X\n", DeviceType(props.deviceType), props.vendorID,
               props.deviceID);
    out += Fmt("  API %s, driver version 0x%08X\n", Version(props.apiVersion).c_str(), props.driverVersion);

    uint32_t extension_count = 0;
    vkEnumerateDeviceExtensionProperties(device, nullptr, &extension_count, nullptr);
    std::vector<VkExtensionProperties> extensions(extension_count);
    vkEnumerateDeviceExtensionProperties(device, nullptr, &extension_count, extensions.data());

    // Driver name/info, when the driver reports it (Vulkan 1.2 or VK_KHR_driver_properties).
    if (HasExtension(extensions, "VK_KHR_driver_properties") || props.apiVersion >= VK_API_VERSION_1_2) {
      auto get_props2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
          vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2"));
      if (!get_props2) {
        get_props2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2KHR"));
      }
      if (get_props2) {
        VkPhysicalDeviceDriverProperties driver{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
        VkPhysicalDeviceProperties2 props2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        props2.pNext = &driver;
        get_props2(device, &props2);
        out += Fmt("  driver: %s (%s), conformance %u.%u.%u.%u\n", driver.driverName, driver.driverInfo,
                   driver.conformanceVersion.major, driver.conformanceVersion.minor,
                   driver.conformanceVersion.subminor, driver.conformanceVersion.patch);
      }
    }

    VkPhysicalDeviceFeatures f;
    vkGetPhysicalDeviceFeatures(device, &f);
    out += "  Features the SDK requires (vulkan_device.cpp):\n";
    Feature(out, "independentBlend", f.independentBlend, true);
    Feature(out, "fragmentStoresAndAtomics", f.fragmentStoresAndAtomics, true);
    Feature(out, "vertexPipelineStoresAndAtomics", f.vertexPipelineStoresAndAtomics, true);
    Feature(out, "geometryShader", f.geometryShader, true);
    Feature(out, "fillModeNonSolid", f.fillModeNonSolid, true);
    out += "  Features it uses when present:\n";
    Feature(out, "fullDrawIndexUint32", f.fullDrawIndexUint32, false);
    Feature(out, "sampleRateShading", f.sampleRateShading, false);
    Feature(out, "shaderClipDistance", f.shaderClipDistance, false);
    Feature(out, "shaderCullDistance", f.shaderCullDistance, false);
    Feature(out, "samplerAnisotropy", f.samplerAnisotropy, false);
    Feature(out, "textureCompressionBC", f.textureCompressionBC, false);
    Feature(out, "tessellationShader", f.tessellationShader, false);
    Feature(out, "sparseBinding", f.sparseBinding, false);
    Feature(out, "shaderFloat64", f.shaderFloat64, false);

    out += Fmt("  VK_KHR_swapchain: %s\n", HasExtension(extensions, "VK_KHR_swapchain") ? "yes" : "NO");

    const VkPhysicalDeviceLimits& l = props.limits;
    out += Fmt("  limits: maxImageDimension2D %u, maxBoundDescriptorSets %u, maxStorageBufferRange %u,\n"
               "          maxPerStageDescriptorSamplers %u, maxColorAttachments %u, maxMemoryAllocationCount %u\n",
               l.maxImageDimension2D, l.maxBoundDescriptorSets, l.maxStorageBufferRange,
               l.maxPerStageDescriptorSamplers, l.maxColorAttachments, l.maxMemoryAllocationCount);

    VkPhysicalDeviceMemoryProperties memory;
    vkGetPhysicalDeviceMemoryProperties(device, &memory);
    for (uint32_t h = 0; h < memory.memoryHeapCount; ++h) {
      out += Fmt("  memory heap %u: %" PRIu64 " MiB%s\n", h, uint64_t(memory.memoryHeaps[h].size >> 20),
                 (memory.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) ? " (device local)" : "");
    }

    uint32_t family_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(device, &family_count, nullptr);
    std::vector<VkQueueFamilyProperties> families(family_count);
    vkGetPhysicalDeviceQueueFamilyProperties(device, &family_count, families.data());
    for (uint32_t q = 0; q < family_count; ++q) {
      out += Fmt("  queue family %u: %u queue(s)%s%s%s\n", q, families[q].queueCount,
                 (families[q].queueFlags & VK_QUEUE_GRAPHICS_BIT) ? " graphics" : "",
                 (families[q].queueFlags & VK_QUEUE_COMPUTE_BIT) ? " compute" : "",
                 (families[q].queueFlags & VK_QUEUE_TRANSFER_BIT) ? " transfer" : "");
    }

    const bool ok = f.independentBlend && f.fragmentStoresAndAtomics && f.vertexPipelineStoresAndAtomics &&
                    f.geometryShader && f.fillModeNonSolid && HasExtension(extensions, "VK_KHR_swapchain");
    out += ok ? "  VERDICT: has everything the SDK requires.\n"
              : "  VERDICT: MISSING a required feature - the game will refuse to start on this GPU.\n";
  }

  vkDestroyInstance(instance, nullptr);
  return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_org_project8recomp_android_VulkanProbe_run(JNIEnv* env, jclass) {
  return env->NewStringUTF(Probe().c_str());
}
