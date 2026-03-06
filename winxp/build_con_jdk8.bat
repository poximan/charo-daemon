@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "JAVA_HOME=%SCRIPT_DIR%java8"
set "WRAPPER_DIST_DIR=%USERPROFILE%\.gradle\wrapper\dists\gradle-6.9.4-bin\5a0lguq60r1skx3y8d5qdl7d9"
set "WRAPPER_ZIP=%WRAPPER_DIST_DIR%\gradle-6.9.4-bin.zip"
set "WRAPPER_PART=%WRAPPER_ZIP%.part"
set "WRAPPER_LCK=%WRAPPER_ZIP%.lck"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo ERROR: Falta "%JAVA_HOME%\bin\java.exe".
  exit /b 1
)

if not exist "%JAVA_HOME%\bin\javac.exe" (
  echo ERROR: Falta "%JAVA_HOME%\bin\javac.exe".
  exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

echo JAVA_HOME=%JAVA_HOME%
cd /d "%SCRIPT_DIR%"

if not exist "%WRAPPER_ZIP%" (
  if not exist "%WRAPPER_DIST_DIR%" (
    mkdir "%WRAPPER_DIST_DIR%"
  )
  if exist "%WRAPPER_PART%" del /f /q "%WRAPPER_PART%"
  if exist "%WRAPPER_LCK%" del /f /q "%WRAPPER_LCK%"

  echo Descargando gradle-6.9.4-bin.zip con PowerShell...
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-6.9.4-bin.zip' -OutFile '%WRAPPER_ZIP%'"

  if errorlevel 1 (
    echo ERROR: No se pudo descargar Gradle 6.9.4.
    exit /b 1
  )
)

if "%~1"=="" (
  call "%SCRIPT_DIR%gradlew.bat" clean build :daemon-app:installDist
) else (
  call "%SCRIPT_DIR%gradlew.bat" %*
)

exit /b %ERRORLEVEL%
