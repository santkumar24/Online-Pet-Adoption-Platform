@echo off
cd /d "%~dp0"
if not exist "lib\mysql-connector-j-26.7.0.jar" (
 echo ERROR: Copy mysql-connector-j-26.7.0.jar into the lib folder.
 pause
 exit /b 1
)
if not exist out mkdir out
javac -cp "lib\*" -d out src\*.java
if errorlevel 1 (echo Compilation failed.& pause & exit /b 1)
java -cp "out;lib\*" Main
pause
