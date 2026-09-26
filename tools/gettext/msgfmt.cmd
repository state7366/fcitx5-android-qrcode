@echo off
rem msgfmt shim launcher: prefer PATH python, fall back to py launcher.
rem Requires Python 3.10+ installed on the host (msgfmt.py is pure stdlib).
where python >nul 2>nul
if %ERRORLEVEL%==0 (
    python "%~dp0msgfmt.py" %*
    exit /b %ERRORLEVEL%
)
where py >nul 2>nul
if %ERRORLEVEL%==0 (
    py -3 "%~dp0msgfmt.py" %*
    exit /b %ERRORLEVEL%
)
echo msgfmt.cmd: python not found on PATH, install Python 3.10+ 1>&2
exit /b 1
