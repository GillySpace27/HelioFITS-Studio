@echo off
REM JHelioviewer - PUNCH & coronagraph preview build.  Needs Java 25 or newer.
cd /d "%~dp0"

set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"

"%JAVA%" -version >nul 2>&1
if errorlevel 1 (
    echo.
    echo JHelioviewer needs Java 25 or newer, which was not found.
    echo Install it from https://adoptium.net  ^(download Temurin 25^), then run this again.
    echo.
    pause
    exit /b 1
)

"%JAVA%" --enable-native-access=ALL-UNNAMED -jar JHelioviewer.jar %*
