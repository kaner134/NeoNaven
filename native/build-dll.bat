@echo off
rem ---------------------------------------------------------------------------
rem One-command build for the DLL hot-injection form of Naven.
rem
rem   build-dll.bat
rem
rem Produces:
rem   build\libs\Naven.jar          client jar (also loadable as a plain mod)
rem   build\libs\NavenAgent.jar     minimal java-agent stub
rem   build\native\Naven.dll        injectable DLL with both jars embedded
rem
rem Requires: JDK 21 (JAVA_HOME or a standard install) and Visual Studio 2022
rem           C++ build tools. Everything else is fetched by Gradle.
rem ---------------------------------------------------------------------------
setlocal EnableExtensions

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

if not defined JAVA_HOME (
    for %%D in (
        "%ProgramFiles%\Java\jdk-21"
        "%ProgramFiles%\Eclipse Adoptium\jdk-21"
        "%ProgramFiles%\Microsoft\jdk-21"
        "D:\OpenZen-master\tools\jdk-21.0.2"
        "D:\Java21\jdk-21.0.2"
    ) do (
        if not defined JAVA_HOME if exist "%%~D\bin\javac.exe" set "JAVA_HOME=%%~D"
    )
)
if not defined JAVA_HOME (
    echo [naven] ERROR: JDK 21 not found. Install one or set JAVA_HOME.
    exit /b 1
)
echo [naven] JAVA_HOME=%JAVA_HOME%

call "%ROOT%\gradlew.bat" --console=plain -I "%ROOT%\gradle\mirror.init.gradle" dll
if errorlevel 1 (
    echo [naven] BUILD FAILED
    exit /b 1
)

echo.
echo [naven] artifacts:
dir /b "%ROOT%\build\libs\*.jar" 2>nul
echo [naven]   %ROOT%\build\native\Naven.dll
echo [naven]   %ROOT%\build\native\NavenLoader.exe
exit /b 0