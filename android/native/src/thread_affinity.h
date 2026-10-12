// Core placement for the game's threads on big.LITTLE phones and handhelds.
//
// Android's scheduler tends to stack the busiest threads on the single prime core (profiled on the
// Snapdragon 8 Gen 2 of the AYN Thor: the guest render thread, the GPU command thread and a guest
// polling thread all ran on the one X3 core, so they time-sliced instead of running in parallel).
// This watches per-thread CPU use and gives each of the hottest threads a core of its own, fastest
// first, while keeping everything else off those cores.
//
// It only changes thread affinity (sched_setaffinity on threads of this process), never priorities
// or the game's behaviour. Controlled by the android_thread_affinity cvar.

#pragma once

#include <memory>

namespace thps::affinity {

class Manager {
 public:
  virtual ~Manager() = default;
};

// Starts the watcher thread if the android_thread_affinity cvar is on; the returned object stops
// and joins it when destroyed. Returns null when disabled or when the cores are all alike.
std::unique_ptr<Manager> Start();

}  // namespace thps::affinity
