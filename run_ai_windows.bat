@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "MODEL=llama3.1:8b"
set "COUNT=10"
set "VOICE=%~dp0voices\en_US-lessac-medium.onnx"
set "PIPER_CMD=%~dp0piper\piper.exe"

if not exist "%PIPER_CMD%" set "PIPER_CMD=piper"

if "%~1"=="" (
    set /p "TOPIC=Topic for the AI script: "
) else (
    set "TOPIC=%*"
)

if "%TOPIC%"=="" set "TOPIC=weird everyday stories"

set /p "COUNT_IN=How many posts? [10]: "
if not "%COUNT_IN%"=="" set "COUNT=%COUNT_IN%"

echo.
echo Starting Ollama if needed...
start "Ollama Server" /min ollama serve >nul 2>nul
timeout /t 3 /nobreak >nul

echo.
echo Generating %COUNT% posts about: %TOPIC%
echo.

java -cp out redditTxtToImg.RedditScreenshotGenerator --auto --topic "%TOPIC%" --count %COUNT% --llm-model %MODEL% --tts piper --voice "%VOICE%" --tts-command "%PIPER_CMD%"

echo.
echo Done.
echo Images: output\
echo Audio:  output\audio\
echo Script: output\script\generated_comments.txt
echo.
pause
