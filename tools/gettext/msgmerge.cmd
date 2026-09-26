@echo off
rem msgmerge is never invoked by fcitx5_install_translation; this shim only
rem exists so CMake's FindGettext can locate and validate a "msgmerge".
if "%~1"=="--version" (
    echo msgmerge ^(GNU gettext-tools^) 0.22
)
exit /b 0
