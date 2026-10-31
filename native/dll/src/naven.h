#pragma once

#include <windows.h>
#include <jni.h>
#include <jvmti.h>
#include <string>

namespace naven {

// HMODULE of Naven.dll, captured in DllMain. The PE resource walker needs the
// image base rather than FindResource.
extern HMODULE g_self_module;

namespace log {
    void init();
    void info(const char* fmt, ...);
    void error(const char* fmt, ...);
}

namespace jar {
    // Extract IDR_NAVEN_JAR (the full client jar) to %TEMP%\naven-<pid>.jar.
    bool extract_client(std::wstring& out_path);

    // Extract IDR_NAVEN_AGENT_JAR (the minimal agent stub) to
    // %TEMP%\naven-agent-<pid>.jar.
    bool extract_agent(std::wstring& out_path);
}

namespace jvm {
    // Locate the JavaVM created in this process (jvm.dll!JNI_GetCreatedJavaVMs).
    // Returns nullptr when the DLL was injected into a non-Java process.
    JavaVM* find_vm();

    // Call Agent_OnAttach from the JDK's instrument.dll.
    //
    // agent_jar is the *stub* jar - Agent_OnAttach appends it to the system class
    // loader, so it must not contain the client classes (they have to be defined
    // by the NeoForge game loader). agent_args carries the path of the real
    // client jar, which the stub exposes via AgentStub.getClientJarPath().
    jint attach_instrument(JavaVM* vm, const std::wstring& agent_jar,
                           const std::wstring& agent_args);
}

namespace classes {
    // Walk loaded classes with JVMTI and return the class loader that defined
    // net.minecraft.client.Minecraft (the NeoForge game loader). The returned
    // local reference is owned by the caller.
    jobject find_game_class_loader(JavaVM* vm, JNIEnv* env);

    // new URLClassLoader(jar, parent=game_loader) -> loadClass(bridge FQCN).
    jclass load_bridge(JNIEnv* env, jobject game_loader,
                       const std::wstring& jar_path);
}

} // namespace naven
