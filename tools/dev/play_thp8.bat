@echo off
rem Tony Hawk's Project 8 (Project8Recomp) - M1 test launcher.
rem Same flags the official launcher passes by default (game data, keyboard,
rem GPU plugin, "Performance" preset), plus a per-frame timing CSV.
rem Frame cap stays at the game default (30 fps).
cd /d "%~dp0"
if not exist logs mkdir logs
if not exist perf mkdir perf
if not exist saves mkdir saves
if not exist cache mkdir cache
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set STAMP=%%t
thps_p8.exe --game_data_root=game --user_data_root=saves --cache_root=cache --mnk_mode=true --gpu_plugin=xenos ^
 --fullscreen=false --window_width=1280 --window_height=720 --video_mode_width=1280 --video_mode_height=720 ^
 --gpu_cp_fastpath=true --primitive_processor_cache_min_indices=4096 ^
 --gpu_native_residency=true --gpu_native_prefetch=true --gpu_native_index=true --gpu_native_vertex=true ^
 --gpu_sampler_set_reuse=true --gpu_texture_request_reuse=true --gpu_texture_last_view_cache=true ^
 --gpu_texture_descriptor_set_adjacent_reuse=true --timer_queue_blocking_wait=true ^
 --guest_ring_wait_backoff=true --guest_ring_wait_event=true --guest_swap_wait=true ^
 --guest_u8x4_unpack_native=true --guest_vertex_unpack_native=true --guest_vertex_unpack_simd=true ^
 --perf_log_csv=perf\frames-%STAMP%.csv --log_file=logs\run-%STAMP%.log --log_level=info %*
echo.
echo Game exited with code %ERRORLEVEL%. Logs: logs\run-%STAMP%.log  Frame times: perf\frames-%STAMP%.csv
pause
