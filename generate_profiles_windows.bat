@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "COUNT=100"
set "SIZE=256"
set "PFP_DIR=assets\pfp"
set "NAMES_FILE=data\author_names.txt"
set "PREFIX=tg_profile"
set "APPEND_NAMES=N"
set "PYTHON_CMD=python"

echo.
echo ThreadGens profile generator
echo Branch: feature/profile-batch-generator
echo.
echo This creates cheap local procedural profile PNGs and Reddit-style usernames.
echo No AI model, no web calls, and no Python packages required.
echo.

where %PYTHON_CMD% >nul 2>nul
if errorlevel 1 (
  echo Python was not found in PATH.
  set /p "PYTHON_CMD=Full path to python.exe, or blank to stop: "
  if "%PYTHON_CMD%"=="" (
    echo Stopping. Python is required for profile generation.
    pause
    exit /b 1
  )
)

if not exist "tools\generate_profiles.py" (
  echo Missing tools\generate_profiles.py
  pause
  exit /b 1
)

echo.
set /p "COUNT=How many profiles/usernames? [100]: "
if "%COUNT%"=="" set "COUNT=100"

echo.
set /p "SIZE=Profile PNG size in pixels? [256]: "
if "%SIZE%"=="" set "SIZE=256"

echo.
set /p "APPEND_NAMES=Append to author_names.txt instead of replacing? y/N: "

echo.
echo Output profile folder: %PFP_DIR%
echo Username file:         %NAMES_FILE%
echo Count:                 %COUNT%
echo Size:                  %SIZE%x%SIZE%
if /I "%APPEND_NAMES%"=="Y" (
  echo Names mode:            append
  set "APPEND_FLAG=--append-names"
) else (
  echo Names mode:            replace with backup
  set "APPEND_FLAG="
)
echo.

"%PYTHON_CMD%" tools\generate_profiles.py --count %COUNT% --size %SIZE% --pfp-dir "%PFP_DIR%" --names-file "%NAMES_FILE%" --prefix "%PREFIX%" %APPEND_FLAG%
if errorlevel 1 (
  echo Profile generation failed.
  pause
  exit /b 1
)

echo.
echo Done.
echo Profile pictures: %PFP_DIR%
echo Usernames:        %NAMES_FILE%
echo.
pause
