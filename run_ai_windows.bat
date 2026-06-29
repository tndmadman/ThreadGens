@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "MODEL=llama3.1:8b"
set "COUNT=10"
set "TOPIC=weird everyday stories"
set "VOICE=en_US-lessac-medium"
set "TTS=piper"
set "MAKE_VIDEO=N"
set "VIDEO_FLAGS="
set "VOICE_DIR=voices"
set "PIPER_CMD=%~dp0piper\piper.exe"

echo.
echo ThreadGens local AI runner
echo.

if exist "%PIPER_CMD%" goto piper_ok
where piper >nul 2>nul
if not errorlevel 1 (
  set "PIPER_CMD=piper"
  goto piper_ok
)

echo Piper was not found.
echo Checked: %~dp0piper\piper.exe
echo Also checked Windows PATH.
echo.
echo Put piper.exe in the local piper folder, or install Piper and add it to PATH.
echo You can also paste the full path now.
set /p "PIPER_CMD=Full path to piper.exe, or blank to stop: "
if "%PIPER_CMD%"=="" (
  echo Stopping. Piper is required for voice/audio.
  pause
  exit /b 1
)

:piper_ok
echo Piper command: %PIPER_CMD%

if not exist "%VOICE_DIR%" mkdir "%VOICE_DIR%"

echo.
set /p "GETVOICE=Download another Piper voice? y/N: "
if /I "%GETVOICE%"=="Y" goto voice_menu
goto after_voice_download

:voice_menu
echo.
echo Choose a voice to download:
echo 1. en_US-lessac-medium   female US medium
echo 2. en_US-amy-medium      female US medium
echo 3. en_US-ryan-high       male US high
echo 4. custom direct base URL
echo.
set /p "VOICE_CHOICE=Choice [1-4]: "

if "%VOICE_CHOICE%"=="2" (
  set "DL_VOICE=en_US-amy-medium"
  set "DL_BASE=https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium"
  goto download_voice
)
if "%VOICE_CHOICE%"=="3" (
  set "DL_VOICE=en_US-ryan-high"
  set "DL_BASE=https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/high"
  goto download_voice
)
if "%VOICE_CHOICE%"=="4" (
  set /p "DL_VOICE=Voice file name without .onnx: "
  set /p "DL_BASE=Base URL folder containing the .onnx and .onnx.json files: "
  goto download_voice
)
set "DL_VOICE=en_US-lessac-medium"
set "DL_BASE=https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium"

:download_voice
echo.
echo Downloading %DL_VOICE%...
curl.exe -L -o "%VOICE_DIR%\%DL_VOICE%.onnx" "%DL_BASE%/%DL_VOICE%.onnx"
if errorlevel 1 echo Warning: voice model download may have failed.
curl.exe -L -o "%VOICE_DIR%\%DL_VOICE%.onnx.json" "%DL_BASE%/%DL_VOICE%.onnx.json"
if errorlevel 1 echo Warning: voice config download may have failed.
set "VOICE=%DL_VOICE%"

:after_voice_download
echo.
echo Building Java files...
javac -d out src\redditTxtToImg\*.java
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)

echo.
echo Available Piper voices:
java -cp out redditTxtToImg.RedditScreenshotGenerator --list-voices
echo.
set /p "VOICE=Voice name or ONNX path [%VOICE%]: "
if "%VOICE%"=="" set "VOICE=en_US-lessac-medium"

echo.
set /p "TOPIC=Topic [weird everyday stories]: "
if "%TOPIC%"=="" set "TOPIC=weird everyday stories"

echo.
set /p "COUNT=How many posts [10]: "
if "%COUNT%"=="" set "COUNT=10"

echo.
set /p "MAKE_VIDEO=Make stitched MP4 video? y/N: "
if /I "%MAKE_VIDEO%"=="Y" set "VIDEO_FLAGS=--video --concat-video"

echo.
echo Topic: %TOPIC%
echo Count: %COUNT%
echo Voice: %VOICE%
echo Piper: %PIPER_CMD%
echo Video: %VIDEO_FLAGS%
echo.

java -cp out redditTxtToImg.RedditScreenshotGenerator --auto --topic "%TOPIC%" --count %COUNT% --llm-model %MODEL% --tts %TTS% --tts-command "%PIPER_CMD%" --voice "%VOICE%" %VIDEO_FLAGS%

echo.
echo Done.
echo Text:        output\script\generated_comments.txt
echo Images:      output\
echo Audio:       output\audio\
echo Clips:       output\video\
echo Final video: output\video\final.mp4
echo.
pause
