@echo off
REM install_apk.bat - installs the debug APK to device ZY22DDK2FL.
REM Uses absolute adb path so it works even when adb is not in PATH.
setlocal

set ADB=C:\Users\ew\AppData\Local\Android\Sdk\platform-tools\adb.exe
set APK=F:\KTV\app\build\outputs\apk\debug\app-debug.apk

if not exist "%ADB%" (
  echo [ERR] adb not found: %ADB%
  echo       Check ANDROID_HOME / platform-tools path.
  goto :end
)
if not exist "%APK%" (
  echo [ERR] APK not found: %APK%
  echo       Run tools\build_apk.bat first (and confirm BUILD OK).
  goto :end
)

echo [*] Installing debug APK to device ZY22DDK2FL ...
"%ADB%" -s ZY22DDK2FL install -r "%APK%"
if %ERRORLEVEL%==0 (
  echo [OK] Install succeeded. Open SensorGuard -> start monitoring -> view timeline.
) else (
  echo [ERR] Install failed (see adb output above).
)
:end
endlocal
