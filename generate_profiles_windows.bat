@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "COUNT=25"
set "SIZE=512"
set "PFP_DIR=assets\pfp"
set "NAMES_FILE=data\author_names.txt"
set "AI_PREFIX=tg_ai_profile"
set "PROC_PREFIX=tg_profile"
set "APPEND_NAMES=N"
set "PYTHON_CMD=python"
set "COMFY_URL=http://127.0.0.1:8188"
set "CHECKPOINT="
set "STEPS=18"
set "CFG=6.5"
set "MODE=ai"

echo.
echo ThreadGens profile generator
echo Branch: feature/profile-batch-generator
echo.
echo 1. AI face/selfie profile pictures through local ComfyUI  ^(recommended^)
echo 2. Cheap procedural fallback avatars ^(no AI^)
echo.
set /p "MODE_CHOICE=Choice [1/2, default 1]: "
if "%MODE_CHOICE%"=="2" set "MODE=procedural"
if /I "%MODE_CHOICE%"=="procedural" set "MODE=procedural"
if /I "%MODE_CHOICE%"=="ai" set "MODE=ai"
if "%MODE_CHOICE%"=="" set "MODE=ai"

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

if /I "%MODE%"=="procedural" goto procedural_setup
goto ai_setup

:ai_setup
if not exist "tools\generate_comfy_profiles.py" (
  echo Missing tools\generate_comfy_profiles.py
  pause
  exit /b 1
)

echo.
echo ComfyUI must already be running.
echo Usually: http://127.0.0.1:8188
echo.
set /p "COMFY_URL=ComfyUI URL [http://127.0.0.1:8188]: "
if "%COMFY_URL%"=="" set "COMFY_URL=http://127.0.0.1:8188"

echo.
set /p "LIST_MODELS=List available ComfyUI checkpoints first? y/N: "
if /I "%LIST_MODELS%"=="Y" (
  "%PYTHON_CMD%" tools\generate_comfy_profiles.py --comfy-url "%COMFY_URL%" --list-checkpoints
  echo.
)

echo.
echo Enter the exact checkpoint filename from ComfyUI, or leave blank to auto-pick the first one.
echo Example: realisticVisionV60B1_v51VAE.safetensors
set /p "CHECKPOINT=Checkpoint filename [auto]: "

echo.
set /p "COUNT=How many AI face profiles/usernames? [25]: "
if "%COUNT%"=="" set "COUNT=25"

echo.
set /p "SIZE=Profile PNG size in pixels? [512]: "
if "%SIZE%"=="" set "SIZE=512"

echo.
set /p "STEPS=Sampling steps? [18]: "
if "%STEPS%"=="" set "STEPS=18"

echo.
set /p "CFG=CFG scale? [6.5]: "
if "%CFG%"=="" set "CFG=6.5"

echo.
set /p "APPEND_NAMES=Append to author_names.txt instead of replacing? y/N: "
if /I "%APPEND_NAMES%"=="Y" (
  set "APPEND_FLAG=--append-names"
  set "NAMES_MODE=append"
) else (
  set "APPEND_FLAG="
  set "NAMES_MODE=replace with backup"
)

echo.
echo Mode:                  AI ComfyUI face/selfie profiles
echo ComfyUI URL:           %COMFY_URL%
echo Checkpoint:            %CHECKPOINT%
echo Output profile folder: %PFP_DIR%
echo Username file:         %NAMES_FILE%
echo Count:                 %COUNT%
echo Size:                  %SIZE%x%SIZE%
echo Steps/CFG:             %STEPS% / %CFG%
echo Names mode:            %NAMES_MODE%
echo.

if "%CHECKPOINT%"=="" (
  "%PYTHON_CMD%" tools\generate_comfy_profiles.py --count %COUNT% --size %SIZE% --pfp-dir "%PFP_DIR%" --names-file "%NAMES_FILE%" --prefix "%AI_PREFIX%" --comfy-url "%COMFY_URL%" --steps %STEPS% --cfg %CFG% %APPEND_FLAG%
) else (
  "%PYTHON_CMD%" tools\generate_comfy_profiles.py --count %COUNT% --size %SIZE% --pfp-dir "%PFP_DIR%" --names-file "%NAMES_FILE%" --prefix "%AI_PREFIX%" --comfy-url "%COMFY_URL%" --checkpoint "%CHECKPOINT%" --steps %STEPS% --cfg %CFG% %APPEND_FLAG%
)
if errorlevel 1 (
  echo AI profile generation failed.
  pause
  exit /b 1
)
goto done

:procedural_setup
if not exist "tools\generate_profiles.py" (
  echo Missing tools\generate_profiles.py
  pause
  exit /b 1
)

echo.
set /p "COUNT=How many procedural profiles/usernames? [100]: "
if "%COUNT%"=="" set "COUNT=100"

echo.
set /p "SIZE=Profile PNG size in pixels? [256]: "
if "%SIZE%"=="" set "SIZE=256"

echo.
set /p "APPEND_NAMES=Append to author_names.txt instead of replacing? y/N: "
if /I "%APPEND_NAMES%"=="Y" (
  set "APPEND_FLAG=--append-names"
) else (
  set "APPEND_FLAG="
)

echo.
echo Mode:                  procedural fallback avatars
echo Output profile folder: %PFP_DIR%
echo Username file:         %NAMES_FILE%
echo Count:                 %COUNT%
echo Size:                  %SIZE%x%SIZE%
echo.

"%PYTHON_CMD%" tools\generate_profiles.py --count %COUNT% --size %SIZE% --pfp-dir "%PFP_DIR%" --names-file "%NAMES_FILE%" --prefix "%PROC_PREFIX%" %APPEND_FLAG%
if errorlevel 1 (
  echo Procedural profile generation failed.
  pause
  exit /b 1
)
goto done

:done
echo.
echo Done.
echo Profile pictures: %PFP_DIR%
echo Usernames:        %NAMES_FILE%
echo.
pause
