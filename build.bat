@echo off
REM Builds a portable, dependency-free MTR4toMTR3.jar (Java 17 target).
setlocal
cd /d "%~dp0"

if not exist out mkdir out
if not exist build mkdir build
if exist build\MTR4toMTR3.jar del build\MTR4toMTR3.jar

echo [1/2] compiling...
dir /s /b src\*.java > out\sources.txt
javac -encoding UTF-8 --release 17 -d out @out\sources.txt
if errorlevel 1 goto :fail
del out\sources.txt

echo [2/2] packing...
jar --create --file build\MTR4toMTR3.jar --main-class mtrconv.Main -C out .
if errorlevel 1 goto :fail

echo.
echo Done: build\MTR4toMTR3.jar
dir build\MTR4toMTR3.jar
goto :eof

:fail
echo BUILD FAILED
exit /b 1
