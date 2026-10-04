@echo off
setlocal
chcp 65001 >nul
title FirstSun Thesis - Stop Services
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local-environment.ps1" -Action stop
set "RESULT=%ERRORLEVEL%"
if not defined FIRSTSUN_NO_PAUSE pause
exit /b %RESULT%
