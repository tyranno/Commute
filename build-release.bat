@echo off
REM ============================================================================
REM  Commute - Build both release artifacts
REM
REM    play   -> .aab  Google Play upload. Play only accepts an App Bundle for a
REM                    new app, and this flavor has no self-updater (Play forbids
REM                    an app it distributes from installing anything itself).
REM    github -> .apk  GitHub Releases upload. Includes the self-updater, which
REM                    is what the app's "업데이트 확인" button checks against.
REM
REM  Both are signed with the release key when keystore.properties is present;
REM  without it Gradle produces unsigned artifacts instead of failing.
REM ============================================================================
setlocal
cd /d %~dp0

set JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8
set ANDROID_HOME=C:\Program Files (x86)\Android\android-sdk
set ANDROID_SDK_ROOT=%ANDROID_HOME%

if not exist keystore.properties (
    echo WARNING: keystore.properties not found - artifacts will be UNSIGNED
    echo          and cannot be uploaded to Play or installed as an upgrade.
    echo.
)

echo [1/2] gradle bundlePlayRelease ...
call .\gradlew.bat bundlePlayRelease --no-daemon || goto :err

echo [2/2] gradle assembleGithubRelease ...
call .\gradlew.bat assembleGithubRelease --no-daemon || goto :err

set AAB=app\build\outputs\bundle\playRelease\app-play-release.aab
set APK=app\build\outputs\apk\github\release\app-github-release.apk

echo.
echo BUILD COMPLETE
echo   Play   : %AAB%
echo   GitHub : %APK%
echo.
echo Before publishing, install the release build on a device and check it runs:
echo R8 shrinking is on, and shrinking is the one setting that only breaks at runtime.
echo.
goto :eof

:err
echo.
echo *** BUILD FAILED ***
exit /b 1
