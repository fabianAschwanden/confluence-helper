@echo off
rem Double-click starter for Windows: opens the confluence-helper web UI in the browser.
setlocal
cd /d "%~dp0"
title Confluence-Helper
echo Confluence-Helper wird gestartet ...
echo Dieses Fenster offen lassen, solange du die Oberflaeche benutzt.
echo.
call "%~dp0bin\cfh-ui.cmd" %*
if errorlevel 1 (
  echo.
  echo Confluence-Helper konnte nicht gestartet werden oder wurde mit einem Fehler beendet.
  pause
  exit /b 1
)
exit /b 0
