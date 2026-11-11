@echo off
rem Gradle startup script for Windows

set GRADLE_HOME=%~dp0..\gradle
if not exist "%GRADLE_HOME%" (
    echo Gradle not found. Please run 'gradle wrapper' in the project root.
    exit /b 1
)

"%GRADLE_HOME%\bin\gradle.bat" %*