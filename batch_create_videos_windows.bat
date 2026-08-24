@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "TARGET_VIDEOS=30"
set "COUNT=10"
set "WORKERS=4"
set "MODEL=llama3.1:8b"
set "VOICE=af_heart"
set "VOICE_SERIES=af_heart,af_bella,af_nicole,bf_emma"
set "PLATFORM=reddit"
set "KEEP_OLLAMA_FLAG=-KeepOllamaLoaded"
set "OP_IMAGE_FLAG="
set "THREADGENS_VIDEO_ENCODER=auto"
set "THREADGENS_KOKORO_VERBOSE=0"
set "THREADGENS_REQUIRE_EXACT_KOKORO_TIMING=1"
set "THREADGENS_REQUIRE_SMOOTH_REVEAL=1"
set "PYTHONWARNINGS=ignore"
set "HF_HUB_DISABLE_PROGRESS_BARS=1"
set "TOKENIZERS_PARALLELISM=false"

if /I "%~1"=="--self-test" (
  call :EnsureBatchRunner
  if errorlevel 1 exit /b 1
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\batch_create_videos_color_dashboard.ps1" -SelfTest
  if errorlevel 1 exit /b 1
  echo Shutdown-safe color live dashboard batch launcher self-test passed.
  exit /b 0
)

if not "%~1"=="" set "TARGET_VIDEOS=%~1"
if not "%~2"=="" set "COUNT=%~2"
if not "%~3"=="" set "WORKERS=%~3"

call :EnsureBatchRunner
if errorlevel 1 (
  echo.
  echo Batch launcher cannot continue until the parallel batch runner is current.
  pause
  exit /b 1
)

echo.
echo ThreadGens parallel self-filling batch video creator
echo.
echo This mode generates fresh ideas with local Ollama and keeps replacing rejected
echo attempts until the requested number of approved final videos has been created.
echo Ollama stays serialized while complete video workers render in parallel.
echo.
echo Usage:
echo   batch_create_videos_windows.bat [approved-video-target] [slides-per-video] [workers]
echo Example:
echo   batch_create_videos_windows.bat 30 10 4
echo.
echo Worker guidance:
echo   3-4 workers: recommended starting point for a high-core workstation
echo   5 workers:   aggressive; benchmark before going higher
echo   6-10:        supported for testing but may oversubscribe CPU/RAM/FFmpeg

echo.
set "WORKER_CHOICE="
set /p "WORKER_CHOICE=Parallel video workers [1-10, default %WORKERS%]: "
if not "%WORKER_CHOICE%"=="" set "WORKERS=%WORKER_CHOICE%"

echo.
echo Choose video encoder:
echo 1. Auto - use NVIDIA NVENC when a real GPU encode test succeeds, otherwise CPU x264
echo 2. NVIDIA GPU - require h264_nvenc; fail instead of falling back
echo 3. CPU - force libx264
echo.
set "VIDEO_ENCODER_CHOICE="
set /p "VIDEO_ENCODER_CHOICE=Choice [1/2/3, default 1]: "
if "%VIDEO_ENCODER_CHOICE%"=="2" set "THREADGENS_VIDEO_ENCODER=nvenc"
if "%VIDEO_ENCODER_CHOICE%"=="3" set "THREADGENS_VIDEO_ENCODER=x264"
if /I "%VIDEO_ENCODER_CHOICE%"=="nvenc" set "THREADGENS_VIDEO_ENCODER=nvenc"
if /I "%VIDEO_ENCODER_CHOICE%"=="gpu" set "THREADGENS_VIDEO_ENCODER=nvenc"
if /I "%VIDEO_ENCODER_CHOICE%"=="x264" set "THREADGENS_VIDEO_ENCODER=x264"
if /I "%VIDEO_ENCODER_CHOICE%"=="cpu" set "THREADGENS_VIDEO_ENCODER=x264"
if /I "%VIDEO_ENCODER_CHOICE%"=="auto" set "THREADGENS_VIDEO_ENCODER=auto"

echo.
echo Choose platform/thread style:
echo 1. Reddit thread
echo 2. X post and replies
echo.
set /p "PLATFORM_CHOICE=Choice [1/2, default 1]: "
if "%PLATFORM_CHOICE%"=="2" set "PLATFORM=x"
if /I "%PLATFORM_CHOICE%"=="x" set "PLATFORM=x"
if /I "%PLATFORM_CHOICE%"=="reddit" set "PLATFORM=reddit"
if "%PLATFORM_CHOICE%"=="" set "PLATFORM=reddit"

echo.
set /p "MAKE_OP_IMAGE=Generate an OP image for each %PLATFORM% video with local ComfyUI RealVisXL? y/N: "
if /I "%MAKE_OP_IMAGE%"=="Y" set "OP_IMAGE_FLAG=-GenerateOpImage"
if /I "%MAKE_OP_IMAGE%"=="YES" set "OP_IMAGE_FLAG=-GenerateOpImage"

echo.
set /p "UNLOAD_OLLAMA=Unload Ollama between generation calls? y/N [default N, keeps model loaded]: "
if /I "%UNLOAD_OLLAMA%"=="Y" set "KEEP_OLLAMA_FLAG="
if /I "%UNLOAD_OLLAMA%"=="YES" set "KEEP_OLLAMA_FLAG="

echo.
echo Starting color live batch dashboard...
echo   target:   %TARGET_VIDEOS% approved videos
echo   slides:   %COUNT% per video
echo   workers:  %WORKERS%
echo   encoder:  %THREADGENS_VIDEO_ENCODER%
echo   platform: %PLATFORM%
echo   voices:   %VOICE_SERIES% (one high-end voice per video)
echo.
echo Detailed engine, Ollama, TTS, FFmpeg, P0/P1/P2, and worker output is saved to debug.log.
echo The live screen will show color-coded progress, worker stages, slots, events, and system resources.
echo Press Q or Esc to stop cleanly. Ctrl+C also terminates the entire ThreadGens process tree.
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\batch_create_videos_color_dashboard.ps1" -TargetVideos %TARGET_VIDEOS% -Count %COUNT% -Workers %WORKERS% -Model "%MODEL%" -Voice "%VOICE%" -VoiceSeries "%VOICE_SERIES%" -VoiceSelection series -Platform "%PLATFORM%" -Captions off %KEEP_OLLAMA_FLAG% %OP_IMAGE_FLAG%
set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo Parallel batch approved-video target reached.
) else if "%EXITCODE%"=="130" (
  echo Batch stopped by user. All ThreadGens child processes were terminated.
) else if "%EXITCODE%"=="2" (
  echo Batch stopped before the approved-video target was reached. See debug.log for full detail.
) else (
  echo Batch video creation stopped with exit code %EXITCODE%. See debug.log for full detail.
)
echo.
pause
exit /b %EXITCODE%

:EnsureBatchRunner
set "COLOR_DASHBOARD_SCRIPT=tools\batch_create_videos_color_dashboard.ps1"
set "DASHBOARD_SCRIPT=tools\batch_create_videos_dashboard.ps1"
set "PARALLEL_SCRIPT=tools\batch_create_videos_parallel.ps1"
set "FORMAT_ROTATION_SCRIPT=tools\batch_format_rotation.ps1"
set "WORKER_SCRIPT=tools\batch_parallel_worker.ps1"
set "PROXY_SCRIPT=tools\ollama_serial_proxy.py"
set "COLOR_DASHBOARD_MARKER=ThreadGensKillOnCloseJob"
set "DASHBOARD_MARKER=ThreadGens LIVE BATCH MONITOR"
set "PARALLEL_MARKER=Parallel video workers:"

set "NEEDS_REFRESH=0"
if not exist "%COLOR_DASHBOARD_SCRIPT%" set "NEEDS_REFRESH=1"
if not exist "%DASHBOARD_SCRIPT%" set "NEEDS_REFRESH=1"
if not exist "%PARALLEL_SCRIPT%" set "NEEDS_REFRESH=1"
if not exist "%FORMAT_ROTATION_SCRIPT%" set "NEEDS_REFRESH=1"
if not exist "%WORKER_SCRIPT%" set "NEEDS_REFRESH=1"
if not exist "%PROXY_SCRIPT%" set "NEEDS_REFRESH=1"
if exist "%COLOR_DASHBOARD_SCRIPT%" (
  findstr /C:"%COLOR_DASHBOARD_MARKER%" "%COLOR_DASHBOARD_SCRIPT%" >nul 2>&1
  if errorlevel 1 set "NEEDS_REFRESH=1"
)
if exist "%DASHBOARD_SCRIPT%" (
  findstr /C:"%DASHBOARD_MARKER%" "%DASHBOARD_SCRIPT%" >nul 2>&1
  if errorlevel 1 set "NEEDS_REFRESH=1"
)
if exist "%PARALLEL_SCRIPT%" (
  findstr /C:"%PARALLEL_MARKER%" "%PARALLEL_SCRIPT%" >nul 2>&1
  if errorlevel 1 set "NEEDS_REFRESH=1"
)
if "%NEEDS_REFRESH%"=="0" exit /b 0

echo.
echo Detected missing/outdated color dashboard or parallel batch runner files.
echo Attempting to refresh only the batch launcher files from origin/main...

where git >nul 2>&1
if errorlevel 1 (
  echo Git was not found, so the batch runner cannot be repaired automatically.
  exit /b 1
)
if not exist ".git" (
  echo This folder is not a Git checkout, so the batch runner cannot be repaired automatically.
  exit /b 1
)

for %%F in ("%COLOR_DASHBOARD_SCRIPT%" "%DASHBOARD_SCRIPT%" "%PARALLEL_SCRIPT%" "%FORMAT_ROTATION_SCRIPT%" "%WORKER_SCRIPT%" "%PROXY_SCRIPT%") do (
  git diff --quiet -- "%%~F"
  if errorlevel 1 (
    echo Local edits exist in %%~F.
    echo Refusing to overwrite them automatically. Commit/stash those edits or restore the file from origin/main.
    exit /b 1
  )
)

git fetch origin main --quiet
if errorlevel 1 (
  echo Could not fetch origin/main.
  exit /b 1
)

git restore --source origin/main --worktree -- "%COLOR_DASHBOARD_SCRIPT%" "%DASHBOARD_SCRIPT%" "%PARALLEL_SCRIPT%" "%FORMAT_ROTATION_SCRIPT%" "%WORKER_SCRIPT%" "%PROXY_SCRIPT%"
if errorlevel 1 (
  echo Failed to refresh the color dashboard / parallel runner files from origin/main.
  exit /b 1
)

if not exist "%COLOR_DASHBOARD_SCRIPT%" exit /b 1
if not exist "%DASHBOARD_SCRIPT%" exit /b 1
if not exist "%PARALLEL_SCRIPT%" exit /b 1
if not exist "%FORMAT_ROTATION_SCRIPT%" exit /b 1
if not exist "%WORKER_SCRIPT%" exit /b 1
if not exist "%PROXY_SCRIPT%" exit /b 1
findstr /C:"%COLOR_DASHBOARD_MARKER%" "%COLOR_DASHBOARD_SCRIPT%" >nul 2>&1
if errorlevel 1 (
  echo The refreshed color dashboard is still missing its process-tree shutdown guard.
  exit /b 1
)
findstr /C:"%DASHBOARD_MARKER%" "%DASHBOARD_SCRIPT%" >nul 2>&1
if errorlevel 1 (
  echo The refreshed dashboard is still missing the required live monitor behavior.
  exit /b 1
)
findstr /C:"%PARALLEL_MARKER%" "%PARALLEL_SCRIPT%" >nul 2>&1
if errorlevel 1 (
  echo The refreshed parallel runner is still missing the required worker-pool behavior.
  exit /b 1
)

echo Refreshed color dashboard and parallel batch runner successfully.
exit /b 0
