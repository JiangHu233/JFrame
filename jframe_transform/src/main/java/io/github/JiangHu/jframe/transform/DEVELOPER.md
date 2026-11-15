# jframe_transform 开发文档（内部架构）

> 面向模块维护者；对外 API 见 [README.md](README.md) 与设计方案 §3/附录 A。

## 组件总览

```
业务插件 ──scan/hook/replace──▶ TransformPluginScope ──▶ HookRegistry（注册表心脏）
                                                        │
                     BridgeJarInstaller + AgentInstaller │◀─ 首次注册触发自附加
                                                        │
                  TransformerInstaller（AgentBuilder + Advice 装配，单一 transformer）
                                                        │  retransformClasses 驱动
                                                        ▼
被增强类（内联 dispatch） ──Bridge.enter/exit(id,...)──▶ Bridge（系统加载器 bridge 副本）
                                                        │ Sink
                                                        ▼
                                               BridgeSinkImpl（链执行/REPLACE/镜像）
```

## 类加载器边界（本模块最关键的工程点）

被内联进目标类的 dispatch 代码只能引用**系统类加载器可见**的类型。bridge 包
（`Bridge/State/ExitState/Sink/MethodHook/ReplaceHook/HookContext/HookAt`）：

1. **主 artifact 排除该包**（maven-jar-plugin excludes）+ 以原始 .class 资源内嵌于
   `META-INF/jframe-bridge/`（antrun 拷贝）→ jframe 与业务插件的 PluginClassLoader
   都解析不到自有副本，不依赖 Nukkit 委托顺序；
2. 首次安装时 `BridgeJarInstaller` 组装 `bridge-<hash>.jar` 落盘并
   `appendToSystemClassLoaderSearch` → `Class.forName` 取得**全 JVM 唯一规范副本**
   （已可解析则复用——单元测试 classpath 场景天然单副本）；
3. **下游编译可见性**经 `classifier=bridge` 小 artifact（业务插件 `provided` 依赖）。

## 分发链路

- Advice 模板（`bytebuddy.advice`）经 `skipOn = OnNonDefaultValue` 支持短路；
  **State 仅在短路时非空返回**——非短路路径 exit 自建上下文（AllArguments 只读快照）。
- enter/exit 必须位于**同一 Advice 实例**（`to(enter, exit)` 双类重载），
  否则 `@Advice.Enter` 链路断裂（IllegalArgumentException）。
- hookId 以 `@HookIdMarker` 常量经 `Advice.withCustomMapping().bind(...)` 内联，
  无跨类加载器引用泄漏。
- 模板矩阵：enter = 免参/元数 0..6 可写回/高元只读；exit = void/非void × 有/无 enter。
- 相位省略：`EmptyPhaseDetector`（shaded ASM 空方法体判定）+ 链相位并集 →
  未占用相位完全不织入；`MethodRegistration.requirementsOf` 为唯一形状计算源。

## 注册表与形状协调（HookRegistry）

- `TargetSpec`（类/方法通配 + 参数签名）→ `weavePlan()` 在织入期为
  （类，方法，描述符）建立/复用 `MethodRegistration`（id 内联后永不重组）。
- 注册/卸载：锁内重建链 → 形状对比；同形状原地换链（零 retransform），
  形状跃迁/链空 → `dirtyClasses` → `RefreshDriver.retransformClasses`。
- 激活判定 `needsActivationWeave`：未织入类 OR 已织入类出现**首个命中某未织方法**的新规格
  （同方法后续注册走原地链更新）。
- 还原 = 类不再命中 matcher 的 retransform（JVM 对原始字节码重放全部 transformer）。

## 生命周期

- `PluginDisableListener`（PluginAware）监听 `PluginDisableEvent` → `unhookPlugin`
  （跳过 JFrame 自身）。`TransformModule.shutdown()`（order 950）：停分发 →
  `removeTransformer` → 全量还原 → 清空（`/reload` 后重新装配，无悬挂引用）。

## 构建细节

- 模块以 **release 17** 编译（Advice 模板按字节码内联，受 ByteBuddy 1.15.x 内置 ASM
  类文件版本上限约束）；被增强类为更低版本字节码不受影响。
- 运行 JVM 高于 ByteBuddy 已知版本时依赖 `net.bytebuddy.experimental=true`
  （`AgentInstaller` 静态块设置）。
- 测试在 surefire JVM 内真实自附加（20 用例覆盖 attach/全语义矩阵/通配/相位省略/
  retransform 计数断言/注解扫描/签名镜像 fail-fast/空相位跳过）。
