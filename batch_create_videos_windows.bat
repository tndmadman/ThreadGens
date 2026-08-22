@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "INPUT_FILE=data\batch_videos.txt"
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
  echo Batch launcher self-test passed.
  exit /b 0
)

if not "%~1"=="" set "INPUT_FILE=%~1"
if not "%~2"=="" set "COUNT=%~2"

call :EnsureBatchRunner
if errorlevel 1 (
  echo.
  echo Batch launcher cannot continue until tools\batch_create_videos.ps1 is current.
  pause
  exit /b 1
)

echo.
echo ThreadGens batch video creator
echo.
echo Input format uses 2 non-empty lines per video:
echo   Reddit: line 1 = post title, line 2 = post body
echo   X:      line 1 = hidden reply style, line 2 = visible X post text
echo   Repeat those 2 lines for each next video.
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
set /p "UNLOAD_OLLAMA=Unload Ollama after each video? y/N [default N, keeps model loaded]: "
if /I "%UNLOAD_OLLAMA%"=="Y" set "KEEP_OLLAMA_FLAG="
if /I "%UNLOAD_OLLAMA%"=="YES" set "KEEP_OLLAMA_FLAG="

echo Defaults copied from run_ai_windows.bat:
echo   platform: %PLATFORM%
echo   model:    %MODEL%
echo   count:    %COUNT%
echo   tts:      kokoro
echo   voices:   %VOICE_SERIES% ^(one stable selection per video^)
echo   captions: word-timed
echo   metadata: AI disclosure and provenance sidecars
echo   video:    stitched MP4, watermark off, body text top-aligned
if "%OP_IMAGE_FLAG%"=="" (
  echo   OP image: disabled
) else (
  echo   OP image: ComfyUI RealVisXL enabled for each OP post
)
echo   kokoro console: quiet
if "%KEEP_OLLAMA_FLAG%"=="" (
  echo   Ollama: unload after each video
) else (
  echo   Ollama: keep loaded between videos
)
echo   batch failures: continue to later jobs; failed/rejected jobs are summarized at the end
echo.
if not "%OP_IMAGE_FLAG%"=="" (
  echo OP images: enabled. Make sure ComfyUI is already running at http://127.0.0.1:8188 and RealVisXL_V5.0_fp32.safetensors is installed.
)
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\batch_create_videos.ps1" -InputFile "%INPUT_FILE%" -Count %COUNT% -Model "%MODEL%" -Voice "%VOICE%" -VoiceSeries "%VOICE_SERIES%" -Platform "%PLATFORM%" %KEEP_OLLAMA_FLAG% %OP_IMAGE_FLAG%
set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo Batch video creation finished.
) else if "%EXITCODE%"=="2" (
  echo Batch video creation finished with one or more failed/rejected jobs. See the summary and failed_jobs.txt above.
) else (
  echo Batch video creation stopped with exit code %EXITCODE%.
)
echo.
pause
exit /b %EXITCODE%

:EnsureBatchRunner
set "PS_SCRIPT=tools\batch_create_videos.ps1"
set "CONTINUE_MARKER=Continuing to the next batch job."

if not exist "%PS_SCRIPT%" (
  echo Missing %PS_SCRIPT%
  exit /b 1
)

findstr /C:"%CONTINUE_MARKER%" "%PS_SCRIPT%" >nul 2>&1
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

findstr /C:"%CONTINUE_MARKER%" "%PS_SCRIPT%" >nul 2>&1
if errorlevel 1 (
  echo The refreshed PowerShell runner is still missing the required batch-continuation behavior.
  exit /b 1
)

echo Refreshed %PS_SCRIPT% successfully.
exit /b 0
