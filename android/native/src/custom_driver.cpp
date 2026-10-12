// Project8Recomp - optional custom Vulkan driver (Mesa Turnip) through libadrenotools.
//
// With --android_vulkan_driver=<folder> the app looks for <app internal files>/drivers/<folder>/
// (GameActivity copies drivers there from the external files/drivers/ folder, because a library can
// only be dlopen'd from internal storage), opens a patched libvulkan.so that loads that driver
// instead of the system one, and hands its handle to the SDK through REX_VULKAN_LOADER_HANDLE.
// Anything going wrong leaves the stock driver in place. The outcome is written to
// <internal files>/drivers/last_driver.txt for the launcher.

#include "custom_driver.h"

#include <SDL3/SDL_system.h>
#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <stdlib.h>

#include <cstdio>
#include <sstream>
#include <string>

#include <rex/cvar.h>
#include <vulkan/vulkan.h>

#if defined(THPS_HAVE_ADRENOTOOLS)
#include <adrenotools/driver.h>
#endif

REXCVAR_DEFINE_STRING(android_vulkan_driver, "", "Android",
                      "Folder name(s) under <internal files>/drivers/ holding a custom Vulkan driver "
                      "(libadrenotools), comma-separated: the first that finds the GPU is used. "
                      "Empty = the system driver.");
REXCVAR_DEFINE_STRING(android_vulkan_driver_lib, "vulkan.ad07xx.so", "Android",
                      "Library file name of the custom Vulkan driver inside its folder.");
REXCVAR_DEFINE_STRING(android_vulkan_driver_env, "", "Android",
                      "KEY=VALUE pairs (separated by ';') to put in the environment before the "
                      "custom driver loads (e.g. TU_DEBUG=sysmem).");
REXCVAR_DEFINE_BOOL(android_vulkan_driver_verify, true, "Android",
                    "Before using a custom driver, check that it finds the GPU and the features "
                    "the SDK requires; otherwise use the system driver for this and later launches.");
REXCVAR_DEFINE_BOOL(android_gpu_turbo, false, "Android",
                    "Ask libadrenotools to keep the GPU at its highest clocks (needs a custom driver).");

namespace thps::driver {
namespace {
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "thps_driver", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "thps_driver", __VA_ARGS__)

// Consecutive launches that started loading a custom driver and never got the graphics up (a crash
// or an init failure), counted per driver folder. After two that driver is skipped (the system
// driver is used) until the launcher resets it: a new copy of the driver, or the player choosing it
// again.
constexpr int kMaxFailedLaunches = 2;

bool g_custom_loaded = false;
std::string g_loaded_name;

std::string DriversDir() {
  const char* internal = SDL_GetAndroidInternalStoragePath();
  return internal ? std::string(internal) + "/drivers/" : std::string();
}

std::string FailCounterPath(const std::string& name) {
  const std::string dir = DriversDir();
  return dir.empty() ? dir : dir + ".failed_" + name;
}

int ReadFailCounter(const std::string& name) {
  const std::string path = FailCounterPath(name);
  if (path.empty()) {
    return 0;
  }
  int n = 0;
  if (FILE* f = fopen(path.c_str(), "r")) {
    if (fscanf(f, "%d", &n) != 1) {
      n = 0;
    }
    fclose(f);
  }
  return n;
}

void WriteFailCounter(const std::string& name, int n) {
  const std::string path = FailCounterPath(name);
  if (path.empty()) {
    return;
  }
  if (FILE* f = fopen(path.c_str(), "w")) {
    fprintf(f, "%d\n", n);
    fclose(f);
  }
}

// One line for the launcher's info panel: which driver this launch used, and why.
void WriteStatus(const std::string& text) {
  const std::string dir = DriversDir();
  if (dir.empty()) {
    return;
  }
  if (FILE* f = fopen((dir + "last_driver.txt").c_str(), "w")) {
    fprintf(f, "%s\n", text.c_str());
    fclose(f);
  }
}

#if defined(THPS_HAVE_ADRENOTOOLS)
// Creates a throwaway instance on the freshly loaded driver and checks that it finds a GPU with the
// features the SDK cannot run without. A Turnip build that does not know the GPU (an Adreno 8xx
// driver on a 7xx, or the reverse) reports no physical device here instead of failing later in
// the SDK. Returns an empty string when usable, otherwise the reason.
std::string Verify(void* handle) {
  auto get_proc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(handle, "vkGetInstanceProcAddr"));
  if (!get_proc) {
    return "no vkGetInstanceProcAddr";
  }
  auto create_instance = reinterpret_cast<PFN_vkCreateInstance>(get_proc(nullptr, "vkCreateInstance"));
  if (!create_instance) {
    return "no vkCreateInstance";
  }
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.pApplicationName = "driver check";
  app.apiVersion = VK_API_VERSION_1_1;
  VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  info.pApplicationInfo = &app;
  VkInstance instance = VK_NULL_HANDLE;
  VkResult result = create_instance(&info, nullptr, &instance);
  if (result != VK_SUCCESS) {
    char text[48];
    snprintf(text, sizeof(text), "vkCreateInstance failed (%d)", int(result));
    return text;
  }
  auto destroy_instance =
      reinterpret_cast<PFN_vkDestroyInstance>(get_proc(instance, "vkDestroyInstance"));
  auto enumerate =
      reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(get_proc(instance, "vkEnumeratePhysicalDevices"));
  auto get_features =
      reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures>(get_proc(instance, "vkGetPhysicalDeviceFeatures"));
  auto get_properties =
      reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(get_proc(instance, "vkGetPhysicalDeviceProperties"));
  std::string problem;
  uint32_t count = 0;
  if (!enumerate || !get_features || !get_properties ||
      enumerate(instance, &count, nullptr) != VK_SUCCESS || count == 0) {
    problem = "the driver found no GPU";
  } else {
    count = 1;
    VkPhysicalDevice device = VK_NULL_HANDLE;
    enumerate(instance, &count, &device);
    VkPhysicalDeviceFeatures features{};
    get_features(device, &features);
    VkPhysicalDeviceProperties properties{};
    get_properties(device, &properties);
    if (!features.independentBlend || !features.fragmentStoresAndAtomics ||
        !features.vertexPipelineStoresAndAtomics) {
      problem = std::string("'") + properties.deviceName + "' lacks a required feature";
    } else {
      LOGI("custom driver sees '%s', Vulkan %u.%u", properties.deviceName,
           VK_API_VERSION_MAJOR(properties.apiVersion), VK_API_VERSION_MINOR(properties.apiVersion));
    }
  }
  if (destroy_instance) {
    destroy_instance(instance, nullptr);
  }
  return problem;
}
#endif
}  // namespace

void MarkSuccess() {
  if (g_custom_loaded && ReadFailCounter(g_loaded_name) != 0) {
    WriteFailCounter(g_loaded_name, 0);
  }
}

namespace {
// The library inside a driver folder: android_vulkan_driver_lib when present, else the only .so.
std::string FindLibrary(const std::string& dir) {
  const std::string preferred = REXCVAR_GET(android_vulkan_driver_lib);
  if (FILE* f = fopen((dir + preferred).c_str(), "rb")) {
    fclose(f);
    return preferred;
  }
  std::string found;
  if (DIR* d = opendir(dir.c_str())) {
    while (dirent* e = readdir(d)) {
      const std::string n = e->d_name;
      if (n.size() > 3 && n.compare(n.size() - 3, 3, ".so") == 0) {
        found = n;
        break;
      }
    }
    closedir(d);
  }
  return found;
}

// Loads one driver folder; on failure *reason says why (for the launcher).
bool TryDriver(const std::string& name, const std::string& native_library_dir, std::string* reason);
}  // namespace

bool Setup(const std::string& native_library_dir) {
  const std::string list = REXCVAR_GET(android_vulkan_driver);
  if (list.empty()) {
    WriteStatus("system");
    return false;
  }
  std::string reasons;
  std::stringstream names(list);
  for (std::string name; std::getline(names, name, ',');) {
    if (name.empty()) continue;
    std::string reason;
    if (TryDriver(name, native_library_dir, &reason)) {
      return true;
    }
    reasons += (reasons.empty() ? "" : "; ") + reason;
  }
  WriteStatus("system (" + reasons + ")");
  return false;
}

namespace {
bool TryDriver(const std::string& name, const std::string& native_library_dir, std::string* reason) {
#if !defined(THPS_HAVE_ADRENOTOOLS)
  LOGE("custom driver '%s' requested, but this build has no libadrenotools", name.c_str());
  *reason = "this build cannot load custom drivers";
  return false;
#else
  const std::string drivers = DriversDir();
  if (drivers.empty()) {
    LOGE("no internal storage path");
    *reason = "no internal storage path";
    return false;
  }
  const int failed = ReadFailCounter(name);
  if (failed >= kMaxFailedLaunches) {
    LOGE("custom driver '%s' failed to start %d launches in a row; using the system driver "
         "(delete %s to retry)", name.c_str(), failed, FailCounterPath(name).c_str());
    *reason = name + " failed to start before";
    return false;
  }
  std::string dir = drivers + name + "/";
  const std::string lib = FindLibrary(dir);
  const std::string path = dir + lib;
  if (lib.empty()) {
    LOGE("custom driver library not found: %s", path.c_str());
    *reason = name + " not installed";
    return false;
  }

  std::stringstream env(REXCVAR_GET(android_vulkan_driver_env));
  for (std::string item; std::getline(env, item, ';');) {
    const size_t eq = item.find('=');
    if (eq != std::string::npos && eq > 0) {
      setenv(item.substr(0, eq).c_str(), item.substr(eq + 1).c_str(), 1);
      LOGI("driver env: %s", item.c_str());
    }
  }

  WriteFailCounter(name, failed + 1);  // cleared by MarkSuccess() once the graphics are up
  void* handle = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr,
                                            (native_library_dir + "/").c_str(), dir.c_str(),
                                            lib.c_str(), nullptr, nullptr);
  if (!handle) {
    LOGE("adrenotools_open_libvulkan failed for %s", path.c_str());
    *reason = name + " could not be loaded";
    return false;
  }
  if (REXCVAR_GET(android_vulkan_driver_verify)) {
    const std::string problem = Verify(handle);
    if (!problem.empty()) {
      // Not usable on this GPU: no point retrying it next launch either.
      LOGE("custom driver %s not usable: %s; using the system driver", path.c_str(), problem.c_str());
      WriteFailCounter(name, kMaxFailedLaunches);
      *reason = name + ": " + problem;
      return false;
    }
  }
  if (REXCVAR_GET(android_gpu_turbo)) {
    adrenotools_set_turbo(true);
  }
  char text[40];
  snprintf(text, sizeof(text), "0x%llx", static_cast<unsigned long long>(
                                              reinterpret_cast<uintptr_t>(handle)));
  setenv("REX_VULKAN_LOADER_HANDLE", text, 1);
  g_custom_loaded = true;
  g_loaded_name = name;
  LOGI("custom Vulkan driver %s loaded (loader handle %s)", path.c_str(), text);
  WriteStatus(name);
  return true;
#endif
}
}  // namespace

}  // namespace thps::driver
