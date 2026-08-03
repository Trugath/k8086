@echo off
rem Launch the single-instance emulator CLI from this distribution folder.
rem Requires JDK 21+ on PATH (or JAVA_HOME set).
rem Example: run-cli.bat disks\fd.img --headless --quiet --cga-expect A:>
cd /d "%~dp0"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
  "%JAVA_HOME%\bin\java.exe" -cp "lib\*" com.trugath.k8086.MainKt %*
) else (
  java -cp "lib\*" com.trugath.k8086.MainKt %*
)
