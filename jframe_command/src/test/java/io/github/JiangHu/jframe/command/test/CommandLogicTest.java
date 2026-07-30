package io.github.JiangHu.jframe.command.test;

import io.github.JiangHu.jframe.command.example.CaseController;
import io.github.JiangHu.jframe.command.example.GuildController;
import io.github.JiangHu.jframe.command.example.ShopController;
import io.github.JiangHu.jframe.command.routing.CommandRegistry;
import io.github.JiangHu.jframe.command.routing.CommandRoute;

/**
 * 命令路由逻辑测试（无 Nukkit 服务器，纯 JVM 可运行）。
 * <p>
 * 通过 {@link CommandRegistry#dispatch} 直接驱动路由引擎，用 {@link FakeCommandSender}
 * 捕获输出与权限，再断言示例控制器写入的静态字段。
 *
 * <p>运行方式：直接执行 {@link #main}。
 */
public final class CommandLogicTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // ① 构建注册表并注册两个示例控制器
        CommandRegistry registry = new CommandRegistry();
        registry.register(GuildController.class);
        registry.register(ShopController.class);
        registry.register(CaseController.class);

        System.out.println("已注册根命令: " + registry.getRootCommands());
        System.out.println("路由总数: " + registry.getRoutes().size());
        System.out.println("==================== 开始测试 ====================");

        FakeCommandSender op = new FakeCommandSender("Admin", true);   // 拥有全部权限
        FakeCommandSender noob = new FakeCommandSender("Guest", false); // 无任何权限

        // ========== GuildController ==========

        // 1. @PathVariable 单值捕获 + @Sender 注入
        op.getMessages().clear();
        CommandRoute.InvokeResult r1 = registry.dispatch(op, "guild", new String[]{"create", "我的公会"});
        check("1. create 单值变量", r1.success());
        check("1. create 静态字段", "我的公会".equals(GuildController.lastCreateName));
        check("1. create 回执消息", op.lastMessage() != null && op.lastMessage().contains("已创建公会"));

        // 2. {*msg} 贪婪捕获（多 token 合并）
        CommandRoute.InvokeResult r2 = registry.dispatch(op, "guild", new String[]{"broadcast", "hello", "world", "foo"});
        check("2. broadcast 贪婪变量", r2.success());
        check("2. broadcast 静态字段", "hello world foo".equals(GuildController.lastBroadcast));

        // 3. #{id} 兼容写法
        CommandRoute.InvokeResult r3 = registry.dispatch(op, "guild", new String[]{"lookup", "42"});
        check("3. lookup #变量", r3.success());
        check("3. lookup 静态字段", "42".equals(GuildController.lastLookup));

        // 4. @CommandParam 命名参数 --reason
        CommandRoute.InvokeResult r4 = registry.dispatch(op, "guild", new String[]{"kick", "Steve", "--reason", "违规"});
        check("4. kick 命名参数", r4.success());
        check("4. kick 静态字段", "Steve:违规".equals(GuildController.lastKick));

        // 5. @CommandParam 缺省默认值
        CommandRoute.InvokeResult r5 = registry.dispatch(op, "guild", new String[]{"kick", "Alex"});
        check("5. kick 默认值", r5.success());
        check("5. kick 静态字段", "Alex:无".equals(GuildController.lastKick));

        // 6. 位置参数 + 自动 int 转换
        CommandRoute.InvokeResult r6 = registry.dispatch(op, "guild", new String[]{"sethome", "tower", "10", "20", "30"});
        check("6. sethome 位置参数", r6.success());
        check("6. sethome 静态字段", "tower@(10,20,30)".equals(GuildController.lastHome));

        // ========== ShopController ==========

        // 7. 多路径变量 + int 转换
        CommandRoute.InvokeResult r7 = registry.dispatch(op, "shop", new String[]{"buy", "diamond", "64"});
        check("7. buy 多变量转换", r7.success());
        check("7. buy 静态字段", "diamond x64".equals(ShopController.lastBuy));

        // 8. 布尔标记 --all（出现即 true）
        CommandRoute.InvokeResult r8 = registry.dispatch(op, "shop", new String[]{"sell", "sword", "--all"});
        check("8. sell --all=true", r8.success());
        check("8. sell 静态字段", "sword (all=true)".equals(ShopController.lastSell));

        // 9. 布尔标记缺省（false）
        CommandRoute.InvokeResult r9 = registry.dispatch(op, "shop", new String[]{"sell", "sword"});
        check("9. sell 默认 all=false", r9.success());
        check("9. sell 静态字段", "sword (all=false)".equals(ShopController.lastSell));

        // 10. @RawArgs 原始参数透传（不含子命令名 echo，仅剩余参数）
        CommandRoute.InvokeResult r10 = registry.dispatch(op, "shop", new String[]{"echo", "foo", "bar", "baz"});
        check("10. echo 原始透传", r10.success());
        check("10. echo 静态字段", "foo bar baz".equals(ShopController.lastEcho));

        // 11. @Sender Player 类型 → 非玩家执行失败（权限通过后由类型校验拦截）
        ShopController.spawnInvoked = false;
        CommandRoute.InvokeResult r11 = registry.dispatch(op, "shop", new String[]{"spawn"});
        check("11. spawn 非玩家失败", !r11.success());
        check("11. spawn 未执行", !ShopController.spawnInvoked);
        check("11. spawn 提示仅玩家", r11.errorMessage() != null && r11.errorMessage().contains("只能由玩家"));

        // 12. 权限校验 → 无权限者被拦截
        CommandRoute.InvokeResult r12 = registry.dispatch(noob, "shop", new String[]{"spawn"});
        check("12. spawn 无权限失败", !r12.success());
        check("12. spawn 权限提示", r12.errorMessage() != null && r12.errorMessage().contains("没有权限"));

        // 13. 未知子命令
        CommandRoute.InvokeResult r13 = registry.dispatch(op, "guild", new String[]{"foobar"});
        check("13. 未知子命令失败", !r13.success());
        check("13. 未知子命令提示", r13.errorMessage() != null && r13.errorMessage().contains("未知"));

        // 14. 参数类型转换失败（count 非数字）
        CommandRoute.InvokeResult r14 = registry.dispatch(op, "shop", new String[]{"buy", "apple", "notANumber"});
        check("14. 类型转换失败", !r14.success());
        check("14. 类型转换提示", r14.errorMessage() != null && r14.errorMessage().contains("参数错误"));

        // ========== CaseController（大小写对齐 Nukkit）==========
        // 注册时根命令为 "GameItem"（首字母大写），复刻真实插件场景。

        // 15. 根命令归一化为小写：getRootCommands() 应含 "gameitem"（而非 "GameItem"）
        check("15. 根命令归一化小写", registry.getRootCommands().contains("gameitem"));

        // 16. 小写 dispatch 命中 main（模拟 Nukkit 回传 command.getName()="gameitem"）
        CaseController.lastTriggered = null;
        CommandRoute.InvokeResult r16 = registry.dispatch(op, "gameitem", new String[]{});
        check("16. 小写根命令命中", r16.success());
        check("16. 命中 main 方法", "main".equals(CaseController.lastTriggered));

        // 17. give 子命令 + 位置参数（小写子命令）
        CaseController.lastTriggered = null;
        CommandRoute.InvokeResult r17 = registry.dispatch(op, "gameitem", new String[]{"give", "sword"});
        check("17. give 子命令命中", r17.success());
        check("17. give 参数绑定", "give:sword".equals(CaseController.lastTriggered));

        // 18. 大写/混合大小写根命令也能命中（大小写不敏感，对齐 Nukkit）
        CaseController.lastTriggered = null;
        CommandRoute.InvokeResult r18 = registry.dispatch(op, "GameItem", new String[]{});
        check("18. 大写根命令命中", r18.success() && "main".equals(CaseController.lastTriggered));
        CaseController.lastTriggered = null;
        CommandRoute.InvokeResult r18b = registry.dispatch(op, "GAMEITEM", new String[]{});
        check("18b. 全大写根命令命中", r18b.success() && "main".equals(CaseController.lastTriggered));

        // 19. 子命令大小写不敏感（玩家输入 /gameitem GIVE x 也能命中 give）
        CaseController.lastTriggered = null;
        CommandRoute.InvokeResult r19 = registry.dispatch(op, "gameitem", new String[]{"GIVE", "bow"});
        check("19. 子命令大小写不敏感", r19.success() && "give:bow".equals(CaseController.lastTriggered));

        // 20. 变量值保留原样大小写（路径变量是数据，不应被转小写）
        CaseController.lastTriggered = null;
        CommandRoute.InvokeResult r20 = registry.dispatch(op, "guild", new String[]{"create", "MyGuild"});
        check("20. 变量值保留大小写", r20.success() && "MyGuild".equals(GuildController.lastCreateName));

        System.out.println("==================== 测试汇总 ====================");
        System.out.println("通过: " + pass + "，失败: " + fail);
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("全部通过 ✅");
    }

    /** 简易断言：打印 PASS/FAIL 并计数。 */
    private static void check(String name, boolean condition) {
        if (condition) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private CommandLogicTest() {
    }
}
