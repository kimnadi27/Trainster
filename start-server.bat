@echo off
setlocal
title Trainster Server

pushd "%~dp0server"
if errorlevel 1 (
    echo ERROR: Cannot find the server folder next to this BAT file.
    pause
    exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
    echo ERROR: Python virtual environment was not found in server\.venv.
    popd
    pause
    exit /b 1
)

echo Starting Trainster server...
echo API docs: http://localhost:8000/docs
echo Press Ctrl+C to stop.
echo.

".venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
set "TRAINSTER_EXIT_CODE=%ERRORLEVEL%"
popd

if not "%TRAINSTER_EXIT_CODE%"=="0" (
    echo.
    echo Server stopped with an error. See the message above.
    pause
)
exit /b %TRAINSTER_EXIT_CODE%
