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
set "IMAGE_FLAGS="
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
echo Branch: fix/runtime-cleanup-image-ready
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
if /I "%PLATFORM%"=="x" goto x_prompts
goto reddit_prompts

:x_prompts
set "POST_TITLE="
set "TOPIC="
set /p "POST_TITLE=Optional X reply style, ex wrong answers only/advice/finish story [normal replies]: "
echo.
set /p "TOPIC=Visible original X post text [I just saw something weird and I need someone else to explain it.]: "
if "%TOPIC%"=="" set "TOPIC=I just saw something weird and I need someone else to explain it."
goto after_text_prompts

:reddit_prompts
set /p "POST_TITLE=Reddit post title [Finish this story in the comments]: "
if "%POST_TITLE%"=="" set "POST_TITLE=Finish this story in the comments"
echo.
set /p "TOPIC=Original post/body [weird everyday stories]: "
if "%TOPIC%"=="" set "TOPIC=weird everyday stories"
goto after_text_prompts

:after_text_prompts
echo.
set /p "COUNT=How many total slides/posts [10]: "
if "%COUNT%"=="" set "COUNT=10"

echo.
set /p "MAKE_IMAGE=Generate an OP image with local ComfyUI RealVisXL? y/N: "
if /I "%MAKE_IMAGE%"=="Y" set "IMAGE_FLAGS=--image-mode comfyui"

echo.
set /p "MAKE_VIDEO=Make stitched MP4 video with smooth transitions? y/N: "
if /I "%MAKE_VIDEO%"=="Y" set "VIDEO_FLAGS=--video --concat-video"

echo.
echo Platform:     %PLATFORM%
echo Reply style:  %POST_TITLE%
echo Original:     %TOPIC%
echo Count:        %COUNT%
echo TTS:          %TTS%
echo Voice:        %VOICE%
echo Cmd:          %TTS_CMD%
echo OP image:     %IMAGE_FLAGS%
echo Video:        %VIDEO_FLAGS%
echo.

if /I "%PLATFORM%"=="x" if "%POST_TITLE%"=="" goto run_x_without_style
if /I "%PLATFORM%"=="x" goto run_x_with_style
goto run_reddit

:run_x_without_style
java -cp out redditTxtToImg.CheckedRunner --platform x --auto --topic "%TOPIC%" --count %COUNT% --llm-model %MODEL% --tts %TTS% --tts-command "%TTS_CMD%" --voice "%VOICE%" --no-watermark --top %IMAGE_FLAGS% %VIDEO_FLAGS%
goto after_java_run

:run_x_with_style
java -cp out redditTxtToImg.CheckedRunner --platform x --auto --post-title "%POST_TITLE%" --topic "%TOPIC%" --count %COUNT% --llm-model %MODEL% --tts %TTS% --tts-command "%TTS_CMD%" --voice "%VOICE%" --no-watermark --top %IMAGE_FLAGS% %VIDEO_FLAGS%
goto after_java_run

:run_reddit
java -cp out redditTxtToImg.CheckedRunner --platform reddit --auto --post-title "%POST_TITLE%" --topic "%TOPIC%" --count %COUNT% --llm-model %MODEL% --tts %TTS% --tts-command "%TTS_CMD%" --voice "%VOICE%" --no-watermark --top %IMAGE_FLAGS% %VIDEO_FLAGS%
goto after_java_run

:after_java_run
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
echo OP images:   output\images\
echo Audio:       output\audio\
echo Clips:       output\video\
echo Final video: output\video\final.mp4
echo.
pause
