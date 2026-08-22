@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo.
echo ThreadGens video style: locked social frame, no pan/zoom drift, subtle full-frame grain.
echo Applies to both Reddit and X video generation.
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\run_ai_windows.ps1"
set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo Done.
) else (
  echo Generation failed with exit code %EXITCODE%.
)
echo.
pause
exit /b %EXITCODE%
