@echo off
setlocal
rem Launch the single-instance emulator CLI from this distribution folder.
rem Requires JDK 21+ on PATH (or JAVA_HOME set).
rem Example (cmd.exe): run-cli.bat disks\fd.img --headless --quiet --cga-expect "A:>"
cd /d "%~dp0"
set "K8086_JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "K8086_JAVA=%JAVA_HOME%\bin\java.exe"
"%K8086_JAVA%" -cp "lib\*" com.trugath.k8086.MainKt %*
exit /b %ERRORLEVEL%
