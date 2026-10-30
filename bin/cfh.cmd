@echo off
rem Starts the confluence-helper CLI (cfh). Build first: mvn -q package -DskipTests
setlocal

set "CFH_JAR=%~dp0..\target\cfh.jar"
if exist "%CFH_JAR%" goto findjava
>&2 echo cfh: "%CFH_JAR%" fehlt. Bitte zuerst bauen:
>&2 echo   cd /d "%~dp0.." ^&^& mvn -q package -DskipTests
exit /b 1

:findjava
rem Never run a bare "java": cmd.exe would try the current directory first (e.g. a planted java.bat
rem in a download or export folder). Use JAVA_HOME, otherwise resolve java.exe strictly from PATH.
set "CFH_JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "CFH_JAVA=%JAVA_HOME%\bin\java.exe"
if not defined CFH_JAVA for %%J in (java.exe) do set "CFH_JAVA=%%~$PATH:J"
if defined CFH_JAVA goto run
>&2 echo cfh: Java nicht gefunden. JAVA_HOME setzen oder java.exe in den PATH aufnehmen.
exit /b 1

:run
"%CFH_JAVA%" %JAVA_OPTS% -jar "%CFH_JAR%" %*
exit /b %ERRORLEVEL%
