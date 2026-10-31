# NeoNaven

> 出处 / Origin：**[Huty1337/NeoNaven](https://github.com/Huty1337/NeoNaven)**
> 本仓库是它的 **DLL 热注入形态**（在原客户端基础上把 Mixin 启动路径改造成运行时字节码补丁 + 注入器）。

A Client for Minecraft NeoForge 1.21.10 — 现在可以注入到**已经运行中**的客户端，效果与把它放进 `mods/` 正常加载一致。

---

## 这是什么

原版客户端依赖 Forge/Mixin 在**启动时**改写 Minecraft 类。本分支把它改造成：

1. 一个可注入的 DLL（`Naven.dll`，内含客户端 jar 和一个 1.5KB 的 agent stub）；
2. 一个注入器（`NavenLoader.exe`，**单文件**，DLL 已内嵌）；
3. 一套运行时补丁引擎：注入后对**已加载**的 Minecraft 类做 `retransformClasses`，只改方法体。

为什么不能直接跑 Mixin：JVM 热重定义**禁止增删字段/方法**，而 Mixin 会把 handler、`@Unique` 字段、`@Accessor` 方法合并进目标类。
所以 42 个 mixin 被逐条改写成静态 handler + 注入点注解（见下文“补丁引擎”）。

## 功能

- 42 个 mixin → **31 个 patch 类 + 13 个 accessor 静态工具类**，注入后 0 MISS
- HUD / 水印 / 模块列表 / 通知 / ClickGUI / 自定义字体 全部正常
- 配置文件（`%APPDATA%\<HWID>\*.cfg`）读写、按键绑定、事件总线、渲染管线、stencil 均与 mod 形态一致
- 注入器：640×380 无边框圆角黑窗（GDI+ 抗锯齿 + 分层窗口），无进程时只显示居中 logo；检测到客户端才出现 INJECT 按钮；
  点击后按钮消失、出现圆角荧光绿进度条，**注入成功后自动退出**

## 目录结构

```
native/
  dll/                 Naven.dll：JNI + JVMTI + instrument.dll!Agent_OnAttach
    src/main.cpp       DllMain → 工作线程 → 附加 JVM → 解包 jar → 定位 game loader → 调桥
    src/jvm_attach.cpp JNI_GetCreatedJavaVMs / Agent_OnAttach（动态解析，不链 jvm.lib）
    src/jar_extract.cpp 手工遍历 PE 资源，解出 client jar / agent stub（每次唯一文件名）
    src/class_loader.cpp JVMTI GetLoadedClasses → 找 net.minecraft.client.Minecraft 的 ClassLoader
  loader/              注入器（Win32 + GDI+，无 Qt、无动画）
    src/main.cpp       WMI 枚举 java 进程、远程 LoadLibraryW、轮询 naven.log 驱动进度条
    res/               内嵌 DLL 的 RCDATA 资源模板
src/main/java/
  tech/naven/patch/engine/   运行时补丁引擎（7 个文件，仅依赖 ASM）
  tech/naven/patch/impl/     31 个 patch + 13 个 accessor 工具类
  tech/naven/patch/Reflect.java  缓存反射（替代 @Shadow 的私有成员访问）
  tech/naven/inject/         PatchAgent、GameLoaderBridge、NavenBootstrap、Resources、StencilSupport、PipelineBootstrap
  tech/navenboot/AgentStub.java   最小 agent 入口（只负责接住 Instrumentation）
tools/
  inject.ps1           命令行注入（可选，注入器之外的备用方案）
  screenshot.ps1 / sendkey.ps1 / click.ps1   实机验证脚本
build-dll.bat          一键构建（jar + agent stub + DLL + 注入器）
gradle/mirror.init.gradle  国内镜像重写（maven.neoforged.net → neoforged.forgecdn.net）
INJECT.md              注入形态的详细说明（架构 / 等价性 / 限制）
```

## 环境要求

| 组件 | 版本 / 说明 |
|---|---|
| JDK | **21**（`JAVA_HOME`，或装在标准路径：`%ProgramFiles%\Java\jdk-21` 等） |
| Visual Studio | **2022 生成工具**，需勾选 “使用 C++ 的桌面开发”（提供 MSVC + CMake） |
| 网络 | 首次构建需要下载 Gradle 依赖；国内可直接用（已配镜像） |

## 构建

### 一键（推荐）

```bat
build-dll.bat
```

产物：

| 文件 | 大小 | 说明 |
|---|---|---|
| `build\native\NavenLoader.exe` | ≈9.4 MB | **单文件注入器**，已内嵌 `Naven.dll` |
| `build\native\Naven.dll` | ≈9.7 MB | 可注入 DLL（同时为 exe 提供资源） |
| `build\libs\Naven.jar` | ≈9.5 MB | 客户端 jar |
| `build\libs\NavenAgent.jar` | 1.5 KB | agent stub（只含 `AgentStub`） |

> 分发时**只需要 `NavenLoader.exe`**；它运行时会把内嵌的 DLL 解到 `%TEMP%\naven-<pid>-<time>.dll` 再注入。

### 分步

```bat
gradlew.bat -I gradle\mirror.init.gradle jar stageNativeJar   :: 编译 + 把两个 jar 放到 native\
gradlew.bat -I gradle\mirror.init.gradle nativeDll            :: 调 native\build.bat 编 DLL + 注入器
:: 或者只编原生部分：
native\build.bat
```

`native\build.bat` 会用 `vswhere` 自动定位 VS，若 `JAVA_HOME` 未设置会尝试常见 JDK 路径。

### 国内网络说明

- `gradle/wrapper/gradle-wrapper.properties` 的发行版地址已指向腾讯云镜像；
- `build-dll.bat` 通过 `gradle/mirror.init.gradle` 把 `maven.neoforged.net` 重写到 `neoforged.forgecdn.net`（官方源可达时可去掉 `-I`）。

## 使用注入器

1. 把 `NavenLoader.exe` 放到任意目录（不需要 dll 在旁边）。
2. 先启动 Minecraft 1.21.10 NeoForge 客户端，进标题界面或世界。
3. 运行 `NavenLoader.exe`：
   - 没有任何客户端 → 只显示居中的 **NAVEN** 字标；
   - 检测到客户端 → 出现荧光绿 **INJECT** 按钮（自动选命令行里带 `1.21.10`/`neoforge` 的那个 java 进程）。
4. 点 **INJECT**：按钮消失，出现一条细的圆角进度条（打开进程 → 写入内存 → 加载 DLL → 初始化客户端）。
5. 进度以 `%TEMP%\naven.log` 里的 `bootstrap finished with code 0` 为准；满格约 0.9 秒后**注入器自动退出**。

快捷键：`Enter` 注入、`Esc` 退出、空白处可拖动窗口。进程检测每 1.5 秒刷新。

### 命令行注入（备用）

```powershell
powershell -ExecutionPolicy Bypass -File tools\inject.ps1 -WaitLogSeconds 10
# 也可 -Pid <pid> 指定进程，-DryRun 只看候选
```

## 注入原理

```
NavenLoader.exe
  OpenProcess → VirtualAllocEx → WriteProcessMemory → CreateRemoteThread(LoadLibraryW)
  └─ Naven.dll  DllMain → 工作线程（不在 loader lock 里做 JNI）
     ├─ jvm.dll!JNI_GetCreatedJavaVMs → AttachCurrentThreadAsDaemon
     ├─ 解出 %TEMP%\naven-<pid>-<t>.jar 与 naven-agent-<pid>-<t>.jar
     ├─ instrument.dll!Agent_OnAttach("stub.jar=client.jar")   → Instrumentation
     ├─ JVMTI GetLoadedClasses → 找到定义 net/minecraft/client/Minecraft 的 TransformingClassLoader
     ├─ new URLClassLoader(client.jar, parent=gameLoader) → tech.naven.inject.GameLoaderBridge
     ├─ 把 jar 里所有类 defineClass 到 game loader（handler 必须与 MC 同一 defining loader）
     └─ 渲染线程：Resources.init/registerPack → PipelineBootstrap → StencilSupport
                  → Naven.modRegister() → PatchEngine.install/register/apply（retransformClasses）
```

**为什么要两个 jar**：`Agent_OnAttach` 会把 agent jar 追加到**系统类加载器**。若把客户端 jar 直接传进去，
`tech.naven.*` 会被系统类加载器抢先定义（父委托），那些类看不到 Minecraft，直接 `NoClassDefFoundError`。
所以只把 1.5KB 的 stub 交给 agent，客户端 jar 由 `URLClassLoader` 以 game loader 为父加载。

**为什么先构造客户端再打补丁**：游戏已经在跑，补丁一生效，其它线程（渲染/网络/tick）立刻会命中 handler；
必须先 `Naven.modRegister()` 把单例建好，否则 handler 里 `Naven.getInstance()` 是 null。

## 补丁引擎

| 注解 | 语义 |
|---|---|
| `@Patch(X.class)` | 目标类 |
| `@Inject(method, desc?, at=@At(HEAD/RETURN/TAIL/INVOKE), cancellable)` | 方法体注入；非 void 用 `CallbackInfoReturnable<R>`（RETURN/TAIL 会带上原返回值） |
| `@Redirect(method, target, targetDesc, ordinal)` | 按 owner+desc+ordinal 替换一次方法调用 |
| `@ModifyVariable(method, index/ordinal, at=HEAD)` | 改写参数槽 |
| `@Overwrite(method)` | 整段替换方法体 |

- `@Shadow` 的私有成员走 `tech.naven.patch.Reflect`（缓存 Field/Method，被调方法抛的异常原样透传）；
- `@Accessor/@Invoker` 变成 `patch.impl.accessors.*Accessors` 的静态工具，调用点已同步替换；
- 帧计算用 `COMPUTE_FRAMES|COMPUTE_MAXS`，`getCommonSuperClass` 走目标类的 ClassLoader（否则解析不到 MC 类）。

引擎自带离线/实机回归：`offline 62/62`、`live（真实 retransform）48/48`。

## 加载期等价性

| 项 | 原 mod（FML） | 注入形态 |
|---|---|---|
| jar 内 assets | FML 注册 mod 资源包 | `Resources` 读 jar + 向 `MultiPackResourceManager#namespacedManagers` 注入 `PathPackResources`（最高优先级） |
| ShaderManager 快照 | reload 时生成 | 反射重建 `ShaderManager.Configs`（prepare+apply），否则 MotionBlur 拿不到 post_effect |
| 主渲染目标 stencil | `ConfigureMainRenderTargetEvent#enableStencil` | 反射写 `RenderTarget#useStencil` + `resize` 重建 |
| RenderPipelines | mod bus 注册 | 反射写 `RenderPipelines.PIPELINES_BY_LOCATION` + `precompilePipeline` |
| 客户端构造 | `Minecraft.<init>` 的 mixin 调 `Naven.modRegister()` | 注入时在补丁生效前直接调用 |

## 配置文件位置

配置写在 **C 盘用户目录**，和 OpenZen 的 `~/.zen` 一致：

```
C:\Users\<用户名>\.naven\
  modules.cfg  values.cfg  clickgui.cfg  friend.cfg
  killsays.cfg spammers.cfg proxy.cfg
```

首次启动时如果检测到旧版 `%APPDATA%\<磁盘序列号哈希>\` 目录，会自动把 `*.cfg` 迁移过来。

## 修复记录（相对上游 mod 形态）

- **注入点按方法名匹配**：引擎原来只按 `owner + descriptor + ordinal` 定位调用，遇到同一类里多个 `()Z` 方法就会钩错。
  `@At` 增加 `name()`、`@Redirect` 增加 `targetName()`，全部 patch 改成显式方法名。
  典型受害点：`LocalPlayer.aiStep` 里的 `isUsingItem()` 被误当成 `isPassenger()`（导致移动/减速逻辑错乱）。
- **KillAura 攻击距离**：原实现依赖 `mc.hitResult`，而原版 pick 只到实体交互距离（≈3 格），导致"贴身才打"。
  现在直接对选中的目标攻击，判定用眼睛到碰撞箱最近点的距离 ≤ **3.0 格** + 视线检测。
- **RotationManager 空指针**：`RotationUtils` 在首次 tick / 重生后读取 `lastRotations` 没有判空，启用 KillAura 会刷 NPE，已加兜底。
- 注入器：DLL 内嵌为 RCDATA，单文件即可注入；完成后自动退出。
- **KillAura 新增 `1.9 Cooldown` 开关**：开启后不再按 APS 累积出手，而是等原版攻击冷却
  (`getAttackStrengthScale(0.5) >= 1.0`) 充满才攻击一次；关闭时保持原来的 APS 模式。

## 日志与排错

| 文件 | 内容 |
|---|---|
| `%TEMP%\naven.log` | 原生侧：附加 JVM、解包、`Agent_OnAttach`、定位 game loader、bridge 调用结果 |
| `%TEMP%\naven-java.log` | Java 侧：defineClass 数量、补丁 MISS 列表、`Naven Init` |
| `%TEMP%\naven-loader.log` | 注入器自身（仅排查用） |

常见问题：

- **`bootstrap finished with code 3`**：解包 jar 失败（磁盘/权限/占用）。新版本每次注入使用唯一文件名，重复注入不会冲突。
- **弹“需要管理员权限”**：游戏以管理员运行时，注入器也要以管理员运行。
- **进度卡在“初始化客户端”**：看 `%TEMP%\naven.log` 与 `naven-java.log`，通常是某个 patch MISS 或渲染初始化异常。

## 已知限制

- 仅 Windows x64 / MSVC；DLL 路径需 ASCII（`LoadLibraryA`）。
- 注入不可逆；重新构建 DLL 前需要关闭占用它的游戏（或 `ren build\native\Naven.dll Naven.dll.old`）。
- 热重定义以“初始字节码”为准重算目标类：与其他修改同一批 MC 类的 mod 同用会被本次 retransform 覆盖。
- 注入期有一次 ShaderManager 全量重编译（几百毫秒卡顿），属预期。
- `StencilSupport` 重建主渲染目标时沿用重建前尺寸，窗口分辨率与桌面不一致时画面边缘可能有条状溢出。

## 致谢 / License

- 客户端代码、模块、UI 源自 **[Huty1337/NeoNaven](https://github.com/Huty1337/NeoNaven)**。
- 注入与补丁引擎参考了本机已验证的 OpenZen 运行时补丁方案。
- 许可证沿用上游：见 [LICENSE](LICENSE)。