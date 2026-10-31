#include "naven.h"

#include <atomic>

namespace naven {
    HMODULE g_self_module = nullptr;
}

namespace {

// LoadLibrary() may be called twice (two injectors, retries, ...). Only the
// first DLL_PROCESS_ATTACH runs the bootstrap.
std::atomic<bool> g_bootstrap_started{false};

// All JNI/Java work happens here, in a frame without local objects that would
// need unwinding: MSVC rejects __try in a function that requires C++ object
// unwinding (/EHsc -> C2712), so the SEH guard lives in inject_thread() and the
// worker body stays separate.
DWORD run_bootstrap() {
    using namespace naven;

    JavaVM* vm = jvm::find_vm();
    if (!vm) return 1;

    JNIEnv* env = nullptr;
    JavaVMAttachArgs args{};
    args.version = JNI_VERSION_1_8;
    args.name = const_cast<char*>("Naven-Bootstrap");
    args.group = nullptr;
    if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) != JNI_OK
            || !env) {
        log::error("AttachCurrentThreadAsDaemon failed");
        return 2;
    }
    log::info("Attached the bootstrap thread to the JavaVM");

    std::wstring jar_path;
    if (!jar::extract_client(jar_path)) {
        vm->DetachCurrentThread();
        return 3;
    }

    // The agent jar must contain nothing but the stub: Agent_OnAttach appends it
    // to the system class loader, and the client classes have to be defined by
    // the NeoForge game loader instead (parent-first delegation would otherwise
    // resolve tech.naven.* against a loader that cannot see Minecraft).
    std::wstring agent_path;
    if (!jar::extract_agent(agent_path)) {
        vm->DetachCurrentThread();
        return 31;
    }

    jint rc = jvm::attach_instrument(vm, agent_path, jar_path);
    if (rc != 0) {
        // A few JDK builds return non-zero from Agent_OnAttach even when the
        // agent's agentmain() ran. Keep going; the bridge call below is the
        // real test.
        log::error("Agent_OnAttach reported %d (continuing anyway)", (int)rc);
    }

    jobject game_loader = classes::find_game_class_loader(vm, env);
    if (!game_loader) {
        vm->DetachCurrentThread();
        return 4;
    }

    jclass bridge = classes::load_bridge(env, game_loader, jar_path);
    if (!bridge) {
        env->DeleteLocalRef(game_loader);
        vm->DetachCurrentThread();
        return 5;
    }

    jmethodID load_mid = env->GetStaticMethodID(bridge, "load",
            "(Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (!load_mid) {
        log::error("GameLoaderBridge.load(String, ClassLoader) not found");
        env->ExceptionClear();
        env->DeleteLocalRef(bridge);
        env->DeleteLocalRef(game_loader);
        vm->DetachCurrentThread();
        return 6;
    }

    jstring jar_jstr = env->NewString(
            reinterpret_cast<const jchar*>(jar_path.c_str()),
            static_cast<jsize>(jar_path.size()));

    env->CallStaticVoidMethod(bridge, load_mid, jar_jstr, game_loader);
    if (env->ExceptionCheck()) {
        log::error("GameLoaderBridge.load threw; see the Java stack trace below");
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else {
        log::info("GameLoaderBridge.load completed");
    }

    env->DeleteLocalRef(jar_jstr);
    env->DeleteLocalRef(bridge);
    env->DeleteLocalRef(game_loader);
    vm->DetachCurrentThread();
    return 0;
}

DWORD WINAPI inject_thread(LPVOID) {
    naven::log::init();
    naven::log::info("Naven.dll bootstrap thread started, pid=%lu",
                     GetCurrentProcessId());

    DWORD rc = 0;
    __try {
        rc = run_bootstrap();
    }
    __except (EXCEPTION_EXECUTE_HANDLER) {
        // A hard fault (bad pointer, corrupted JVM state, ...) must never take
        // the game down with it.
        naven::log::error("Unhandled exception 0x%08lX during bootstrap; aborting",
                          (unsigned long)GetExceptionCode());
        rc = 99;
    }

    naven::log::info("Naven bootstrap finished with code %lu", (unsigned long)rc);
    return rc;
}

} // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        bool expected = false;
        if (!g_bootstrap_started.compare_exchange_strong(expected, true)) {
            return TRUE;
        }
        naven::g_self_module = module;
        DisableThreadLibraryCalls(module);

        // Never call JNI from DllMain: the loader lock is held and touching
        // the JVM here can deadlock the whole process. Hand off to a worker.
        HANDLE thread = CreateThread(nullptr, 0, inject_thread, nullptr, 0, nullptr);
        if (thread) {
            CloseHandle(thread);
        } else {
            naven::log::init();
            naven::log::error("CreateThread failed: %lu", GetLastError());
        }
    }
    return TRUE;
}