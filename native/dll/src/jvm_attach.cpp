#include "naven.h"

#include <vector>

namespace naven::jvm {

namespace {
    // Signature of Agent_OnAttach exported by the JDK's instrument.dll.
    using Agent_OnAttach_t = jint (JNICALL*)(JavaVM* vm, char* options, void* reserved);

    bool find_instrument_dll(std::wstring& out_path) {
        // instrument.dll lives next to java.dll/jvm.dll. Locate the module that
        // is already loaded and swap the file name.
        HMODULE javaDll = GetModuleHandleW(L"java.dll");
        if (!javaDll) {
            log::error("java.dll is not loaded in this process");
            return false;
        }
        wchar_t path[MAX_PATH];
        DWORD n = GetModuleFileNameW(javaDll, path, MAX_PATH);
        if (n == 0 || n >= MAX_PATH) {
            log::error("GetModuleFileNameW(java.dll) failed: %lu", GetLastError());
            return false;
        }
        wchar_t* slash = wcsrchr(path, L'\\');
        if (!slash) {
            log::error("Unexpected java.dll path: %ls", path);
            return false;
        }
        slash[1] = L'\0';
        out_path.assign(path);
        out_path.append(L"instrument.dll");
        return true;
    }
}

JavaVM* find_vm() {
    // jvm.dll is already loaded in the target, so we resolve the entry point at
    // runtime instead of linking jvm.lib against one specific JDK.
    HMODULE jvm_dll = GetModuleHandleW(L"jvm.dll");
    if (!jvm_dll) {
        log::error("jvm.dll is not loaded in the current process (not a Java process?)");
        return nullptr;
    }
    using JNI_GetCreatedJavaVMs_t = jint (JNICALL*)(JavaVM**, jsize, jsize*);
    auto fn = reinterpret_cast<JNI_GetCreatedJavaVMs_t>(
        GetProcAddress(jvm_dll, "JNI_GetCreatedJavaVMs"));
    if (!fn) {
        log::error("GetProcAddress(JNI_GetCreatedJavaVMs) failed: %lu", GetLastError());
        return nullptr;
    }
    JavaVM* vm = nullptr;
    jsize count = 0;
    jint rc = fn(&vm, 1, &count);
    if (rc != JNI_OK || count < 1 || !vm) {
        log::error("JNI_GetCreatedJavaVMs rc=%d count=%d", (int)rc, (int)count);
        return nullptr;
    }
    return vm;
}

jint attach_instrument(JavaVM* vm, const std::wstring& agent_jar,
                       const std::wstring& agent_args) {
    std::wstring instrument_path;
    if (!find_instrument_dll(instrument_path)) return -1;

    log::info("Loading %ls", instrument_path.c_str());
    HMODULE inst = LoadLibraryW(instrument_path.c_str());
    if (!inst) {
        log::error("LoadLibraryW(instrument.dll) failed: %lu", GetLastError());
        return -1;
    }

    auto fn = reinterpret_cast<Agent_OnAttach_t>(GetProcAddress(inst, "Agent_OnAttach"));
    if (!fn) {
        log::error("GetProcAddress(Agent_OnAttach) failed: %lu", GetLastError());
        return -1;
    }

    // OpenJDK's instrument.dll parses the options string like -javaagent does:
    // <jar path>[=<args>], in the system code page. The args carry the real
    // client jar path for AgentStub.agentmain.
    std::wstring combined = agent_jar;
    if (!agent_args.empty()) {
        combined.append(L"=");
        combined.append(agent_args);
    }

    int needed = WideCharToMultiByte(CP_ACP, 0, combined.c_str(), -1,
                                      nullptr, 0, nullptr, nullptr);
    if (needed <= 0) {
        log::error("WideCharToMultiByte sizing failed: %lu", GetLastError());
        return -1;
    }
    std::vector<char> options(needed);
    WideCharToMultiByte(CP_ACP, 0, combined.c_str(), -1,
                         options.data(), needed, nullptr, nullptr);

    log::info("Calling Agent_OnAttach with options=%s", options.data());
    jint rc = fn(vm, options.data(), nullptr);
    log::info("Agent_OnAttach returned %d", (int)rc);
    return rc;
}

} // namespace naven::jvm