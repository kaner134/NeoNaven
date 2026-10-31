# The agent jar that gets embedded as the IDR_NAVEN_JAR RCDATA resource.
# rc.exe resolves the file name relative to its include path, so we stage a copy
# into the binary dir and point the resource compiler there.
set(NAVEN_JAR_SOURCE "${NAVEN_JAR_FILE}")
set(NAVEN_JAR_STAGED "${CMAKE_CURRENT_BINARY_DIR}/naven.jar")
set(NAVEN_AGENT_JAR_SOURCE "${NAVEN_AGENT_JAR_FILE}")
set(NAVEN_AGENT_JAR_STAGED "${CMAKE_CURRENT_BINARY_DIR}/naven-agent.jar")

if(NOT EXISTS "${NAVEN_JAR_SOURCE}")
    message(WARNING
        "naven.jar not found at ${NAVEN_JAR_SOURCE}; "
        "run the Gradle 'stageNativeJar' task first. "
        "Naven.dll will be built, but IDR_NAVEN_JAR will be empty, so a real "
        "injection cannot load the client.")
    file(WRITE "${NAVEN_JAR_STAGED}" "")
else()
    configure_file("${NAVEN_JAR_SOURCE}" "${NAVEN_JAR_STAGED}" COPYONLY)
endif()

if(NOT EXISTS "${NAVEN_AGENT_JAR_SOURCE}")
    message(WARNING
        "naven-agent.jar (agent stub) not found at ${NAVEN_AGENT_JAR_SOURCE}; "
        "run the Gradle 'stageNativeJar' task first.")
    file(WRITE "${NAVEN_AGENT_JAR_STAGED}" "")
else()
    configure_file("${NAVEN_AGENT_JAR_SOURCE}" "${NAVEN_AGENT_JAR_STAGED}" COPYONLY)
endif()

add_library(Naven SHARED
    src/main.cpp
    src/jvm_attach.cpp
    src/jar_extract.cpp
    src/class_loader.cpp
    src/log.cpp
    res/naven.rc
)

target_include_directories(Naven PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${CMAKE_CURRENT_SOURCE_DIR}/res"
)

set_source_files_properties(res/naven.rc PROPERTIES
    COMPILE_FLAGS "/I\"${CMAKE_CURRENT_BINARY_DIR}\""
)

target_compile_definitions(Naven PRIVATE
    NAVEN_DLL_EXPORTS
)

# Always drop Naven.dll directly in the build root (build/native/Naven.dll)
# instead of a per-configuration subdirectory.
set_target_properties(Naven PROPERTIES
    OUTPUT_NAME "Naven"
    PREFIX ""
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}"
    PDB_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)

# We deliberately do not link jvm.lib / java.lib: jvm.dll is already loaded in
# the target process and every entry point (JNI_GetCreatedJavaVMs,
# Agent_OnAttach, JNI functions through the vtable) is resolved dynamically.