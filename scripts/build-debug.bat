@echo off
setlocal
rem 若环境里已有 JAVA_HOME 就用它，否则回落到本机 Android Studio 自带的 JBR
if not defined JAVA_HOME set "JAVA_HOME=D:\WSN2005\Android\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0.."
if "%~1"=="" (
  call gradlew.bat :app:assembleDebug --stacktrace
) else (
  call gradlew.bat %*
)
exit /b %ERRORLEVEL%
