@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "INPUT_FILE=data\batch_videos.txt"
set "COUNT=10"
set "MODEL=llama3.1:8b"
set "VOICE=af_heart"
set "PLATFORM=reddit"
set "KEEP_OLLAMA=N"
set "KEEP_OLLAMA_FLAG="
set "OP_IMAGE_FLAG="
set "THREADGENS_KOKORO_VERBOSE=0"
set "PYTHONWARNINGS=ignore"
set "HF_HUB_DISABLE_PROGRESS_BARS=1"
set "TOKENIZERS_PARALLELISM=false"

if not "%~1"=="" set "INPUT_FILE=%~1"
if not "%~2"=="" set "COUNT=%~2"

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

echo Defaults copied from run_ai_windows.bat:
echo   platform: %PLATFORM%
echo   model:    %MODEL%
echo   count:    %COUNT%
echo   tts:      kokoro
echo   voice:    %VOICE%
echo   video:    stitched MP4, watermark off, body text top-aligned
if "%OP_IMAGE_FLAG%"=="" (
  echo   OP image: disabled
) else (
  echo   OP image: ComfyUI RealVisXL enabled for each OP post
)
echo   kokoro console: quiet
echo.
set /p "KEEP_OLLAMA=Keep Ollama loaded between videos? y/N: "
if /I "%KEEP_OLLAMA%"=="Y" set "KEEP_OLLAMA_FLAG=-KeepOllamaLoaded"
if /I "%KEEP_OLLAMA%"=="YES" set "KEEP_OLLAMA_FLAG=-KeepOllamaLoaded"
echo.
if "%KEEP_OLLAMA_FLAG%"=="" (
  echo Ollama unload: enabled after each script ^(default^)
) else (
  echo Ollama unload: disabled, keeping model loaded between videos
)
if not "%OP_IMAGE_FLAG%"=="" (
  echo OP images: enabled. Make sure ComfyUI is already running at http://127.0.0.1:8188 and RealVisXL_V5.0_fp32.safetensors is installed.
)
echo.

if not exist "tools\batch_create_videos.ps1" (
  echo Missing tools\batch_create_videos.ps1
  pause
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\batch_create_videos.ps1" -InputFile "%INPUT_FILE%" -Count %COUNT% -Model "%MODEL%" -Voice "%VOICE%" -Platform "%PLATFORM%" %KEEP_OLLAMA_FLAG% %OP_IMAGE_FLAG%
set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo Batch video creation finished.
) else (
  echo Batch video creation stopped with exit code %EXITCODE%.
)
echo.
pause
exit /b %EXITCODE%
