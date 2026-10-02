@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local-environment.ps1" -Action rebuild
set "RESULT=%ERRORLEVEL%"
if not defined FIRSTSUN_NO_PAUSE pause
exit /b %RESULT%
