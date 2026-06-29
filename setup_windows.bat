@echo off
setlocal
cd /d "%~dp0"

echo ThreadGens Windows setup
echo This will install/check Java, Ollama, Piper, Kokoro TTS, profile generator support, and build ThreadGens.
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup_windows.ps1"
set "EXITCODE=%ERRORLEVEL%"
echo.
if "%EXITCODE%"=="0" (
    echo Setup finished.
) else (
    echo Setup failed with exit code %EXITCODE%.
)
echo.
pause
exit /b %EXITCODE%
