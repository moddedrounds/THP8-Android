# Scope: a Project 8-specific fast render path (Android / AYN Thor)

Status 2026-10-10. Written after the locked-30 work (SDK patches 0012-0015) and the resolution option.
Goal: make real (not generated) frame rates above 30 possible at native resolution - a locked 40 on a
120 Hz panel needs <= 25 ms per frame, a real 60 needs <= 16.7 ms - and lower the heat at 30.

## 1. Where a frame goes today (garage spawn, the heaviest view, native 1040x584)

Measured with frames.csv (`--gpu_timestamp_queries=true`), thps8-m14/m15:

| Part | Cost / frame | Notes |
|---|---|---|
| Draws | 1,718 | 300 k vertices, 62 render passes, 235 barriers, ~30-42 resolves |
| GPU busy | 28.2 ms | 100 % 28.2, 75 % 21.8, 50 % 16.8 ms -> ~15 ms scales with pixels, **~13 ms does not** |
| Command processor CPU | ~19 ms | PM4 decode, state/pipeline/descriptor lookup, shared-memory validation (30 MB of texture ranges and 6.6 MB of vertex ranges re-checked per frame), 3.6 k texture-cache lookups |
| Resolves (RT -> EDRAM dump -> shared memory -> texture) | ~4.8 ms GPU | part of the 13 ms fixed GPU cost |
| Per-draw GPU stamps | ~10-17 us per draw, spread evenly | no single expensive shader; cost is per-draw overhead |

Skating (lighter views, ~900-1,300 draws) uncapped already reaches 45.7 fps at 100 %, 52.7 at 75 %,
57.0 at 50 % - but frames alternate 16.7 / 33.3 ms on the 60 Hz panel. The game's simulation runs on
real time, so higher frame rates do not speed it up.

**The limit is per-draw overhead on both the CPU (command processor) and the GPU**, not pixels alone.
That is the gap to the Skate 3 port: it uses the same ReXGlue runtime but adds a game-specific
"native render" path (cvars native_render_*, mesh/2D decode workers) instead of emulating the Xbox 360
GPU for every draw. (Design reference only; no code from it.)

## 2. Three tiers, cheapest first

### Tier 1 - emulator-level, no game knowledge needed (est. 2-4 sessions)

Each item is a measured A/B (live flags) and kept only if it wins; all keep the picture identical.

1. **Native vertex fetch.** Translated vertex shaders read vertices from a storage buffer and unpack
   them in the shader (Xenos vfetch). For the common formats (float3/float2/ubyte4n/short2n, stride
   known per draw) bind real Vulkan vertex buffers and attributes instead. Target: a large part of the
   ~13 ms resolution-independent GPU cost.
2. **Draw-state replay cache in the command processor.** Most draws repeat the previous frame's state
   (same shaders, render target, sampler/texture set). Key a cache on the PM4 state-register hash and
   reuse the resolved pipeline + descriptor sets + vertex bindings, skipping translation. Target CP
   19 -> ~10-12 ms.
3. **Cheaper shared-memory validation.** 30 MB of texture ranges per frame are re-validated. Keep
   per-page version counters (already have write-watch / volatile pages) and skip ranges whose pages did
   not change since the texture was last loaded.
4. **Direct render-target -> texture for resolves.** When a resolve's destination is only sampled
   (no CPU read, no memexport), copy RT -> texture directly and skip the EDRAM dump + shared-memory
   round trip. Earlier estimate: saves <= 3-5 ms GPU plus CP time.
5. **Skip draws that write nothing** (~48 per frame: no color/depth writes, stencil-only no-ops) and
   merge the ~175 tiny (<= 6 vertex) draws where state allows.

Expected after tier 1 (estimate, to be measured): GPU 28 -> ~20 ms, CP 19 -> ~11 ms in the garage.
That would make a **locked 40 fps on a 120 Hz screen** realistic at native resolution, and a near-60
at 75 % + FSR.

### Tier 2 - game-aware, light reverse engineering (est. 2-3 sessions)

1. **Real level-of-detail / draw distance.** Find the engine's visibility/LOD pass (SetViewerLODDist =
   sub_821C9EE8 per object; far-clip scaling did NOT cull) and expose a "detail" option that drops
   distant small objects. Cuts draws directly - the main cost driver.
2. **Shadow map updates every other frame** (owner-approved). Measured only ~1.3 ms in the garage, so
   low priority.
3. **Batching of the engine's static geometry** if it emits many draws with identical state.

### Tier 3 - a native renderer for Project 8 (Skate 3 style; est. 10-20+ sessions, high uncertainty)

Hook the game at the D3D API level instead of the GPU command level. The Xbox D3D library is
statically linked in the binary (e.g. the fence wait sub_823B58A0 and device setup sub_82360410 are
already identified), so its entry points - SetRenderTarget, SetTexture, SetVertexDeclaration,
DrawIndexedVertices, Resolve, Present - can be replaced like a DXVK-style D3D9 -> Vulkan translator:

- The game's draws go straight to Vulkan with native vertex formats, real render targets (no EDRAM
  emulation) and native copies for resolves. PM4 encoding by the game and PM4 decoding by the command
  processor both disappear.
- Xenos shader microcode still needs translation, but per shader pair ahead of time and cached, with
  the state known at the API level.
- Hard parts: the XDK's static/precompiled command buffers (if the engine records PM4 buffers once and
  replays them, those still need the emulator path), predicated tiling, memexport, GPU-written memory
  the game reads back, movies (Bink) and the UI. A hybrid is likely: native path for the 3D scene, the
  existing emulator path for everything else.
- Expected: CP cost mostly gone (19 -> ~3-5 ms), GPU maybe 28 -> 15-20 ms at native resolution. A real
  locked 60 at native resolution would still be borderline in the densest views; with 75 % + FSR it
  becomes plausible.

## 3. Recommendation

Do tier 1 first, in this order: (1) draw-state replay cache (CPU), (2) native vertex fetch (GPU),
(3) direct resolves, (4) validation and no-op draws. Each is independently testable with accept30 and
`live_ab.ps1`, keeps the picture identical, and also lowers heat at 30 fps. Decide on tier 3 only after
tier 1 shows how much per-draw cost is left - tier 3 is a large, open-ended project whose gain is partly
the same per-draw overhead tier 1 attacks.

To get 40/45 fps smooth on screen, the Thor needs 120 Hz enabled in its display settings (owner-only
system setting): 40 fps = 3 refreshes per frame. 45 fps has no even cadence at 60 or 120 Hz.
