@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "MODEL=llama3.1:8b"
set "COUNT=10"
set "POST_TITLE=Finish this story in the comments"
set "TOPIC=weird everyday stories"
set "PLATFORM=reddit"
set "TTS=kokoro"
set "VOICE=af_heart"
set "MAKE_VIDEO=N"
set "VIDEO_FLAGS="
set "PIPER_CMD=%~dp0piper\piper.exe"
set "KOKORO_PYTHON=%~dp0.venv-kokoro\Scripts\python.exe"
set "PYTHON_CMD=python"
set "TTS_CMD="
set "THREADGENS_KOKORO_VERBOSE=0"
set "PYTHONWARNINGS=ignore"
set "HF_HUB_DISABLE_PROGRESS_BARS=1"
set "TOKENIZERS_PARALLELISM=false"

echo.
echo ThreadGens local AI runner
echo Branch: feature/x-platform
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
echo Choose TTS engine:
echo 1. Kokoro - recommended/default
echo 2. Piper  - fallback
echo.
set /p "TTS_CHOICE=Choice [1/2, default 1]: "
if "%TTS_CHOICE%"=="2" set "TTS=piper"
if /I "%TTS_CHOICE%"=="piper" set "TTS=piper"
if /I "%TTS_CHOICE%"=="kokoro" set "TTS=kokoro"
if "%TTS_CHOICE%"=="" set "TTS=kokoro"

if /I "%TTS%"=="piper" goto piper_setup
goto kokoro_setup

:piper_setup
set "TTS=piper"
set "VOICE=en_US-lessac-medium"
set "TTS_CMD=%PIPER_CMD%"
if not exist "%PIPER_CMD%" set "TTS_CMD=piper"
echo Piper command: %TTS_CMD%
echo.
set /p "VOICE=Voice name or ONNX path [en_US-lessac-medium]: "
if "%VOICE%"=="" set "VOICE=en_US-lessac-medium"
goto after_tts_setup

:kokoro_setup
set "TTS=kokoro"
set "VOICE=af_heart"
if exist "%KOKORO_PYTHON%" (
  set "TTS_CMD=%KOKORO_PYTHON%"
) else (
  echo Kokoro venv was not found: %KOKORO_PYTHON%
  echo Run setup_windows.bat first, or choose system Python now.
  set /p "USE_SYSTEM_PYTHON=Use system Python anyway? y/N: "
  if /I not "%USE_SYSTEM_PYTHON%"=="Y" (
    pause
    exit /b 1
  )
  set "TTS_CMD=%PYTHON_CMD%"
)
echo Kokoro Python: %TTS_CMD%
echo Common Kokoro voices: af_heart af_bella af_nicole am_adam am_michael bf_emma bm_george
echo.
set /p "VOICE=Kokoro voice [af_heart]: "
if "%VOICE%"=="" set "VOICE=af_heart"
goto after_tts_setup

:after_tts_setup
echo.
echo Building Java files...
javac -d out src\redditTxtToImg\*.java
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)

echo.
set /p "POST_TITLE=Prompt/title for AI replies [Finish this story in the comments]: "
if "%POST_TITLE%"=="" set "POST_TITLE=Finish this story in the comments"

echo.
set /p "TOPIC=Original post/body [weird everyday stories]: "
if "%TOPIC%"=="" set "TOPIC=weird everyday stories"

echo.
set /p "COUNT=How many total slides/posts [10]: "
if "%COUNT%"=="" set "COUNT=10"

echo.
set /p "MAKE_VIDEO=Make stitched MP4 video with smooth transitions? y/N: "
if /I "%MAKE_VIDEO%"=="Y" set "VIDEO_FLAGS=--video --concat-video"

echo.
echo Platform:   %PLATFORM%
echo Post title: %POST_TITLE%
echo Original:   %TOPIC%
echo Count:      %COUNT%
echo TTS:        %TTS%
echo Voice:      %VOICE%
echo Cmd:        %TTS_CMD%
echo Video:      %VIDEO_FLAGS%
echo.

java -cp out redditTxtToImg.CheckedRunner --platform %PLATFORM% --auto --post-title "%POST_TITLE%" --topic "%TOPIC%" --count %COUNT% --llm-model %MODEL% --tts %TTS% --tts-command "%TTS_CMD%" --voice "%VOICE%" --no-watermark --top %VIDEO_FLAGS%
if errorlevel 1 (
  echo.
  echo Generation failed. Check the error above.
  pause
  exit /b 1
)

echo.
echo Done.
echo Text:        output\script\generated_comments.txt
echo Images:      output\
echo Audio:       output\audio\
echo Clips:       output\video\
echo Final video: output\video\final.mp4
echo.
pause
