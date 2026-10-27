@echo off
setlocal EnableExtensions

rem Launch SUMO GUI with Russian translations enabled.
rem Set SUMO_HOME if SUMO is installed in a non-standard directory.
if defined SUMO_HOME if exist "%SUMO_HOME%\bin\sumo-gui.exe" (
    set "SUMO_GUI=%SUMO_HOME%\bin\sumo-gui.exe"
)

if not defined SUMO_GUI (
    for /f "delims=" %%I in ('where sumo-gui.exe 2^>nul') do (
        if not defined SUMO_GUI set "SUMO_GUI=%%I"
    )
)

if not defined SUMO_GUI (
    echo [ERROR] sumo-gui.exe was not found.
    echo Install SUMO, add its bin directory to PATH, or set SUMO_HOME.
    echo Download: https://eclipse.dev/sumo/docs/Downloads.html
    pause
    exit /b 1
)

echo Launching SUMO GUI in Russian...
"%SUMO_GUI%" --language ru %*
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
    echo SUMO exited with code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%
