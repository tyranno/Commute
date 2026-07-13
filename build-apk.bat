@echo off
REM ============================================================================
REM  Commute - Build debug APK + ADB install
REM ============================================================================
setlocal
cd /d %~dp0

set JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8
set ANDROID_HOME=C:\Program Files (x86)\Android\android-sdk
set ANDROID_SDK_ROOT=%ANDROID_HOME%

echo [1/1] gradle assembleDebug ...
call .\gradlew.bat assembleDebug --no-daemon || goto :err

set APK=app\build\outputs\apk\debug\app-debug.apk
echo.
echo BUILD COMPLETE: %APK%
echo.

adb devices 2>nul | findstr /v "List" | findstr "device" > nul
if not errorlevel 1 (
    echo Device found. Installing via ADB...
    adb install -r "%APK%"
    if errorlevel 1 (
        echo Install failed - signature mismatch. Run:
        echo   adb uninstall com.commute.app
        echo   adb install "%APK%"
    ) else (
        echo Install OK.
    )
) else (
    echo No device connected. Install APK manually.
)
goto :eof

:err
echo.
echo *** BUILD FAILED ***
exit /b 1
