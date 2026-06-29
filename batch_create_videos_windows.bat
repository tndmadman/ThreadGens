@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "INPUT_FILE=data\batch_videos.txt"
set "COUNT=10"
set "MODEL=llama3.1:8b"
set "VOICE=af_heart"
set "KEEP_OLLAMA=N"
set "KEEP_OLLAMA_FLAG="
set "THREADGENS_KOKORO_VERBOSE=0"
set "PYTHONWARNINGS=ignore"
set "HF_HUB_DISABLE_PROGRESS_BARS=1"
set "TOKENIZERS_PARALLELISM=false"

if not "%~1"=="" set "INPUT_FILE=%~1"
if not "%~2"=="" set "COUNT=%~2"

echo.
echo ThreadGens batch video creator
echo.
echo Input format:
echo   line 1 = title
echo   line 2 = body text
echo   line 3 = next title
echo   line 4 = next body text
echo.
echo Defaults copied from run_ai_windows.bat:
echo   model: %MODEL%
echo   count: %COUNT%
echo   tts:   kokoro
echo   voice: %VOICE%
echo   video: stitched MP4, watermark off, body text top-aligned
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
echo.

if not exist "tools\batch_create_videos.ps1" (
  echo Missing tools\batch_create_videos.ps1
  pause
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\batch_create_videos.ps1" -InputFile "%INPUT_FILE%" -Count %COUNT% -Model "%MODEL%" -Voice "%VOICE%" %KEEP_OLLAMA_FLAG%
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
