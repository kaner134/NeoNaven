# Naven — DLL 热注入源码（含注入器）

注入已经运行中的 Minecraft 1.21.10 NeoForge 客户端，效果与把这个 jar 放进 `mods/` 正常加载一致。

## 构建（一条命令）

```bat
build-dll.bat
```

要求：**JDK 21**（`JAVA_HOME`，或安装在标准路径）、**Visual Studio 2022 C++ 生成工具**。其余依赖由 Gradle 自动下载。

产物：

| 文件 | 说明 |
|---|---|
| `build\native\NavenLoader.exe` | 注入器 GUI（Win32，无 Qt、无动画；**内嵌 Naven.dll，单文件可注入**） |
| `build\native\Naven.dll` | 可注入 DLL，内嵌下面两个 jar |
| `build\libs\Naven.jar` | 客户端 jar |
| `build\libs\NavenAgent.jar` | 1.5KB 的 java-agent stub（只含 `tech.navenboot.AgentStub`） |

> 国内网络：`gradlew` 的发行版地址已指向腾讯云镜像；`build-dll.bat` 会用 `gradle/mirror.init.gradle`
> 把 `maven.neoforged.net` 重写到 `neoforged.forgecdn.net`。官方源可达时可去掉 `-I` 直接 `gradlew dll`。

## 用注入器（推荐）

1. `NavenLoader.exe` 是**单文件**：`Naven.dll` 在构建时以 RCDATA 嵌进 exe，运行时自动解到 `%TEMP%\naven-<pid>-<time>.dll` 再注入，
   **不需要把 dll 放在旁边**（同目录有 dll 时作为开发兜底）。
2. 先启动游戏，进到标题界面或世界。
3. 运行 `NavenLoader.exe`：640x380 无边框圆角黑窗（GDI+ 抗锯齿 + 分层窗口，边缘无锯齿），自动检测命令行里带 1.21.10/NeoForge 的 `java.exe`。
   没有任何客户端时窗口只显示居中的 NAVEN 字标；检测到客户端才出现荧光绿 INJECT 按钮。
4. 点 **INJECT**：按钮消失，出现一条细的圆角进度条（0 → 100%，依次 打开进程 → 写入内存 → 加载 DLL → 初始化客户端），
   进度以 `%TEMP%\naven.log` 里的 `bootstrap finished with code 0` 为准。
5. **注入成功后注入器会自动退出**（进度条满格停留约 0.9 秒然后关窗）。

窗口任意空白处可拖动，`Enter` 注入，`Esc` 手动退出；进程检测每 1.5 秒刷新一次。

## 用命令行注入（可选）

```powershell
powershell -ExecutionPolicy Bypass -File tools\inject.ps1 -WaitLogSeconds 10
```

日志：

- `%TEMP%\naven.log` — 原生侧：附加 JVM、解包、`Agent_OnAttach`、定位 game loader
- `%TEMP%\naven-java.log` — Java 侧：defineClass 数量、补丁 MISS、`Naven` 初始化
- `%TEMP%\naven-loader.log` — 注入器自身的调试日志（仅排查用）

注入成功的标志：`naven-java.log` 出现 `Naven Init` + `injection bootstrap finished`，且**没有 MISS 行**；
游戏里能看到水印、模块列表、通知。

## 注入链路

```
NavenLoader.exe  OpenProcess → VirtualAllocEx → WriteProcessMemory → CreateRemoteThread(LoadLibraryW)
└─ Naven.dll  DllMain → worker thread
   ├─ jvm.dll!JNI_GetCreatedJavaVMs → AttachCurrentThreadAsDaemon
   ├─ 解出 %TEMP%\naven-<pid>.jar 与 naven-agent-<pid>.jar
   ├─ instrument.dll!Agent_OnAttach("stub.jar=client.jar")   → Instrumentation
   ├─ JVMTI GetLoadedClasses 找到定义 net/minecraft/client/Minecraft 的 TransformingClassLoader
   ├─ new URLClassLoader(client.jar, parent=gameLoader) → tech.naven.inject.GameLoaderBridge
   ├─ 把 jar 里所有类 defineClass 到 game loader（handler 必须与 MC 同一 defining loader）
   └─ 渲染线程：Resources.init/registerPack → PipelineBootstrap → StencilSupport
                → Naven.modRegister() → PatchEngine.install/register/apply（retransformClasses）
```

**为什么是两个 jar**：`Agent_OnAttach` 会把 agent jar 追加到系统类加载器。若把客户端 jar 直接传进去，
`tech.naven.*` 会被系统类加载器抢先定义（父委托），那些类看不到 Minecraft，直接 `NoClassDefFoundError`。
所以只把 1.5KB 的 stub 交给 agent，客户端 jar 由 `URLClassLoader` 以 game loader 为父加载。

## 补丁引擎（为什么不是 Mixin）

JVM 热重定义**禁止增删字段/方法**，Mixin 把 handler 合并进目标类的模型在已加载类上不可行。
42 个 mixin 已改写为 `tech.naven.patch.impl.*` 的静态 handler + 注入点注解：

| 注解 | 语义 |
|---|---|
| `@Patch(X.class)` | 目标类 |
| `@Inject(method, desc?, at=@At(HEAD/RETURN/TAIL/INVOKE), cancellable)` | 方法体注入；非 void 用 `CallbackInfoReturnable<R>`（RETURN/TAIL 带原返回值） |
| `@Redirect(method, target, targetDesc, ordinal)` | 替换一次方法调用 |
| `@ModifyVariable(method, index/ordinal, at=HEAD)` | 改写参数槽 |
| `@Overwrite(method)` | 整段替换方法体 |

`@Shadow` 私有成员走 `tech.naven.patch.Reflect`（缓存 Field/Method，异常透明）；
`@Accessor/@Invoker` 变成 `patch.impl.accessors.*Accessors` 静态工具（调用点已替换）。

## 加载期等价性（mod 由 FML 完成、注入需手工复现）

| 项 | 做法 |
|---|---|
| jar 内 assets | `Resources` 直接读 jar → 解包目录，并向 `MultiPackResourceManager#namespacedManagers` 注入 `PathPackResources`（最高优先级） |
| ShaderManager 快照 | 资源包在 reload 之后注入，必须反射重建 `ShaderManager.Configs`（prepare+apply），否则 MotionBlur 拿不到 post_effect |
| 主渲染目标 stencil | 反射写 `RenderTarget#useStencil` + `resize` 重建 |
| RenderPipelines | 反射写 `PIPELINES_BY_LOCATION` + `precompilePipeline` |
| 客户端构造 | `Naven.modRegister()`，且必须在补丁生效**之前**完成（否则其它线程先命中 handler 看到 null 单例） |

## 源码结构

```
native/loader/            注入器（Win32 + GDI，WMI 枚举进程，远程 LoadLibraryW）
native/dll/               Naven.dll（JNI + JVMTI + Agent_OnAttach）
src/main/java/tech/naven/patch/engine/   运行时补丁引擎
src/main/java/tech/naven/patch/impl/     31 个 patch + 13 个 accessor 工具类
src/main/java/tech/naven/inject/         桥接、Resources、StencilSupport、PipelineBootstrap
src/main/java/tech/navenboot/AgentStub.java
tools/                    命令行注入 / 截图 / 按键脚本
```

## 已知限制

- 仅 Windows x64 / MSVC；DLL 路径需 ASCII（LoadLibraryA）。
- 注入不可逆；重建 DLL 前需关闭占用它的游戏（或 `ren build\native\Naven.dll Naven.dll.old`）。
- 热重定义以“初始字节码”为准重算目标类：与其他会改同一批 MC 类的 mod 同用时会被本次 retransform 覆盖。
- 注入期有一次 ShaderManager 全量重编译（几百毫秒卡顿），属预期。
- `StencilSupport` 重建主渲染目标时沿用重建前的尺寸，窗口尺寸与桌面分辨率不一致时画面边缘可能出现条状溢出。
- 注入器需要与游戏同等级权限；游戏以管理员运行时，注入器也要以管理员运行。