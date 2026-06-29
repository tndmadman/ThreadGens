@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "MODEL=llama3.1:8b"
set "COUNT=10"
set "TOPIC=weird everyday stories"
set "VOICE=en_US-lessac-medium"
set "TTS=piper"
set "MAKE_VIDEO=N"
set "MAKE_FINAL=N"
set "VIDEO_FLAGS="

echo.
echo ThreadGens local AI runner
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
set /p "VOICE=Voice name or ONNX path [en_US-lessac-medium]: "
if "%VOICE%"=="" set "VOICE=en_US-lessac-medium"

echo.
set /p "TOPIC=Topic [weird everyday stories]: "
if "%TOPIC%"=="" set "TOPIC=weird everyday stories"

echo.
set /p "COUNT=How many posts [10]: "
if "%COUNT%"=="" set "COUNT=10"

echo.
set /p "MAKE_VIDEO=Make MP4 video clips? y/N: "
if /I "%MAKE_VIDEO%"=="Y" set "VIDEO_FLAGS=--video"

if /I "%MAKE_VIDEO%"=="Y" (
  set /p "MAKE_FINAL=Also combine into output\video\final.mp4? y/N: "
  if /I "%MAKE_FINAL%"=="Y" set "VIDEO_FLAGS=--video --concat-video"
)

echo.
echo Topic: %TOPIC%
echo Count: %COUNT%
echo Voice: %VOICE%
echo Video: %VIDEO_FLAGS%
echo.

java -cp out redditTxtToImg.RedditScreenshotGenerator --auto --topic "%TOPIC%" --count %COUNT% --llm-model %MODEL% --tts %TTS% --voice "%VOICE%" %VIDEO_FLAGS%

echo.
echo Done.
echo Text:   output\script\generated_comments.txt
echo Images: output\
echo Audio:  output\audio\
echo Video:  output\video\
echo.
pause
