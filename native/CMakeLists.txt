cmake_minimum_required(VERSION 3.20)

# CMP0091 NEW lets us drive the MSVC runtime through MSVC_RUNTIME_LIBRARY
# instead of patching CMAKE_CXX_FLAGS_*, so the build stays predictable across
# CMake/VS versions.
cmake_policy(SET CMP0091 NEW)

project(NavenNative LANGUAGES CXX)

if(NOT WIN32)
    message(FATAL_ERROR "Naven native build only supports Windows")
endif()
if(NOT MSVC)
    message(FATAL_ERROR "Naven native build requires MSVC (cl.exe); run native/build.bat")
endif()
if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "Naven.dll must be x64: the game JVM is 64-bit and a 32-bit DLL cannot be injected")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Statically link the CRT. The DLL is injected into a long-running JVM and may
# be loaded from an arbitrary path; /MT keeps it free of vcruntime140/ucrtbase
# imports so it can never bind to a mismatched runtime in the host process.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_compile_definitions(
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)

if(MSVC)
    add_compile_options(/W3 /permissive- /utf-8 /EHsc /MP)
endif()

# ---------------------------------------------------------------------------
# JDK headers (jni.h / jvmti.h). We never link jvm.lib: JNI_GetCreatedJavaVMs
# and Agent_OnAttach are resolved dynamically at runtime (see jvm_attach.cpp).
# ---------------------------------------------------------------------------
set(NAVEN_JDK_HOME "" CACHE PATH "JDK home that contains include/jni.h")

if(NOT NAVEN_JDK_HOME AND DEFINED ENV{JAVA_HOME})
    set(NAVEN_JDK_HOME "$ENV{JAVA_HOME}")
endif()

if(NOT NAVEN_JDK_HOME)
    # Last-resort search of the known local toolchains so a fresh checkout
    # builds without extra environment setup.
    foreach(_candidate
            "D:/OpenZen-master/tools/jdk-21.0.2"
            "C:/Program Files/Java/jdk-21"
            "C:/Program Files/Eclipse Adoptium/jdk-21")
        if(EXISTS "${_candidate}/include/jni.h")
            set(NAVEN_JDK_HOME "${_candidate}")
            break()
        endif()
    endforeach()
endif()

if(NOT EXISTS "${NAVEN_JDK_HOME}/include/jni.h")
    message(FATAL_ERROR
        "jni.h not found. Set JAVA_HOME (JDK 21) or configure with "
        "-DNAVEN_JDK_HOME=<path to a JDK>.")
endif()

message(STATUS "Naven: using JDK headers from ${NAVEN_JDK_HOME}")
include_directories("${NAVEN_JDK_HOME}/include" "${NAVEN_JDK_HOME}/include/win32")

# Path of the agent jar embedded as IDR_NAVEN_JAR. The Gradle task
# stageNativeJar copies the built jar here; when it is missing the build still
# succeeds with an empty resource (see dll/CMakeLists.txt) so a native-only
# checkout is never a hard failure.
set(NAVEN_JAR_FILE "${CMAKE_CURRENT_SOURCE_DIR}/naven.jar" CACHE FILEPATH
    "Client jar embedded into Naven.dll as IDR_NAVEN_JAR")

# Minimal java-agent jar embedded as IDR_NAVEN_AGENT_JAR. It only contains
# tech.navenboot.AgentStub, which receives Instrumentation from Agent_OnAttach.
set(NAVEN_AGENT_JAR_FILE "${CMAKE_CURRENT_SOURCE_DIR}/naven-agent.jar" CACHE FILEPATH
    "Agent stub jar embedded into Naven.dll as IDR_NAVEN_AGENT_JAR")

add_subdirectory(dll)
add_subdirectory(loader)