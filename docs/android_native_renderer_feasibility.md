# Feasibility: a Project 8 native renderer (Skate 3 style)

Status 2026-10-10. Probes: tools/android/probes (call census of the graphics library, render-path
skip switches, draws per shader pair). Measured on the AYN Thor, Suburbia (garage spawn and skating);
other levels not measured yet.

## Verdict: feasible - one engine function draws the whole 3D scene

- **`sub_82359E88` is the engine's per-mesh render function** and draws essentially all 3D geometry
  (world, skater, NPCs, sky). It is reached through a function pointer (render-item callback), once per
  item, ~1,500 times per frame in the garage. With it skipped (probe flag 1) only the HUD and a glow
  marker remain: draws 1,729 -> 238, **GPU 27.4 -> 10.4 ms, and the game runs at 60 fps** - the game's
  CPU work and the command processor fit in 16.7 ms once these draws are gone.
- At its entry it has everything a native renderer needs, D3D9-style (r3 = render item):
  `+20` material (applied by `sub_82356820`: shaders, textures, render states), `+36` index count,
  `+40` primitive type, `+44..+52` a per-item float3 written to the device constant shadow,
  `+56/+57` flags, `+64` vertex buffer (streams 0 and 1 via `sub_823B8E50`, stride byte `+90`),
  `+84` vertex declaration (`sub_823B3B80`), `+92` index buffer (`sub_823B9000`), draw through
  `sub_823B4710`, `+112` an alternate (probably skinned) path `sub_82359298`. The D3D device lives at
  `*(0x827F0050 + 2788)`.
- `sub_82355FF8` (a state/material helper on the same layer, ~800 calls per frame) is expensive:
  skipping it keeps the draws but saves 12 ms of GPU - the material state it sets is what makes the
  draws costly.
- **Material concentration**: a frame uses ~100-130 vertex/pixel shader pairs (114 VS / 177 PS seen in
  a session), but the top 10 pairs are ~2/3 of all draws, the top 20 ~80 %. The single most common
  pair is ~20 %; depth-only shadow casters ~5 %.

## What it would take

The plan is a hybrid, like Skate 3's: native for the 3D scene, the existing emulator for everything
else (render targets, resolves, post-processing, HUD, menus, movies).

1. **Capture and replay (milestone A, 2-3 sessions).** Hook `sub_82359E88`: record the item's draw
   (buffers, declaration, material shaders/textures/states, the device's constant shadow) and put a
   marker into the game's command stream instead of the D3D calls, so the command processor replays
   the draw at exactly the right point, into the emulator's own render targets - post-processing and
   resolves keep working unchanged. First version reuses the translated shaders with a lean state
   path (native vertex fetch from SDK 0017 already exists). Measures how much per-draw cost lean
   submission alone saves; must render identically (A/B screenshots).
2. **Cheaper shaders for the top ~20 materials (milestone B, several sessions).** The remaining GPU
   cost is the translated shaders themselves (~4 us vertex/setup per draw and most of the fragment
   time). Hand-written or optimised native versions (fp16, no Xenos emulation overhead) of the
   materials covering ~80 % of the draws; the rest stays on the translated path.
3. **Coverage (milestone C).** Other levels, skinned/alternate paths, transparency, edge cases;
   verify each level with the benchmarks.

Expected: the 3D portion (~17 ms GPU in the garage) down to ~6-8 ms -> garage ~16-19 ms, skating
views (lighter) at a real 60. The CPU side already fits 60. Biggest risks: matching the game's
materials exactly (lighting, fog, blending, alpha test), the alternate/skinned paths, and levels with
different material mixes. Total: a multi-week project; milestone A answers the GPU question early.

## Milestone A checkpoint (session 4, 2026-10-10): re-scoped

Measured before writing the capture/replay plumbing (details: PROGRESS.md, session 4):

- Of the garage's 27.4 ms of GPU time, the work *around* the draws (descriptor/pipeline binds,
  hoisted uploads, barriers) is only ~4.5 ms - the most a lean replay of the same draws could remove
  on the GPU. Draw execution was 17.4 ms: translated pixel shaders ~10.4, vertex/raster ~3, a
  hardware floor of ~2 us per draw; non-draw work (resolves) 5.5 ms.
- The translated shaders and the resolves were the big, generic levers, fixed for every level:
  implicit-LOD sampling + pipeline-time texture sign classes (SDK 0018) and fused render target ->
  shared memory resolves (0021/0022): garage **27.4 -> 18.6 ms**, picture identical. What is left
  of the pixel shader time is mostly the game's own varyings (~4.4 ms), which a native shader would
  pay too.
- The command processor (IssueDraw = 73 % of its time) spends it on guest memory validation (shared
  memory ranges 26 %, texture requests 17 %) - a game-level replay would have to solve the same
  validation problem; with the CP alone on the prime core (affinity mode 2) it is ~8 ms per frame
  while skating.
- So a native renderer (milestones A-C as planned) is no longer the shortest path to a real 60 while
  skating: that is now limited by CPU scheduling (run-queue waits when Qualcomm core_ctl parks
  cores) more than by GPU or CP work. Revisit A only if the dense views (garage) must reach 60.
