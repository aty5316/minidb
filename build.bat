@echo off
cd /d "%~dp0"
if not exist out mkdir out
(for /r src %%f in (*.java) do @echo "%%f") > sources.txt
javac -encoding UTF-8 -source 8 -target 8 -Xlint:all -d out @sources.txt
if errorlevel 1 exit /b 1
if not exist dist mkdir dist
jar cfe dist/minidb.jar ru.lab.minidb.Main -C out ru/lab/minidb
if errorlevel 1 exit /b 1
