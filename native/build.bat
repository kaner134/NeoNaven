@echo off
rem ---------------------------------------------------------------------------
rem Build Naven.dll (x64, Release).
rem
rem   native\build.bat                 configure + build with the staged jars
rem Requires: Visual Studio 2022 C++ tools and a JDK 21 (JAVA_HOME).
rem Output:   <repo>\build\native\Naven.dll
rem ---------------------------------------------------------------------------
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
for %%I in ("%ROOT%\..") do set "REPO=%%~fI"
set "BUILD_DIR=%REPO%\build\native"

rem --- locate Visual Studio -------------------------------------------------
set "VSROOT="
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" (
    for /f "usebackq delims=" %%I in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSROOT=%%I"
)
if not defined VSROOT if exist "D:\OpenZen-master\tools\vs\VC\Auxiliary\Build\vcvars64.bat" set "VSROOT=D:\OpenZen-master\tools\vs"

set "VCVARS="
set "CMAKE_EXE="
if defined VSROOT (
    set "VCVARS=%VSROOT%\VC\Auxiliary\Build\vcvars64.bat"
    set "CMAKE_EXE=%VSROOT%\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
)
if not exist "%CMAKE_EXE%" set "CMAKE_EXE=cmake"
echo [naven] VSROOT=%VSROOT%
echo [naven] VCVARS=%VCVARS%
echo [naven] CMAKE_EXE=%CMAKE_EXE%

echo [naven] source : %ROOT%
echo [naven] build  : %BUILD_DIR%

if defined VCVARS if exist "%VCVARS%" (
    echo [naven] calling %VCVARS%
    call "%VCVARS%" >nul
    if errorlevel 1 (
        echo [naven] ERROR: vcvars64.bat failed
        exit /b 1
    )
) else (
    echo [naven] WARNING: vcvars64.bat not found; assuming MSVC/CMake are on PATH
)

rem --- locate a JDK ---------------------------------------------------------
if not defined JAVA_HOME (
    for %%D in (
        "D:\OpenZen-master\tools\jdk-21.0.2"
        "%ProgramFiles%\Java\jdk-21"
        "%ProgramFiles%\Eclipse Adoptium\jdk-21"
        "%ProgramFiles%\Microsoft\jdk-21"
    ) do (
        if not defined JAVA_HOME if exist "%%~D\include\jni.h" set "JAVA_HOME=%%~D"
    )
)
if defined JAVA_HOME echo [naven] JAVA_HOME=%JAVA_HOME%
if not defined JAVA_HOME echo [naven] WARNING: JAVA_HOME is not set; pass -DNAVEN_JDK_HOME=JDK_HOME

echo.
echo [naven] configuring...
"%CMAKE_EXE%" -S "%ROOT%" -B "%BUILD_DIR%" -G "Visual Studio 17 2022" -A x64
if errorlevel 1 goto :fail

echo.
echo [naven] building Release...
"%CMAKE_EXE%" --build "%BUILD_DIR%" --config Release --parallel
if errorlevel 1 goto :fail

echo.
if not exist "%BUILD_DIR%\Naven.dll" (
    echo [naven] ERROR: build reported success but "%BUILD_DIR%\Naven.dll" is missing
    exit /b 1
)

for %%F in ("%BUILD_DIR%\Naven.dll") do set "DLL_SIZE=%%~zF"
echo [naven] OK: %BUILD_DIR%\Naven.dll  (!DLL_SIZE! bytes)
exit /b 0

:fail
echo.
echo [naven] BUILD FAILED
exit /b 1