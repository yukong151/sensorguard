@echo off
REM build_apk.bat - builds debug APK and VERIFIES the artifact really exists.
REM Uses .bat to bypass PowerShell execution-policy (Restricted) blocking scripts.
setlocal EnableDelayedExpansion
set JAVA_HOME=C:\Users\ew\jdk17
set ANDROID_HOME=C:\Users\ew\AppData\Local\Android\Sdk
set GRADLE_USER_HOME=C:\Users\ew\.gradle
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d F:\KTV
set APK=F:\KTV\app\build\outputs\apk\internal\debug\app-internal-debug.apk
set DEST=F:\KTV\app-debug.apk

REM Delete stale APK first so a FAILED build can never be mistaken for success.
if exist "%APK%" del /f /q "%APK%"

REM 注: 加 productFlavors 后必须指定 flavor, assembleDebug 已不存在。
REM 内测版(internal)调试构建(含归因/调试入口):
echo [*] Building internal-debug APK ... (full log -^> F:\KTV\build_apk.log)
call gradlew.bat assembleInternalDebug --console=plain --no-daemon > build_apk.log 2>&1
REM 商店版(store)发布构建(无归因/无调试入口,合规上架):
REM call gradlew.bat assembleStoreRelease --console=plain --no-daemon > build_apk.log 2>&1
set RC=%ERRORLEVEL%
echo ============================================
if %RC%==0 (
  if exist "%APK%" (
    for %%F in ("%APK%") do (
      set SZ=%%~zF
      set TS=%%~tF
    )
    if !SZ! GTR 0 (
      copy /Y "%APK%" "%DEST%" >nul
      echo BUILD OK
      echo   APK : %APK%
      echo   copy: %DEST%   (project root - visible in IDE tree)
      echo   size: !SZ! bytes
      echo   time: !TS!
    ) else (
      echo BUILD FAILED: gradle rc=0 but APK is EMPTY
    )
  ) else (
    echo BUILD FAILED: gradle rc=0 but APK MISSING at %APK%
  )
) else (
  echo BUILD FAILED (rc=%RC%)
  echo --- Kotlin / compile errors (if any) ---
  findstr /C:"e:" build_apk.log
  echo --- Full log: F:\KTV\build_apk.log ---
)
echo ============================================
endlocal
