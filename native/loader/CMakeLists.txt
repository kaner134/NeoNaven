# Minimal Win32 injector UI (no Qt, no animations).
# The Naven.dll is embedded as RT_RCDATA so the injector is a single self-contained exe.

set(NAVEN_DLL_FILE "${CMAKE_BINARY_DIR}/Naven.dll")
set(NAVEN_LOADER_RC "${CMAKE_CURRENT_BINARY_DIR}/loader_res.rc")

configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/res/loader_res.rc.in"
    "${NAVEN_LOADER_RC}"
    @ONLY
)

add_executable(NavenLoader WIN32
    src/main.cpp
    ${NAVEN_LOADER_RC}
)

# The resource compiler must see a completed Naven.dll, and a rebuilt DLL must
# invalidate the resource object.
add_dependencies(NavenLoader Naven)
set_source_files_properties(${NAVEN_LOADER_RC} PROPERTIES OBJECT_DEPENDS "${NAVEN_DLL_FILE}")

target_include_directories(NavenLoader PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/res")

target_compile_definitions(NavenLoader PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)

target_link_libraries(NavenLoader PRIVATE
    dwmapi
    ole32
    oleaut32
    wbemuuid
    msimg32
)

set_target_properties(NavenLoader PROPERTIES
    OUTPUT_NAME "NavenLoader"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}"
)
