@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "MODEL=llama3.1:8b"
set "COUNT=10"
set "POST_TITLE=Finish this story in the comments"
set "TOPIC=weird everyday stories"
set "TTS=kokoro"
set "VOICE=af_heart"
set "MAKE_VIDEO=N"
set "VIDEO_FLAGS="
set "VOICE_DIR=voices"
set "PIPER_CMD=%~dp0piper\piper.exe"
set "KOKORO_PYTHON=%~dp0.venv-kokoro\Scripts\python.exe"
set "PYTHON_CMD=python"
set "TTS_CMD="

echo.
echo ThreadGens local AI runner
echo Branch: feature/kokoro-tts
echo.

echo Choose TTS engine:
echo 1. Kokoro - RECOMMENDED/default, better local voice, uses isolated .venv-kokoro
echo 2. Piper  - fallback, faster/lower quality
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

if exist "%PIPER_CMD%" goto piper_ok
where piper >nul 2>nul
if not errorlevel 1 (
  set "TTS_CMD=piper"
  goto piper_ok
)

echo Piper was not found.
echo Checked: %~dp0piper\piper.exe
echo Also checked Windows PATH.
echo.
echo Put piper.exe in the local piper folder, install Piper to PATH, or paste the full path now.
set /p "TTS_CMD=Full path to piper.exe, or blank to stop: "
if "%TTS_CMD%"=="" (
  echo Stopping. Piper is required when using Piper TTS.
  pause
  exit /b 1
)

:piper_ok
echo Piper command: %TTS_CMD%
if not exist "%VOICE_DIR%" mkdir "%VOICE_DIR%"

echo.
set /p "GETVOICE=Download another Piper voice? y/N: "
if /I "%GETVOICE%"=="Y" goto voice_menu
goto after_tts_setup

:voice_menu
echo.
echo Choose a Piper voice to download. You can type the number OR the exact voice name:
echo 1. en_US-lessac-medium   female US medium
echo 2. en_US-amy-medium      female US medium
echo 3. en_US-ryan-high       male US high
echo 4. custom direct base URL
echo.
set /p "VOICE_CHOICE=Choice or voice name [1-4/name]: "

if /I "%VOICE_CHOICE%"=="1" set "VOICE_CHOICE=en_US-lessac-medium"
if /I "%VOICE_CHOICE%"=="2" set "VOICE_CHOICE=en_US-amy-medium"
if /I "%VOICE_CHOICE%"=="3" set "VOICE_CHOICE=en_US-ryan-high"

if /I "%VOICE_CHOICE%"=="en_US-lessac-medium" (
  set "DL_VOICE=en_US-lessac-medium"
  set "DL_BASE=https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium"
  goto download_voice
)
if /I "%VOICE_CHOICE%"=="en_US-amy-medium" (
  set "DL_VOICE=en_US-amy-medium"
  set "DL_BASE=https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium"
  goto download_voice
)
if /I "%VOICE_CHOICE%"=="en_US-ryan-high" (
  set "DL_VOICE=en_US-ryan-high"
  set "DL_BASE=https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/high"
  goto download_voice
)
if /I "%VOICE_CHOICE%"=="4" goto custom_voice

echo Unknown built-in voice: %VOICE_CHOICE%
echo Switching to custom download mode.
set "DL_VOICE=%VOICE_CHOICE%"
goto ask_custom_base

:custom_voice
set /p "DL_VOICE=Voice file name without .onnx: "

:ask_custom_base
set /p "DL_BASE=Base URL folder containing the .onnx and .onnx.json files: "
if "%DL_BASE%"=="" (
  echo No URL entered. Skipping download.
  goto after_tts_setup
)

:download_voice
echo.
echo Downloading %DL_VOICE%...
curl.exe -L -f -o "%VOICE_DIR%\%DL_VOICE%.onnx" "%DL_BASE%/%DL_VOICE%.onnx"
if errorlevel 1 (
  echo Voice model download failed. Not selecting this voice.
  goto after_tts_setup
)
curl.exe -L -f -o "%VOICE_DIR%\%DL_VOICE%.onnx.json" "%DL_BASE%/%DL_VOICE%.onnx.json"
if errorlevel 1 echo Warning: voice config download may have failed.
set "VOICE=%DL_VOICE%"
goto after_tts_setup

:kokoro_setup
set "TTS=kokoro"
set "VOICE=af_heart"

if exist "%KOKORO_PYTHON%" (
  set "TTS_CMD=%KOKORO_PYTHON%"
  goto kokoro_python_ok
)

echo Kokoro venv was not found:
echo %KOKORO_PYTHON%
echo.
echo Run setup_windows.bat first to create the isolated Kokoro environment.
echo Falling back to system Python only if you explicitly choose it.
set /p "USE_SYSTEM_PYTHON=Use system Python anyway? y/N: "
if /I not "%USE_SYSTEM_PYTHON%"=="Y" (
  pause
  exit /b 1
)
set "TTS_CMD=%PYTHON_CMD%"

:kokoro_python_ok
echo Kokoro Python: %TTS_CMD%
echo.
echo Common Kokoro voices:
echo af_heart   af_bella   af_nicole
echo am_adam    am_michael
echo bf_emma    bm_george
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

if /I "%TTS%"=="piper" (
  echo.
  echo Available voices:
  java -cp out redditTxtToImg.RedditScreenshotGenerator --list-voices
  echo.
  set /p "VOICE=Voice name or ONNX path [%VOICE%]: "
  if "%VOICE%"=="" set "VOICE=en_US-lessac-medium"
)

echo.
set /p "POST_TITLE=Post title [Finish this story in the comments]: "
if "%POST_TITLE%"=="" set "POST_TITLE=Finish this story in the comments"

echo.
set /p "TOPIC=Original story/body [weird everyday stories]: "
if "%TOPIC%"=="" set "TOPIC=weird everyday stories"

echo.
set /p "COUNT=How many total slides/posts [10]: "
if "%COUNT%"=="" set "COUNT=10"

echo.
set /p "MAKE_VIDEO=Make stitched MP4 video with smooth transitions? y/N: "
if /I "%MAKE_VIDEO%"=="Y" set "VIDEO_FLAGS=--video --concat-video"

echo.
echo Post title: %POST_TITLE%
echo Original:   %TOPIC%
echo Count:      %COUNT%
echo TTS:        %TTS%
echo Voice:      %VOICE%
echo Cmd:        %TTS_CMD%
echo Video:      %VIDEO_FLAGS%
echo Watermark:  off
echo Pipeline:   text/script first, then images, then audio, then video
echo.

java -cp out redditTxtToImg.RedditScreenshotGenerator --auto --post-title "%POST_TITLE%" --topic "%TOPIC%" --count %COUNT% --llm-model %MODEL% --tts %TTS% --tts-command "%TTS_CMD%" --voice "%VOICE%" --no-watermark %VIDEO_FLAGS%

echo.
echo Done.
echo Text:        output\script\generated_comments.txt
echo Images:      output\
echo Audio:       output\audio\
echo Clips:       output\video\
echo Final video: output\video\final.mp4
echo.
pause
