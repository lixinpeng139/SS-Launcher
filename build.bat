@echo off
setlocal
cd /d "%~dp0"
call gradlew.bat build installDist --console=plain
if errorlevel 1 (
    echo.
    echo Build failed.
    pause
    exit /b 1
)
echo.
echo Build complete:
echo   build\install\SS-Launcher\bin\SS-Launcher.bat
pause
endlocal
