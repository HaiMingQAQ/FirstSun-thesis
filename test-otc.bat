@echo off
setlocal
pushd "%~dp0"
if errorlevel 1 (
  echo Cannot enter project directory.
  pause
  exit /b 2
)
node "%~dp0scripts\otc-acceptance\runner.cjs" %*
set "otc_exit=%errorlevel%"
popd
if "%~1"=="" pause
exit /b %otc_exit%
