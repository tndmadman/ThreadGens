@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "TARGET_VIDEOS=30"
set "COUNT=10"
set "MODEL=llama3.1:8b"
set "VOICE=af_heart"
set "VOICE_SERIES=af_heart,af_bella,af_nicole,am_adam,am_michael,bf_emma,bm_george"
set "PLATFORM=reddit"
set "KEEP_OLLAMA_FLAG=-KeepOllamaLoaded"
set "OP_IMAGE_FLAG="
set "THREADGENS_KOKORO_VERBOSE=0"
set "PYTHONWARNINGS=ignore"
set "HF_HUB_DISABLE_PROGRESS_BARS=1"
set "TOKENIZERS_PARALLELISM=false"

if /I "%~1"=="--self-test" (
  call :EnsureBatchRunner
  if errorlevel 1 exit /b 1
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\batch_create_videos.ps1" -SelfTest
  if errorlevel 1 exit /b 1
  echo Batch launcher self-test passed.
  exit /b 0
)

if not "%~1"=="" set "TARGET_VIDEOS=%~1"
if not "%~2"=="" set "COUNT=%~2"

call :EnsureBatchRunner
if errorlevel 1 (
  echo.
  echo Batch launcher cannot continue until tools\batch_create_videos.ps1 is current.
  pause
  exit /b 1
)

echo.
echo ThreadGens self-filling batch video creator
echo.
echo This mode does not load title/body prompts from batch_videos.txt.
echo It generates fresh ideas with local Ollama and keeps replacing rejected attempts
echo until the requested number of approved final videos has been created.
echo.
echo Usage:
echo   batch_create_videos_windows.bat [approved-video-target] [slides-per-video]
echo Example:
echo   batch_create_videos_windows.bat 30 10
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
echo   model:                  %MODEL%
echo   tts:                    kokoro
echo   voices:                 %VOICE_SERIES% ^(one stable selection per video^)
echo   ideas:                   generated automatically and persisted to data\batch_idea_history.jsonl
echo   retry behavior:          rejected ideas are replaced until approved target is reached
echo   text:                    narration-timed reveal inside the rendered social image
echo   captions:                bottom duplicate subtitles disabled
echo   visible counters:        disabled ^(no conversation 1/10, ranks, or message numbers^)
echo   final filenames:         title-based with no numeric prefix
echo   background:              rotates through dark color palettes from video to video
echo   video motion:            locked/static social frame ^(no pan/zoom/crop drift^)
echo   final texture:           subtle full-frame temporal grain on completed MP4
echo   metadata:                AI disclosure and provenance sidecars
if "%OP_IMAGE_FLAG%"=="" (
  echo   OP image:               disabled
) else (
  echo   OP image:               ComfyUI RealVisXL enabled for each OP post
)
echo   kokoro console:           quiet
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

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\batch_create_videos.ps1" -TargetVideos %TARGET_VIDEOS% -Count %COUNT% -Model "%MODEL%" -Voice "%VOICE%" -VoiceSeries "%VOICE_SERIES%" -Platform "%PLATFORM%" -Captions off %KEEP_OLLAMA_FLAG% %OP_IMAGE_FLAG%
set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo Batch approved-video target reached.
) else if "%EXITCODE%"=="2" (
  echo Batch stopped before the approved-video target was reached. See the summary above.
) else (
  echo Batch video creation stopped with exit code %EXITCODE%.
)
echo.
pause
exit /b %EXITCODE%

:EnsureBatchRunner
set "PS_SCRIPT=tools\batch_create_videos.ps1"
set "SELF_FILL_MARKER=Self-filling mode: generate ideas until approved target is reached"

if not exist "%PS_SCRIPT%" (
  echo Missing %PS_SCRIPT%
  exit /b 1
)

findstr /C:"%SELF_FILL_MARKER%" "%PS_SCRIPT%" >nul 2>&1
if not errorlevel 1 exit /b 0

echo.
echo Detected an outdated local %PS_SCRIPT%.
echo Attempting to refresh only that launcher script from origin/main...

where git >nul 2>&1
if errorlevel 1 (
  echo Git was not found, so the stale PowerShell runner cannot be repaired automatically.
  exit /b 1
)

if not exist ".git" (
  echo This folder is not a Git checkout, so the stale PowerShell runner cannot be repaired automatically.
  exit /b 1
)

git diff --quiet -- "%PS_SCRIPT%"
if errorlevel 1 (
  echo Local edits exist in %PS_SCRIPT%.
  echo Refusing to overwrite them automatically. Commit/stash those edits or restore the file from origin/main.
  exit /b 1
)

git fetch origin main --quiet
if errorlevel 1 (
  echo Could not fetch origin/main.
  exit /b 1
)

git rev-parse --verify origin/main >nul 2>&1
if errorlevel 1 (
  echo origin/main could not be resolved after fetch.
  exit /b 1
)

git restore --source origin/main --worktree -- "%PS_SCRIPT%"
if errorlevel 1 (
  echo Failed to refresh %PS_SCRIPT% from origin/main.
  exit /b 1
)

findstr /C:"%SELF_FILL_MARKER%" "%PS_SCRIPT%" >nul 2>&1
if errorlevel 1 (
  echo The refreshed PowerShell runner is still missing the required self-filling approved-target behavior.
  exit /b 1
)

echo Refreshed %PS_SCRIPT% successfully.
exit /b 0
