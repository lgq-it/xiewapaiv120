@echo off
rem ============================================================
rem  Build script for Windows (double-click friendly)
rem    build-exe.cmd            full build -> dist\chengyi-updatepai\
rem    build-exe.cmd --jar-only jar only (fast check)
rem    add --no-pause to skip the final pause
rem
rem  Requires JDK 21 (must contain jpackage) and Maven.
rem  Both are auto-detected; edit JDK_FALLBACK below if needed.
rem ============================================================
setlocal enabledelayedexpansion
chcp 65001 >nul
pushd "%~dp0"

set "APP_NAME=chengyi-updatepai"
set "APP_VERSION=1.0.0"
set "MAIN_CLASS=com.chengyi.xiewa.save.XiewaEditorApp"
set "MAIN_JAR=xiewa-save.jar"
set "JDK_FALLBACK=C:\Program Files\Java\jdk-21"

set "NOPAUSE="
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
if /i "%~2"=="--no-pause" set "NOPAUSE=1"

rem ---------------------------------------------------------- locate JDK
set "JDK="
if exist "%JAVA_HOME%\bin\jpackage.exe" set "JDK=%JAVA_HOME%"
if not defined JDK if exist "%JDK_FALLBACK%\bin\jpackage.exe" set "JDK=%JDK_FALLBACK%"
if not defined JDK (
  for /d %%D in ("C:\Program Files\Java\jdk-21*") do (
    if not defined JDK if exist "%%~D\bin\jpackage.exe" set "JDK=%%~D"
  )
)
if not defined JDK goto :no_jdk

set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%PATH%"

rem ---------------------------------------------------------- locate Maven
set "MVN="
for /f "delims=" %%I in ('where mvn 2^>nul') do (
  if not defined MVN set "MVN=%%I"
)
if not defined MVN (
  for /d %%D in ("C:\Program Files\JetBrains\*") do (
    if exist "%%~D\plugins\maven\lib\maven3\bin\mvn.cmd" set "MVN=%%~D\plugins\maven\lib\maven3\bin\mvn.cmd"
  )
)
if not defined MVN goto :no_mvn

echo JDK    : %JDK%
echo Maven  : %MVN%
echo.

rem ---------------------------------------------------------- 1. build jar
echo [1/3] mvn clean package
call "%MVN%" -q clean package
if errorlevel 1 goto :build_failed
if not exist "target\%MAIN_JAR%" goto :build_failed
echo      OK: target\%MAIN_JAR%
echo.

if /i "%~1"=="--jar-only" goto :jar_only

rem ---------------------------------------------------------- 2. stage input
rem jpackage copies EVERYTHING under --input into app\, so the staging
rem folder must contain only the fat jar (target also has original-*.jar).
echo [2/3] staging target\jpkg
if exist "target\jpkg" rmdir /s /q "target\jpkg"
mkdir "target\jpkg"
copy /y "target\%MAIN_JAR%" "target\jpkg\" >nul
echo.

rem ---------------------------------------------------------- 3. jpackage
echo [3/3] jpackage app-image
if exist "dist\%APP_NAME%" rmdir /s /q "dist\%APP_NAME%"
if not exist "dist" mkdir "dist"
"%JDK%\bin\jpackage.exe" --type app-image --name "%APP_NAME%" --app-version "%APP_VERSION%" --input "target\jpkg" --main-jar "%MAIN_JAR%" --main-class "%MAIN_CLASS%" --icon "design\icon.ico" --dest "dist" --java-options "-Dfile.encoding=UTF-8"
if errorlevel 1 goto :build_failed
echo.


echo Done: dist\%APP_NAME%
dir /b "dist\%APP_NAME%"
goto :done

:jar_only
echo Done: target\%MAIN_JAR%
echo Run: java -jar target\%MAIN_JAR%
goto :done

:no_jdk
echo [ERROR] JDK 21 with jpackage not found.
echo         Set JAVA_HOME to a JDK folder (not a JRE), e.g.
echo         set JAVA_HOME=C:\Program Files\Java\jdk-21
goto :fail

:no_mvn
echo [ERROR] Maven not found (neither in PATH nor bundled with IntelliJ IDEA).
goto :fail

:build_failed
echo.
echo [FAILED] see the messages above
goto :fail

:fail
popd
if not defined NOPAUSE pause
exit /b 1

:done
popd
if not defined NOPAUSE pause
exit /b 0
