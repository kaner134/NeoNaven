# Naven native loader

`Naven.dll` is the native half of Naven's hot-injection path for
Minecraft 1.21.10 (NeoForge, Java 21). It is loaded into an already running
`javaw.exe` and bootstraps the Java agent from inside the game process:

1. resolve `jvm.dll!JNI_GetCreatedJavaVMs` (no `jvm.lib` link, no
   `-javaagent`);
2. attach a daemon thread (`AttachCurrentThreadAsDaemon`);
3. extract the embedded `IDR_NAVEN_JAR` resource to
   `%TEMP%\naven-<pid>.jar`;
4. call `instrument.dll!Agent_OnAttach` (the DLL next to the JVM's
   `java.dll`) so the JDK's own attach machinery loads the agent -
   no `jdk.attach` module and no restart required;
5. find the class loader that defined `net.minecraft.client.Minecraft` via
   JVMTI `GetLoadedClasses` - that is the NeoForge game loader;
6. build `new URLClassLoader(naven.jar, gameLoader)`,
   `loadClass("tech.naven.inject.GameLoaderBridge")` and call its static
   `load(String jarPath, ClassLoader gameLoader)`.

Everything is logged to `%TEMP%\naven.log` (and to
`OutputDebugStringA`). Failures are logged and swallowed, and the whole
bootstrap runs inside an SEH guard - the DLL never takes the game down.

## Build

Prerequisites: Windows x64, Visual Studio 2022 (MSVC), a JDK 21 (only the
`include/jni.h` and `include/win32` headers are used; nothing is linked
against `jvm.lib`).

    native\build.bat
    native\build.bat <path\agent.jar>

The script calls
`D:\OpenZen-master\tools\vs\VC\Auxiliary\Build\vcvars64.bat`, configures with the
VS-bundled CMake (Visual Studio 17 2022 generator, x64) and builds Release,
producing:

    D:\OpenZen-master\NeoNaven-main\build\native\Naven.dll

`JAVA_HOME` is used when set; otherwise the script falls back to
`D:\OpenZen-master\tools\jdk-21.0.2`. To override the JDK, pass a
CMake cache entry:

    cmake -S native -B build\native -G "Visual Studio 17 2022" -A x64 -DNAVEN_JDK_HOME=<jdk>

### The embedded jar

`naven.jar` is embedded as the `IDR_NAVEN_JAR` RCDATA resource. The
Gradle task `stageNativeJar` copies the built agent jar to `native\naven.jar`
before the native build; the staged jar is a build artifact and is git-ignored.

If the file is missing, CMake prints a warning and still produces a working
DLL, but `IDR_NAVEN_JAR` is empty: injection then fails at steps 3 and 4 with
a clear message in `naven.log` ("PE resource IDR_NAVEN_JAR ... is missing or
empty"). This is intentional - a native-only checkout must not fail to build.

## Inject

    tools\inject.ps1                    # auto-detect the running 1.21.10 client
    tools\inject.ps1 -Pid 12345         # explicit process id
    tools\inject.ps1 -Name Minecraft    # filter javaw/java candidates
    tools\inject.ps1 -DryRun            # show selection and scores, do not inject
    tools\inject.ps1 -Pid 12345 -Dll <path>\Naven.dll -WaitLogSeconds 10

The injector uses `OpenProcess` + `VirtualAllocEx` + `WriteProcessMemory`
+ `CreateRemoteThread(LoadLibraryA)`, so the DLL must be a real file on disk
(not a manual-mapped image). Injection needs the same or higher privileges as
the game process - start PowerShell as administrator if the game runs elevated.

Without `-Pid`, every `javaw` and `java` process is scored by its command
line (1.21.10 and NeoForge markers score high, Gradle daemons and legacy 1.12.2
launchers score low). If the best candidate does not look like a Minecraft
client, the script refuses to inject unless `-Force` is given.

After injecting, the script tails `%TEMP%\naven.log` so the bootstrap
result is visible immediately.

## Layout

    native/
      CMakeLists.txt          top-level project, x64/MSVC checks, JDK header discovery
      build.bat               vcvars64 + VS CMake, Release -> build/native/Naven.dll
      naven.jar               staged agent jar (gradle stageNativeJar, git-ignored)
      dll/
        CMakeLists.txt        Naven target, jar staging, output directory
        res/naven.rc          IDR_NAVEN_JAR RCDATA "naven.jar"
        res/resource.h
        src/naven.h
        src/main.cpp          DllMain, bootstrap thread, SEH guard, bridge invocation
        src/jvm_attach.cpp    JNI_GetCreatedJavaVMs + instrument.dll!Agent_OnAttach
        src/jar_extract.cpp   PE resource walk + %TEMP% extraction
        src/class_loader.cpp  JVMTI class search + URLClassLoader bridge load
        src/log.cpp           %TEMP%\naven.log + OutputDebugStringA
        src/generated_names.h NAVEN_BRIDGE_FQCN constant
    tools/
      inject.ps1              remote LoadLibraryA injector

## Known limitations

- x64 only (MSVC, 64-bit JVM); the injector rejects a 32-bit (WOW64) target.
- Windows only; uses Win32 APIs and SEH.
- The DLL path must be ASCII because the injector calls `LoadLibraryA`.
- Only the first JavaVM in the process is used (`JNI_GetCreatedJavaVMs` with
  one slot).
- The bridge class name is hard-coded as `tech.naven.inject.GameLoaderBridge`
  (see `generated_names.h`); renaming or obfuscating the Java side requires
  rebuilding the DLL.
- `%TEMP%\naven-<pid>.jar` stays on disk for the life of the process (the
  URLClassLoader keeps reading it) and is overwritten on the next injection
  into the same PID.
- Injection is not reversible through the supported path: there is no JVMTI
  teardown. While a game has `Naven.dll` loaded the file is locked, so
  `build.bat` fails with `LNK1104`; Windows still allows renaming a loaded
  image, so `ren build\native\Naven.dll Naven.dll.old` unblocks the rebuild.
- If `%TEMP%\naven.log` is locked at load time the DLL retries for about one
  second and then falls back to `%TEMP%\naven-<pid>.log`.
- Auto-detection is a heuristic: on a machine with several Minecraft JVMs, pass
  `-Pid` explicitly.
