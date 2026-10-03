@echo off
setlocal
if not defined JAVA_HOME set "JAVA_HOME=D:\WSN2005\Android\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "BT=D:\WSN2005\Android1\build-tools\36.0.0"
echo === badging: %~nx1 ===
"%BT%\aapt2.exe" dump badging "%~1" | findstr /b "package: application-label: launchable-activity:"
echo === signer: %~nx1 ===
call "%BT%\apksigner.bat" verify --print-certs "%~1" | findstr /i "certificate DN certificate SHA-256"
