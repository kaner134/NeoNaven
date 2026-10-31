#include "naven.h"
#include "generated_names.h"  // NAVEN_BRIDGE_FQCN

#include <cstring>

namespace naven::classes {

namespace {
    bool check_and_clear(JNIEnv* env, const char* where) {
        if (env->ExceptionCheck()) {
            log::error("JNI exception at %s", where);
            env->ExceptionDescribe();
            env->ExceptionClear();
            return true;
        }
        return false;
    }

    jvmtiEnv* get_jvmti(JavaVM* vm) {
        jvmtiEnv* jvmti = nullptr;
        if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK
                || !jvmti) {
            log::error("GetEnv(JVMTI_VERSION_1_2) failed");
            return nullptr;
        }
        return jvmti;
    }

    jobject class_loader_of(JNIEnv* env, jclass cls) {
        jclass classCls = env->FindClass("java/lang/Class");
        if (!classCls) return nullptr;
        jmethodID mid = env->GetMethodID(classCls, "getClassLoader",
                                         "()Ljava/lang/ClassLoader;");
        if (!mid) {
            env->DeleteLocalRef(classCls);
            return nullptr;
        }
        jobject loader = env->CallObjectMethod(cls, mid);
        env->DeleteLocalRef(classCls);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }
        return loader;
    }
}

jobject find_game_class_loader(JavaVM* vm, JNIEnv* env) {
    jvmtiEnv* jvmti = get_jvmti(vm);
    if (!jvmti) return nullptr;

    jint count = 0;
    jclass* classes = nullptr;
    jvmtiError rc = jvmti->GetLoadedClasses(&count, &classes);
    if (rc != JVMTI_ERROR_NONE || !classes) {
        log::error("GetLoadedClasses failed: %d", (int)rc);
        return nullptr;
    }
    log::info("GetLoadedClasses returned %d classes", (int)count);

    jobject game_loader = nullptr;
    const char* needle = "Lnet/minecraft/client/Minecraft;";
    int candidates = 0;

    for (jint i = 0; i < count; ++i) {
        char* sig = nullptr;
        if (jvmti->GetClassSignature(classes[i], &sig, nullptr) != JVMTI_ERROR_NONE) {
            continue;
        }
        if (sig) {
            if (!game_loader && std::strcmp(sig, needle) == 0) {
                log::info("Matched class signature %s", sig);
                game_loader = class_loader_of(env, classes[i]);
                if (!game_loader) {
                    log::error("Minecraft has no class loader (bootstrap loader?)");
                }
            } else if (game_loader == nullptr && candidates < 8
                       && std::strstr(sig, "minecraft/client/Minecraft") != nullptr) {
                // Diagnostic breadcrumb for unexpected runtimes (mappings,
                // class name drift between Minecraft versions).
                log::info("  related class: %s", sig);
                ++candidates;
            }
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));
        }
    }

    jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));

    if (!game_loader) {
        log::error("net/minecraft/client/Minecraft was not found among %d loaded classes",
                   (int)count);
        return nullptr;
    }
    return game_loader;
}

jclass load_bridge(JNIEnv* env, jobject game_loader,
                   const std::wstring& jar_path) {
    // java.io.File(jar_path)
    jclass fileCls = env->FindClass("java/io/File");
    if (!fileCls) { check_and_clear(env, "FindClass File"); return nullptr; }
    jmethodID fileCtor = env->GetMethodID(fileCls, "<init>", "(Ljava/lang/String;)V");
    if (!fileCtor) { check_and_clear(env, "GetMethodID File.<init>"); return nullptr; }

    jstring jarStr = env->NewString(
        reinterpret_cast<const jchar*>(jar_path.c_str()),
        static_cast<jsize>(jar_path.size()));
    jobject file = env->NewObject(fileCls, fileCtor, jarStr);
    if (check_and_clear(env, "new File")) return nullptr;

    // file.toURI().toURL()
    jmethodID toURI = env->GetMethodID(fileCls, "toURI", "()Ljava/net/URI;");
    if (!toURI) { check_and_clear(env, "GetMethodID File.toURI"); return nullptr; }
    jobject uri = env->CallObjectMethod(file, toURI);
    if (check_and_clear(env, "File.toURI")) return nullptr;

    jclass uriCls = env->FindClass("java/net/URI");
    if (!uriCls) { check_and_clear(env, "FindClass URI"); return nullptr; }
    jmethodID toURL = env->GetMethodID(uriCls, "toURL", "()Ljava/net/URL;");
    if (!toURL) { check_and_clear(env, "GetMethodID URI.toURL"); return nullptr; }
    jobject url = env->CallObjectMethod(uri, toURL);
    if (check_and_clear(env, "URI.toURL")) return nullptr;

    // URL[] urls = { url }
    jclass urlCls = env->FindClass("java/net/URL");
    if (!urlCls) { check_and_clear(env, "FindClass URL"); return nullptr; }
    jobjectArray urls = env->NewObjectArray(1, urlCls, url);
    if (check_and_clear(env, "NewObjectArray URL[]")) return nullptr;

    // new URLClassLoader(urls, parent = game loader)
    jclass urlclCls = env->FindClass("java/net/URLClassLoader");
    if (!urlclCls) { check_and_clear(env, "FindClass URLClassLoader"); return nullptr; }
    jmethodID urlclCtor = env->GetMethodID(urlclCls, "<init>",
        "([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
    if (!urlclCtor) {
        check_and_clear(env, "GetMethodID URLClassLoader.<init>");
        return nullptr;
    }
    jobject urlcl = env->NewObject(urlclCls, urlclCtor, urls, game_loader);
    if (check_and_clear(env, "new URLClassLoader")) return nullptr;
    log::info("URLClassLoader constructed with the game loader as parent");

    // urlcl.loadClass(NAVEN_BRIDGE_FQCN)
    jclass classLoaderCls = env->FindClass("java/lang/ClassLoader");
    if (!classLoaderCls) {
        check_and_clear(env, "FindClass ClassLoader");
        return nullptr;
    }
    jmethodID loadClass = env->GetMethodID(classLoaderCls, "loadClass",
        "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!loadClass) {
        check_and_clear(env, "GetMethodID ClassLoader.loadClass");
        return nullptr;
    }
    jstring name = env->NewStringUTF(NAVEN_BRIDGE_FQCN);
    jobject loaded = env->CallObjectMethod(urlcl, loadClass, name);
    if (check_and_clear(env, "URLClassLoader.loadClass(" NAVEN_BRIDGE_FQCN ")")) {
        return nullptr;
    }
    if (!loaded) {
        log::error("loadClass returned null for " NAVEN_BRIDGE_FQCN);
        return nullptr;
    }
    log::info("Loaded " NAVEN_BRIDGE_FQCN " through the URLClassLoader");
    return static_cast<jclass>(loaded);
}

} // namespace naven::classes
