@echo off
rem Launch the k8086 workstation from this distribution folder.
rem Requires JDK 21+ on PATH (or JAVA_HOME set).
cd /d "%~dp0"
call "%~dp0bin\k8086.bat" %*
