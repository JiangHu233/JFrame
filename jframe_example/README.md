# JFrame 示例插件（jframe_example）

> 一个完整可运行的 Nukkit 插件样例，演示如何**作为业务插件依赖前置 JFrame**，复用其 Spring 容器，调用事件 / 表单 / 线程 / 命令 / 箱子界面 / AI 六大模块的 API。

---

## 📑 目录

- [一、它演示什么](#一它演示什么)
- [二、与传统写法的对比](#二与传统写法的对比)
- [三、目录结构](#三目录结构)
- [四、构建与部署](#四构建与部署)
- [五、接入流程（核心）](#五接入流程核心)
- [六、示例清单](#六示例清单)
- [七、游戏内命令](#七游戏内命令)
- [八、游戏内验证步骤](#八游戏内验证步骤)

---

## 一、它演示什么

本插件展示了一个真实业务插件的**标准接入姿势**：

1. 在 [`plugin.yml`](src/main/resources/plugin.yml) 声明 `depend: [JFrame]`，依赖前置插件。
2. [`ExamplePlugin.onEnable()`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java:72) 获取 [`JFrameMain`](../jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 单例，直接复用其已创建好的容器中的各模块 API。
3. 用**自身类加载器**扫描 jar 内的 `@Wrapper`（事件）、`@CommandController`（命令），注册到框架共享容器。
4. 通过命令打开表单 / 箱子界面、测试 AI 功能。

> 本插件**不创建自己的 Spring 容器**，也不重复打包框架类（`pom.xml` 中 `jframe_main` 为 `provided` 作用域）。容器、生命周期、类加载器问题全部由前置插件 [`JFrameMain`](../jframe_main/src/main/java/io/github/JiangHu/jframe/main/JFrameMain.java) 统一处理。

---

## 二、与传统写法的对比

| 能力 | 传统 Nukkit 写法 | 本示例演示的 JFrame 写法 |
|------|------|------|
| **事件监听** | 实现 `Listener` + `@EventHandler`，每个事件一个方法，手动管理监听器注册/注销 | [`@Wrapper`](src/main/java/io/github/JiangHu/jframe/example/wrapper/PlayerGameWrapper.java) 类 + `@EventRoute`，支持对象级路由、SpEL 条件、优先级独占 |
| **命令处理** | 覆写 `onCommand`，手写 `if/else` 分发子命令、手动 `parseInt` 转换 | [`@CommandController`](src/main/java/io/github/JiangHu/jframe/example/command/KitController.java) + `@CommandMapping`，路径变量 / 命名参数 / 类型转换全自动 |
| **表单界面** | 手写 `FormWindow`、手动管理打开/回调、导航要自己维护栈 | 继承 `FormView`，声明式构建 + 内置栈式导航（[`addStack`](src/main/java/io/github/JiangHu/jframe/example/view/NavigationDemoView.java) / `replaceThis`） |
| **箱子界面** | 手动操作虚拟箱子坐标、处理点击事件、布局全靠算坐标 | 声明式组件（Button/StorageBox/Filler）+ 自动布局（[`InventoryTestView`](src/main/java/io/github/JiangHu/jframe/example/inventory/InventoryTestView.java)） |
| **异步任务** | `scheduleAsyncTask` 或裸 `new Thread`，无队列概念 | [`ThreadAPI`](../jframe_async/src/main/java/io/github/JiangHu/jframe/async/thread/ThreadAPI.java) 命名队列，串行保证 |
| **AI 行为** | 从零实现寻路 / 战术逻辑 | [`AiAPI`](src/main/java/io/github/JiangHu/jframe/example/command/AiController.java) 一行调用寻路、导航、战术 |

---

## 三、目录结构

```
jframe_example/
├── pom.xml                          # provided 依赖 jframe_main，shade 打 fat jar
└── src/main/
    ├── java/.../example/
    │   ├── ExamplePlugin.java       # 主类：获取前置 JFrame、扫描注册、命令分发
    │   ├── wrapper/                 # 事件模块示例（@Wrapper）
    │   │   ├── PlayerGameWrapper.java     # 对象级：每玩家独立统计实例
    │   │   ├── ChatGuardWrapper.java      # 全局单例：聊天审核（优先级+独占）
    │   │   ├── ThunderSwordWrapper.java   # 事件触发：闪电钻石剑
    │   │   └── Template.java              # 空模板：复制即用
    │   ├── command/                 # 命令模块示例（@CommandController）
    │   │   ├── KitController.java         # /kit：路径变量/命名参数/贪婪变量/权限
    │   │   └── AiController.java          # /ai：AI 寻路与战术测试
    │   ├── view/                    # 表单模块示例（FormView）
    │   │   ├── FormDemoView.java          # 表单基础
    │   │   ├── CustomFormDemoView.java    # 自定义表单
    │   │   ├── ModalFormDemoView.java     # 模态表单
    │   │   ├── IconDemoView.java          # 图标
    │   │   ├── NavigationDemoView.java    # 栈式导航（含 SubView / ReplacedView）
    │   │   ├── DataBusDemoView.java       # 数据总线
    │   │   ├── OnDataDemoView.java        # 数据回调（含 DetailView）
    │   │   ├── BuildStrategyDemoView.java # 构建策略
    │   │   ├── ShopSubView.java           # 商店子视图
    │   │   └── StatsView.java             # 统计视图
    │   ├── inventory/               # 箱子界面示例（InventoryView）
    │   │   ├── ShopInventoryView.java     # 商店：声明式组件
    │   │   └── InventoryTestView.java     # 全特性测试：所有组件/布局/事件/外观
    │   └── entity/
    │       └── TestNpcEntity.java         # AI 测试用 NPC 实体
    └── resources/
        └── plugin.yml               # depend:[JFrame] + 命令/权限声明
```

---

## 四、构建与部署

### 1. 前置条件

- JDK 24（与框架一致）
- 已在本地 Maven 仓库安装好框架各模块：在**项目根目录**执行
  ```bash
  mvn clean install -DskipTests
  ```

### 2. 打包示例插件

```bash
cd jframe_example
mvn clean package
```
产物：`jframe_example/target/jframe_example.jar`

### 3. 部署

服务器 `plugins/` 目录需**先放** `jframe_main.jar`（前置插件），**再放** `jframe_example.jar`。启动服务器后，JFrame 先加载并创建容器，本插件随后复用。

---

## 五、接入流程（核心）

[`ExamplePlugin.onEnable()`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java:72) 是接入的标准模板，分五步：

```java
@Override
public void onEnable() {
    // ① 获取前置插件 JFrame 单例
    Plugin jframePlugin = getServer().getPluginManager().getPlugin("JFrame");
    JFrameMain jframeMain = (JFrameMain) jframePlugin;

    // ② 复用 JFrame 容器中的各模块 API（无需自己创建容器）
    eventAPI     = jframeMain.getEventAPI();
    viewAPI      = jframeMain.getViewAPI();
    threadAPI    = jframeMain.getThreadAPI();
    commandAPI   = jframeMain.getCommandAPI();
    inventoryAPI = jframeMain.getInventoryAPI();
    aiAPI        = jframeMain.getAiAPI();

    // ③ 扫描本插件 jar 内的 @Wrapper（必须传自身类加载器）
    eventAPI.scan(getClass().getClassLoader(), "...example.wrapper");

    // ④ 扫描 @CommandController（根命令会自动注册到 Nukkit，无需 plugin.yml）
    commandAPI.scan(getClass().getClassLoader(), "...example.command");

    // ⑤ 创建本插件专用的异步任务队列
    threadAPI.createThreadTask("example");
}
```

> **关键点**：`scan` 必须传入 `getClass().getClassLoader()`（本插件的类加载器），否则扫描不到本插件 jar 内的类。框架容器由 JFrame 持有，但业务类由业务插件的类加载器加载。

---

## 六、示例清单

### 事件模块（`wrapper/`）

| 文件 | 演示能力 |
|------|----------|
| [`PlayerGameWrapper`](src/main/java/io/github/JiangHu/jframe/example/wrapper/PlayerGameWrapper.java) | 对象级路由（每玩家一实例）、`@KeyExtractor`、自定义实例工厂、SpEL 条件处理器 |
| [`ChatGuardWrapper`](src/main/java/io/github/JiangHu/jframe/example/wrapper/ChatGuardWrapper.java) | 全局单例、优先级 + 独占（拦截后阻止低优先级）、filter 方法引用 |
| [`ThunderSwordWrapper`](src/main/java/io/github/JiangHu/jframe/example/wrapper/ThunderSwordWrapper.java) | 事件触发道具（右键方块召唤闪电） |
| [`Template`](src/main/java/io/github/JiangHu/jframe/example/wrapper/Template.java) | 空模板，复制即用 |

### 命令模块（`command/`）

| 文件 | 演示能力 |
|------|----------|
| [`KitController`](src/main/java/io/github/JiangHu/jframe/example/command/KitController.java) | 路径变量 `{item} {count}`、命名参数 `--amount`、布尔标记 `--on`、贪婪变量 `{*msg}`、`@Sender Player` 权限校验 |
| [`AiController`](src/main/java/io/github/JiangHu/jframe/example/command/AiController.java) | AI 寻路、实体导航、战术行为（找掩体/包抄/高地）、战斗动作 |

### 表单模块（`view/`）

| 文件 | 演示能力 |
|------|----------|
| [`FormDemoView`](src/main/java/io/github/JiangHu/jframe/example/view/FormDemoView.java) | 表单基础构建与按钮回调 |
| [`CustomFormDemoView`](src/main/java/io/github/JiangHu/jframe/example/view/CustomFormDemoView.java) | 自定义表单（输入框/下拉/滑块等） |
| [`ModalFormDemoView`](src/main/java/io/github/JiangHu/jframe/example/view/ModalFormDemoView.java) | 模态表单（是/否） |
| [`IconDemoView`](src/main/java/io/github/JiangHu/jframe/example/view/IconDemoView.java) | 按钮图标 |
| [`NavigationDemoView`](src/main/java/io/github/JiangHu/jframe/example/view/NavigationDemoView.java) | 栈式导航（含 `SubView` / `ReplacedView`，演示 `addStack` / `replaceThis`） |
| [`DataBusDemoView`](src/main/java/io/github/JiangHu/jframe/example/view/DataBusDemoView.java) | 数据总线（跨视图共享数据） |
| [`OnDataDemoView`](src/main/java/io/github/JiangHu/jframe/example/view/OnDataDemoView.java) | 数据回调（含 `DetailView`） |
| [`BuildStrategyDemoView`](src/main/java/io/github/JiangHu/jframe/example/view/BuildStrategyDemoView.java) | 构建策略 |
| [`StatsView`](src/main/java/io/github/JiangHu/jframe/example/view/StatsView.java) | 统计展示 |

### 箱子界面模块（`inventory/`）

| 文件 | 演示能力 |
|------|----------|
| [`ShopInventoryView`](src/main/java/io/github/JiangHu/jframe/example/inventory/ShopInventoryView.java) | 声明式组件（Button/StorageBox）+ 自动布局 |
| [`InventoryTestView`](src/main/java/io/github/JiangHu/jframe/example/inventory/InventoryTestView.java) | 全特性集中测试：所有组件 / 布局 / 事件 / 外观 / 图层 / 动态更新 |

### 实体（`entity/`）

| 文件 | 演示能力 |
|------|----------|
| [`TestNpcEntity`](src/main/java/io/github/JiangHu/jframe/example/entity/TestNpcEntity.java) | AI 测试用 NPC（无怪物默认 AI，供寻路/战术测试） |

---

## 七、游戏内命令

| 命令 | 来源 | 说明 |
|------|------|------|
| `/jframe` | [`plugin.yml`](src/main/resources/plugin.yml) | 打开示例菜单 |
| `/sword` | [`plugin.yml`](src/main/resources/plugin.yml) + [`onCommand`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java:115) | 获得闪电钻石剑 |
| `/shop` | [`plugin.yml`](src/main/resources/plugin.yml) + [`onCommand`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java:128) | 打开声明式商店箱子 |
| `/inv <jframe>` | [`plugin.yml`](src/main/resources/plugin.yml) + [`onCommand`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java:151) | 用 jframe_inventory 打开箱子 |
| `/invtest` | [`plugin.yml`](src/main/resources/plugin.yml) + [`onCommand`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java:141) | 打开箱子全特性测试界面 |
| `/kit ...` | [`KitController`](src/main/java/io/github/JiangHu/jframe/example/command/KitController.java) 动态注册 | 命令模块示例（无需 plugin.yml 声明） |
| `/ai ...` | [`AiController`](src/main/java/io/github/JiangHu/jframe/example/command/AiController.java) 动态注册 | AI 模块示例（无需 plugin.yml 声明） |

> `/kit`、`/ai` 由 [`CommandAPI.scan()`](src/main/java/io/github/JiangHu/jframe/example/ExamplePlugin.java:103) 扫描 `@CommandController` 后自动注册到 Nukkit，**无需在 plugin.yml 声明**；其余命令走传统 `plugin.yml` + `onCommand`。

---

## 八、游戏内验证步骤

服务器启动后，用玩家账号进服，按以下步骤验证：

### 事件
- **进服欢迎**：玩家进入收到欢迎消息；OP 额外收到管理员上线提示（验证 SpEL 条件）。
- **移动计数**：移动累计达到阈值收到计数提示（验证对象级路由）。
- **聊天审核**：发送违禁词被拦截且不计入聊天次数（验证优先级 + 独占）；正常消息计数 +1。

### 命令（`/kit`）
| 操作 | 预期 |
|------|------|
| `/kit give 264 64` | 获得 64 个物品（路径变量 + 类型转换） |
| `/kit give 264 abc` | 返回参数错误（转换失败） |
| `/kit heal --amount 10` | 生命恢复至 10（命名参数） |
| `/kit fly --on` | 启用飞行（布尔标记） |
| `/kit broadcast 你好 世界` | 全服广播（贪婪变量） |
| `/kit whoami`（控制台） | 提示仅玩家可用（`@Sender Player`） |

### 表单 / 箱子 / AI
- `/jframe` 打开菜单，点击进入子界面验证栈式导航。
- `/shop`、`/invtest` 验证声明式箱子组件与布局。
- `/ai spawn` 生成 NPC，`/ai <寻路/战术>` 验证 AI 行为。

> 维护者文档请看 [DEVELOPER.md](DEVELOPER.md)。
