# jframe_transform — 动态类增强模块

> 设计方案：`plans/transform-module-plan.md`（含完整决策记录与边界讨论）

## 这是什么

**服务器正常启动后**（零启动参数、零重启），依赖 JFrame 的插件可动态增强
**任意非 bootstrap/JDK 类**（Nukkit 服务端类为主，服务端 classpath 库与其他插件类均可）的
**已加载**方法：

- 观测调用（参数/返回值/异常）
- 修改参数、修改返回值
- 短路跳过原方法体（`skip/skipWith/forceThrow`）
- 整方法接管（REPLACE，含私有/静态方法；注解层支持**签名镜像**——同参同返回值）
- 私有字段/方法读写（`ctx.field/setField/call` 与 `PrivateAccess`，纯反射零织入）

技术底座：运行中向 JVM **自附加 agent**（`ByteBuddyAgent`，JDK 21+ 会打一条无害警告；
可选 `-javaagent:jframe_main.jar` 预注入消除）+ ByteBuddy **Advice 内联**（只改方法体，
天然满足 JVM retransform 约束）+ `retransformClasses` 安全点原子替换（无半新半旧中间态）。

## 快速上手（业务插件）

`plugin.yml` 声明 `depend: [JFrame]`；`pom.xml` 中 `jframe_main` 与
`jframe_transform`（classifier `bridge`）均为 `provided`：

```xml
<dependency>
    <groupId>io.github.JiangHu.jframe</groupId>
    <artifactId>jframe_main</artifactId>
    <version>1.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
<!-- bridge 类型（HookContext/HookAt/MethodHook/ReplaceHook）编译期专用：
     provided 保证绝不打包进插件 jar、不部署服务器（运行时副本由 JFrame 注入） -->
<dependency>
    <groupId>io.github.JiangHu.jframe</groupId>
    <artifactId>jframe_transform</artifactId>
    <version>1.0-SNAPSHOT</version>
    <classifier>bridge</classifier>
    <scope>provided</scope>
</dependency>
```

### 注解声明式（推荐主路径）

```java
public class MyPlugin extends PluginBase {
    @Override
    public void onEnable() {
        JFrameMain jframe = (JFrameMain) getServer().getPluginManager().getPlugin("JFrame");
        try {
            jframe.getTransformAPI().forPlugin(this).scan("com.example.myplugin.hook");
        } catch (TransformException e) {
            getLogger().error("动态增强不可用，功能降级: " + e.getMessage());
        }
    }
    // onDisable 零样板——框架监听 PluginDisableEvent 自动停分发 + 还原字节码
}

/** 宿主类：普通类，无需接口；一个类可挂任意多个 hook */
public class NukkitPatches {

    /** before 手感（≈ Mixin @At("HEAD")）：改参 */
    @HookOn(target = "cn.nukkit.level.Level", method = "setRaining",
            at = HookAt.HEAD, needsArgs = true)
    public void lockLobbyWeather(HookContext ctx) {
        if ("lobby".equals(((Level) ctx.getInstance()).getName())) {
            ctx.setArg(0, false);
        }
    }

    /** after 手感（≈ @At("RETURN")）：改返回值 */
    @HookOn(target = "cn.nukkit.Server", method = "getMotd", at = HookAt.RETURN)
    public void motdPrefix(HookContext ctx) {
        ctx.setReturn("[增强] " + ctx.getReturn());
    }

    /** 整方法接管（≈ @Overwrite）：签名镜像，同参同返回值（首参 = 目标实例） */
    @HookOn(target = "cn.nukkit.Player", method = "attackEntity", at = HookAt.REPLACE)
    public boolean critStreak(Player self, Entity target, double damage) {
        return self.kill(target, damage * 2);
    }
}
```

### 命令式（运行时动态挂/摘）

```java
TransformPluginScope transform = jframe.getTransformAPI().forPlugin(this);

HookHandle handle = transform.hook("cn.nukkit.entity.mob.EntityZombie", "onUpdate",
                                    new ZombieNerfHook());
handle.status();            // ACTIVE / PENDING(目标类未加载) / FAILED
handle.touchedClassNames(); // 通配命中清单
handle.unhook();            // 停分发；该类无其他钩子时还原原始字节码

transform.replace("cn.nukkit.level.Level", "getWeatherDuration",
                  (ReplaceHook) ctx -> 999999);   // 无类型整方法接管
```

## 语义速查

| 相位 | 触发 | 能做什么 |
|---|---|---|
| `at = HEAD` / `before` | 方法体前 | `setArg` 改参（需 `needsArgs=true`，元数 ≤6；高元只读模板下 `setArg` 抛错）、`skip/skipWith` 短路、`forceThrow` |
| `at = RETURN` / `after` | 方法体后（含抛出路径） | 读返回值/异常、`setReturn` 改返回值（正常路径）、`forceThrow` |
| `at = REPLACE` | **替代原方法体** | 返回值即方法返回值；抛出即方法异常；私有/静态可挂 |

- **异常传播对 void 方法同样成立**：`forceThrow` / REPLACE 抛出在 void 方法上也会
  传播给调用方（needsEnter 时无条件织 exit 作为异常出口），不会静默吞掉。
- **优先级链**：同方法多钩子 HIGHEST→LOWEST 依次执行（参数修改向后可见，任一 skip 截断），
  after 反序；多 REPLACE 最高优先级胜出（注册时警告）。
- **按类名匹配**：hook 目标按类全限定名匹配——同一类名在**所有类加载器**中的类都会被
  增强（跨插件同名类共享同一组钩子）；目标类的类加载器父链必须可见 bridge（即系统
  类加载器），无父加载器的自定义加载器不支持。
- **相位省略**：空相位不内联任何 dispatch 指令（注解层"方法不存在 = 相位不存在"；
  `needsArgs=false` 免装箱）——未被 hook 的类/相位零开销。
- **同形状增删钩子**只改注册表（零 retransform）；形状跃迁才对单类重织（毫秒级）。
- **线程**：钩子可能在任意线程触发（主线程/Netty），跨线程改世界自行调度；
  钩子抛异常被框架捕获记录，本次调用按"未修改"继续（REPLACE 抛出除外——直接传播）。
- **REPLACE 边界**：原方法局部变量不存在（原体未执行）；要中间状态用 HEAD/RETURN 包裹。

## 运维

- 首次注册时自动自附加（`config.yml` 的 `transform.enabled: false` 可整体禁用）。
- JDK 21+ 控制台的动态附加警告属预期；`-javaagent:jframe_main.jar` 预注入可消除
  （manifest 已带 `Premain-Class`/`Can-Retransform-Classes`）。
- bridge jar 注入落盘于 `plugins/JFrame/transform/`（只读环境自动降级临时目录）。
- 卸载/关服：停分发 → 移除 transformer → retransform 还原全部织入类（`/reload` 安全）。
  **若日志出现"还原 retransform 失败"，旧织入可能残留（hook id 已加随机盐值缓解错误
  分发），此时热重载不保证安全——建议重启 JVM 后再加载新版本插件。**
- **在线注册策略**：优先在插件 `onEnable` 批量注册；运行中注册推荐仅做同形状链更新
  （零 retransform、即时生效）；形状跃迁重织会触发 JVM safepoint 停顿（毫秒级），
  高频在线变更与全 JVM 级性能影响尚未经实机验证，生产环境谨慎使用。

## 已知限制

- `@Redirect`/`@ModifyArg` 调用点级拦截、lambda 体替换、构造器 hook、加字段/方法 → 后续版本。
- bootstrap/JDK 类（`java.*`/`javax.*`/`jakarta.*`/`jdk.*`/`sun.*`/`com.sun.*`）拒绝。
- 高于 6 参方法的参数**只读**（无写回模板，`setArg` 报错）。
- ByteBuddy 以原名打包（shade 3.6.0 的内置 ASM 不支持 JDK 24 类文件、无法 relocate）；
  与自带 ByteBuddy 的其他插件存在理论冲突面，待 shade 升级后补 relocate。

## 更多

- 模块内部架构（bridge 单副本 / Advice 模板矩阵 / 注册表与分发）见
  [DEVELOPER.md](DEVELOPER.md) 与设计方案 `plans/transform-module-plan.md`。
- 实机演示：jframe_example 的 `/jtransform on|off|status`。
