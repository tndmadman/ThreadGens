@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "TARGET_VIDEOS=30"
set "COUNT=10"
set "WORKERS=4"
set "MODEL=llama3.1:8b"
set "VOICE=af_heart"
set "VOICE_SERIES=af_heart"
set "PLATFORM=reddit"
set "KEEP_OLLAMA_FLAG=-KeepOllamaLoaded"
set "OP_IMAGE_FLAG="
set "THREADGENS_KOKORO_VERBOSE=0"
set "THREADGENS_REQUIRE_EXACT_KOKORO_TIMING=1"
set "THREADGENS_REQUIRE_SMOOTH_REVEAL=1"
set "PYTHONWARNINGS=ignore"
set "HF_HUB_DISABLE_PROGRESS_BARS=1"
set "TOKENIZERS_PARALLELISM=false"

if /I "%~1"=="--self-test" (
  call :EnsureBatchRunner
  if errorlevel 1 exit /b 1
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\batch_create_videos_parallel.ps1" -SelfTest
  if errorlevel 1 exit /b 1
  echo Parallel batch launcher self-test passed.
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
echo This mode does not load title/body prompts from batch_videos.txt.
echo It generates fresh ideas with local Ollama and keeps replacing rejected attempts
echo until the requested number of approved final videos has been created.
echo Multiple complete video pipelines render at once, while every Ollama request
echo is serialized through one local request gate so generation order stays safe.
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

echo Defaults:
echo   platform:               %PLATFORM%
echo   approved video target:  %TARGET_VIDEOS%
echo   slides per video:       %COUNT%
echo   parallel workers:       %WORKERS%
echo   Ollama concurrency:     1 serialized request at a time
echo   model:                  %MODEL%
echo   tts:                    Kokoro neural TTS only
echo   voice:                  %VOICE% ^(locked; no Piper/voice-pool switching^)
echo   ideas:                  generated automatically and persisted to data\batch_idea_history.jsonl
echo   retry behavior:         rejected ideas/workers are replaced until approved target is reached
echo   text:                   exact Kokoro-timed smooth reveal required for every social clip
echo   captions:               bottom duplicate subtitles disabled
echo   visible counters:       disabled ^(no conversation 1/10, ranks, or message numbers^)
echo   final filenames:        title-based with no numeric prefix
echo   background:             rotates through dark color palettes from video to video
echo   video motion:           locked/static social frame ^(no pan/zoom/crop drift^)
echo   final texture:          subtle full-frame temporal grain on completed MP4
echo   metadata:               AI disclosure and provenance sidecars
if "%OP_IMAGE_FLAG%"=="" (
  echo   OP image:               disabled
) else (
  echo   OP image:               ComfyUI RealVisXL enabled; workers will be clamped to 1 for GPU safety
)
echo   kokoro console:          quiet
if "%KEEP_OLLAMA_FLAG%"=="" (
  echo   Ollama:                 unload between calls
) else (
  echo   Ollama:                 keep loaded between ideas/videos
)
echo.
if not "%OP_IMAGE_FLAG%"=="" (
  echo OP images: enabled. Make sure ComfyUI is already running at http://127.0.0.1:8188 and RealVisXL_V5.0_fp32.safetensors is installed.
)
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\batch_create_videos_parallel.ps1" -TargetVideos %TARGET_VIDEOS% -Count %COUNT% -Workers %WORKERS% -Model "%MODEL%" -Voice "%VOICE%" -VoiceSeries "%VOICE_SERIES%" -VoiceSelection single -Platform "%PLATFORM%" -Captions off %KEEP_OLLAMA_FLAG% %OP_IMAGE_FLAG%
set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo Parallel batch approved-video target reached.
) else if "%EXITCODE%"=="2" (
  echo Batch stopped before the approved-video target was reached. See the summary above.
) else (
  echo Batch video creation stopped with exit code %EXITCODE%.
)
echo.
pause
exit /b %EXITCODE%

:EnsureBatchRunner
set "PARALLEL_SCRIPT=tools\batch_create_videos_parallel.ps1"
set "WORKER_SCRIPT=tools\batch_parallel_worker.ps1"
set "PROXY_SCRIPT=tools\ollama_serial_proxy.py"
set "PARALLEL_MARKER=Parallel video workers:"

set "NEEDS_REFRESH=0"
if not exist "%PARALLEL_SCRIPT%" set "NEEDS_REFRESH=1"
if not exist "%WORKER_SCRIPT%" set "NEEDS_REFRESH=1"
if not exist "%PROXY_SCRIPT%" set "NEEDS_REFRESH=1"
if exist "%PARALLEL_SCRIPT%" (
  findstr /C:"%PARALLEL_MARKER%" "%PARALLEL_SCRIPT%" >nul 2>&1
  if errorlevel 1 set "NEEDS_REFRESH=1"
)
if "%NEEDS_REFRESH%"=="0" exit /b 0

echo.
echo Detected missing/outdated parallel batch runner files.
echo Attempting to refresh only the parallel launcher files from origin/main...

where git >nul 2>&1
if errorlevel 1 (
  echo Git was not found, so the parallel runner cannot be repaired automatically.
  exit /b 1
)
if not exist ".git" (
  echo This folder is not a Git checkout, so the parallel runner cannot be repaired automatically.
  exit /b 1
)

for %%F in ("%PARALLEL_SCRIPT%" "%WORKER_SCRIPT%" "%PROXY_SCRIPT%") do (
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

git restore --source origin/main --worktree -- "%PARALLEL_SCRIPT%" "%WORKER_SCRIPT%" "%PROXY_SCRIPT%"
if errorlevel 1 (
  echo Failed to refresh the parallel runner files from origin/main.
  exit /b 1
)

if not exist "%PARALLEL_SCRIPT%" exit /b 1
if not exist "%WORKER_SCRIPT%" exit /b 1
if not exist "%PROXY_SCRIPT%" exit /b 1
findstr /C:"%PARALLEL_MARKER%" "%PARALLEL_SCRIPT%" >nul 2>&1
if errorlevel 1 (
  echo The refreshed parallel runner is still missing the required worker-pool behavior.
  exit /b 1
)

echo Refreshed parallel batch runner successfully.
exit /b 0
