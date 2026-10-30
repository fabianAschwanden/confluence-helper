@echo off
rem Starts the confluence-helper web UI (cfh ui). Builds target\cfh.jar first if it is missing.
setlocal

set "CFH_REPO=%~dp0.."
if exist "%CFH_REPO%\target\cfh.jar" goto run

rem Resolve mvn strictly from PATH (never from the current directory).
set "CFH_MVN="
for %%M in (mvn.cmd) do set "CFH_MVN=%%~$PATH:M"
if defined CFH_MVN goto build
>&2 echo cfh-ui: "%CFH_REPO%\target\cfh.jar" fehlt und Maven (mvn) ist nicht installiert.
>&2 echo   Maven installieren und erneut starten, oder bauen mit:
>&2 echo   cd /d "%CFH_REPO%" ^&^& mvn -q package -DskipTests
exit /b 1

:build
>&2 echo cfh-ui: target\cfh.jar fehlt, wird jetzt gebaut (einmalig, dauert etwa eine Minute) ...
pushd "%CFH_REPO%"
call "%CFH_MVN%" -q -B package -DskipTests
set "CFH_RC=%ERRORLEVEL%"
popd
if "%CFH_RC%"=="0" goto run
>&2 echo cfh-ui: Bauen fehlgeschlagen. Ausgabe oben pruefen.
exit /b 1

:run
call "%~dp0cfh.cmd" ui %*
exit /b %ERRORLEVEL%
