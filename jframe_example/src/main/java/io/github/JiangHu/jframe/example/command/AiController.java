package io.github.JiangHu.jframe.example.command;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import io.github.JiangHu.jframe.ai.AiAPI;
import io.github.JiangHu.jframe.ai.core.tactical.FormationType;
import io.github.JiangHu.jframe.ai.core.tactical.TacticalPosition;
import io.github.JiangHu.jframe.command.annotation.CommandController;
import io.github.JiangHu.jframe.command.annotation.CommandMapping;
import io.github.JiangHu.jframe.command.annotation.RawArgs;
import io.github.JiangHu.jframe.command.annotation.Sender;
import io.github.JiangHu.jframe.example.entity.TestNpcEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 模块<b>运行时测试控制器</b>（根命令 {@code /ai}）。
 * <p>
 * 本类是 {@code jframe_ai} 在真实服务器环境下的「冒烟测试」入口：通过一组子命令，
 * 逐项驱动 AI 模块的七工厂 API（{@code walk/chase/wander/attack/see}）、寻路双策略
 * （A* 默认 / 贪心低开销）、单实体战术、团队战术与战斗能力，
 * 让开发者能在游戏内直观观察实体行为，快速定位运行时 bug。
 *
 * <h3>注册方式</h3>
 * 采用 {@code jframe_command} 的声明式注册：本类标注 {@link CommandController @CommandController("ai")}，
 * 由 {@code ExamplePlugin} 通过 {@code commandAPI.scan(...)} 自动发现并注册。
 * 根命令 {@code ai} 由框架动态注册到 Nukkit，<b>无需</b>在 {@code plugin.yml} 声明。
 *
 * <h3>依赖注入</h3>
 * {@code jframe_command} 通过<b>无参构造</b>实例化控制器（不支持构造器注入），
 * 因此本类用<b>静态字段</b> {@link #ai} 持有 {@link AiAPI}，
 * 由 {@code ExamplePlugin.onEnable} 在 scan 之前调用 {@link #setAi} 注入。
 *
 * <h3>命令一览</h3>
 * <pre>
 *   /ai                    显示帮助
 *   /ai spawn [类型]       生成测试实体（默认 TestNpc，可选 Zombie/Skeleton/Creeper/Cow 等）
 *   /ai goto               主实体贪心寻路到玩家（粒子显示路径轨迹）
 *   /ai goto2              主实体续算导航到玩家（走完自动续算，长距离）
 *   /ai chase              主实体贪心追逐玩家（粒子显示路径轨迹）
 *   /ai wander [半径]      主实体在半径内随机游荡（默认 10）
 *   /ai cover              主实体寻找掩体并前往（相对玩家为威胁）
 *   /ai flee               主实体远离玩家
 *   /ai flank              主实体侧翼包抄玩家
 *   /ai high               主实体寻找高地
 *   /ai surround [数量]    生成多个实体并包围玩家（团队，默认 4，上限 12）
 *   /ai rally [阵型]       团队以指定阵型集结到玩家（line/column/wedge/circle/square）
 *   /ai attack             主实体近战攻击玩家（4 点伤害）
 *   /ai shoot              主实体向玩家射箭
 *   /ai stop               停止主实体的全部 AI 行为
 *   /ai status             查看 AI 运行状态
 *   /ai clear              移除所有测试实体
 * </pre>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>{@link Sender @Sender} {@link Player} 强制「仅玩家可用」：控制台执行会自动失败</li>
 *   <li>带可选参数的子命令用 {@link RawArgs @RawArgs} 透传剩余参数，自行解析（保持 {@code /ai spawn Zombie} 自然格式）</li>
 *   <li>实体生成位置取玩家前方 3 格，避免实体卡在玩家身上</li>
 *   <li>战术位置（{@link TacticalPosition}）通过 {@link TacticalPosition#toLevelPosition}
 *       转换为世界坐标后交给导航执行器</li>
 *   <li>示例导航统一采用贪心策略（{@code strategy(ai.getGreedyPathFinder())}，低开销局部步进）；
 *       去掉 {@code strategy(...)} 即用默认 A* 策略</li>
 * </ul>
 *
 * @see AiAPI
 */
@CommandController("ai")
public class AiController {

    /** AI 服务门面（由 ExamplePlugin 在 scan 前通过 {@link #setAi} 静态注入） */
    private static AiAPI ai;

    /** 玩家名 → 主测试实体（单实体功能的操作对象） */
    private static final Map<String, Entity> primary = new ConcurrentHashMap<>();
    /** 玩家名 → 团队实体列表（多实体协同功能的操作对象） */
    private static final Map<String, List<Entity>> team = new ConcurrentHashMap<>();

    /**
     * 注入 AI 服务门面。由 {@code ExamplePlugin.onEnable} 在调用 {@code commandAPI.scan} 之前调用。
     *
     * @param aiAPI AI 服务门面
     */
    public static void setAi(AiAPI aiAPI) {
        ai = aiAPI;
    }

    // ============================ 子命令 ============================

    /** {@code /ai} 或 {@code /ai help}：显示帮助。 */
    @CommandMapping(value = "", desc = "AI 模块运行时测试（显示帮助）")
    public void help(@Sender Player player) {
        player.sendMessage("§a===== §f/ai AI 测试命令 §a=====");
        player.sendMessage("§7/ai spawn [类型] §8→ §f生成测试实体（默认 TestNpc，无怪物AI）");
        player.sendMessage("§7/ai goto §8→ §f贪心寻路到玩家（粒子显示路径）");
        player.sendMessage("§7/ai goto2 §8→ §f续算导航到玩家（走完自动续算，长距离）");
        player.sendMessage("§7/ai chase §8→ §f贪心追逐玩家（粒子显示路径）");
        player.sendMessage("§7/ai greedy §8→ §f贪心寻路到玩家（同 goto）");
        player.sendMessage("§7/ai chasegreedy §8→ §f贪心追逐玩家（同 chase）");
        player.sendMessage("§7/ai wander [半径] §8→ §f半径内游荡（默认 10）");
        player.sendMessage("§7/ai cover §8→ §f寻找掩体并前往");
        player.sendMessage("§7/ai flee §8→ §f远离玩家");
        player.sendMessage("§7/ai flank §8→ §f侧翼包抄（单实体）");
        player.sendMessage("§7/ai flankteam [数量] §8→ §f团队协同钳形包抄（多实体，默认 4）");
        player.sendMessage("§7/ai high §8→ §f寻找高地");
        player.sendMessage("§7/ai cansee §8→ §f视野判断（当前朝向可见 / 转头可见）");
        player.sendMessage("§7/ai seeksight §8→ §f移动到能看到玩家的位置");
        player.sendMessage("§7/ai seekapprox [半径] §8→ §f模糊搜索（移动到玩家大致位置）");
        player.sendMessage("§7/ai showpath [秒数] §8→ §f粒子显示当前寻路路径（默认 10）");
        player.sendMessage("§7/ai gotocoord <x> <y> <z> §8→ §f寻路到指定坐标");
        player.sendMessage("§7/ai surround [数量] §8→ §f团队包围（默认 4）");
        player.sendMessage("§7/ai rally [阵型] §8→ §f团队集结（line/column/wedge/circle/square）");
        player.sendMessage("§7/ai attack §8→ §f近战攻击玩家");
        player.sendMessage("§7/ai shoot §8→ §f射箭");
        player.sendMessage("§7/ai stop §8→ §f停止 AI 行为");
        player.sendMessage("§7/ai status §8→ §f查看状态");
        player.sendMessage("§7/ai clear §8→ §f移除所有测试实体");
    }

    /** {@code /ai spawn [类型]}：在玩家前方生成测试实体。 */
    @CommandMapping(value = "spawn", desc = "生成测试实体", usage = "/ai spawn [TestNpc|Zombie|Skeleton|Creeper|Cow]")
    public void spawn(@Sender Player player, @RawArgs String[] args) {
        String type = args.length > 0 ? args[0] : "TestNpc";
        Entity entity = spawnEntity(player, type);
        if (entity == null) {
            player.sendMessage("§c生成实体失败，未知类型: §f" + type + "§c（默认 TestNpc，或 Zombie/Skeleton/Creeper/Cow）");
            return;
        }
        primary.put(player.getName(), entity);
        player.sendMessage("§a已生成 §b" + type + " §a作为测试实体（id=" + entity.getId() + "），位于你前方 3 格");
    }

    /** {@code /ai goto}：主实体寻路到玩家（单段贪心，走完即停）。 */
    @CommandMapping("goto")
    public void gotoTarget(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        boolean ok = greedyWalkAndShow(entity, playerPos(player));
        player.sendMessage(ok
                ? "§a测试实体正在以贪心策略寻路前往你（单段，粒子显示路径轨迹）"
                : "§e贪心寻路失败：附近无可行走方向");
    }

    /**
     * {@code /ai goto2}：主实体续算导航到玩家（走完自动续算，长距离）。
     * <p>
     * 与 {@code /ai goto}（单段贪心，走完即停）不同，本命令在导航执行器上开启
     * {@code continuous()} 连续模式：实体走完当前段后，立即从新位置重新寻路计算下一段，
     * 串联多段完成长距离导航。适用于目标较远、需要持续行进的场景。
     */
    @CommandMapping("goto2")
    public void gotoContinuous(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        ai.walk(entity)
                .strategy(ai.getGreedyPathFinder())
                .to(playerPos(player))
                .continuous()
                .start();
        ai.showPath(entity, 10);
        player.sendMessage("§a测试实体开始续算导航前往你（走完自动续算，长距离，粒子显示路径轨迹）");
    }

    /** {@code /ai chase}：主实体追逐玩家（每轮从玩家最新位置重算）。 */
    @CommandMapping("chase")
    public void chase(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        // chase 接受活实体引用：玩家离线/死亡时目标失效，行为自动以 TARGET_LOST 结束
        ai.chase(entity, player)
                .strategy(ai.getGreedyPathFinder())
                .start();
        ai.showPath(entity, 10);
        player.sendMessage("§a测试实体开始贪心追逐你（周期重算，粒子显示路径轨迹）");
    }

    /** {@code /ai greedy}：主实体贪心寻路到玩家（局部步进，低开销）。 */
    @CommandMapping("greedy")
    public void greedy(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        boolean ok = greedyWalkAndShow(entity, playerPos(player));
        player.sendMessage(ok
                ? "§a测试实体正在以贪心策略寻路前往你（粒子显示路径轨迹）"
                : "§e贪心寻路失败：附近无可行走方向");
    }

    /** {@code /ai chasegreedy}：主实体贪心追逐玩家（同 chase，保留旧命令别名）。 */
    @CommandMapping("chasegreedy")
    public void chaseGreedy(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        ai.chase(entity, player)
                .strategy(ai.getGreedyPathFinder())
                .start();
        ai.showPath(entity, 10);
        player.sendMessage("§a测试实体开始贪心追逐你（周期重算，粒子显示路径轨迹）");
    }

    /** {@code /ai wander [半径]}：主实体游荡。 */
    @CommandMapping(value = "wander", usage = "/ai wander [半径]")
    public void wander(@Sender Player player, @RawArgs String[] args) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        double radius = parseDouble(args.length > 0 ? args[0] : null, 10);
        ai.wander(entity, radius).start();
        player.sendMessage("§a测试实体开始在半径 §e" + radius + " §a内随机游荡");
    }

    /** {@code /ai cover}：主实体寻找掩体（以玩家为威胁）并前往。 */
    @CommandMapping("cover")
    public void cover(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        TacticalPosition pos = ai.findCover(entity, player, 8);
        if (!pos.isPresent()) {
            player.sendMessage("§e附近 8 格内未找到合适的掩体");
            return;
        }
        greedyWalkAndShow(entity, pos.toLevelPosition(entity.getLevel()));
        player.sendMessage("§a测试实体正在贪心前往掩体（遮挡评分 §e" + format(pos.score()) + "§a，粒子显示路径）");
    }

    /** {@code /ai flee}：主实体远离玩家。 */
    @CommandMapping("flee")
    public void flee(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        TacticalPosition pos = ai.findFleePosition(entity, player, 12);
        if (!pos.isPresent()) {
            player.sendMessage("§e附近 12 格内未找到远离路径");
            return;
        }
        greedyWalkAndShow(entity, pos.toLevelPosition(entity.getLevel()));
        player.sendMessage("§a测试实体正在贪心逃离（距你 §e" + format(pos.distanceToThreat()) + " §a格，粒子显示路径）");
    }

    /** {@code /ai flank}：主实体侧翼包抄玩家（单实体）。 */
    @CommandMapping("flank")
    public void flank(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        TacticalPosition pos = ai.findFlankPosition(entity, player, 8);
        if (!pos.isPresent()) {
            player.sendMessage("§e附近 8 格内未找到侧翼位置");
            return;
        }
        greedyWalkAndShow(entity, pos.toLevelPosition(entity.getLevel()));
        player.sendMessage("§a测试实体正在贪心包抄你的侧翼（侧偏评分 §e" + format(pos.score()) + "§a，粒子显示路径）");
    }

    /**
     * {@code /ai flankteam [数量]}：生成多个实体并对玩家发起<b>协同钳形包抄</b>（团队战术）。
     * <p>
     * 演示 {@link AiAPI#flankTarget(List, Entity, double)}：成员以小队来袭方向为统一参考，
     * 在玩家远侧半圆（180°）上均匀展开——两端落在左右两翼、中间位于后方，
     * 形成协调的钳形合围，而非各自为政地聚堆。
     */
    @CommandMapping(value = "flankteam", usage = "/ai flankteam [数量]")
    public void flankTeam(@Sender Player player, @RawArgs String[] args) {
        int count = parseInt(args.length > 0 ? args[0] : null, 4);
        count = Math.max(2, Math.min(count, 12));
        List<Entity> members = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Entity e = spawnEntity(player, "TestNpc");
            if (e != null) {
                members.add(e);
            }
        }
        if (members.isEmpty()) {
            player.sendMessage("§c生成团队实体失败");
            return;
        }
        team.put(player.getName(), members);
        List<TacticalPosition> positions = ai.flankTarget(members, player, 6);
        navigateTeam(player, members, positions);
        player.sendMessage("§a已生成 §e" + members.size() + " §a个实体，正在 6 格半径协同钳形包抄你（两翼展开、均匀合围）");
    }

    /** {@code /ai high}：主实体寻找高地。 */
    @CommandMapping("high")
    public void highGround(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        TacticalPosition pos = ai.findHighGround(entity, 12, 2);
        if (!pos.isPresent()) {
            player.sendMessage("§e附近 12 格内未找到更高位置（需高出至少 2 格）");
            return;
        }
        greedyWalkAndShow(entity, pos.toLevelPosition(entity.getLevel()));
        player.sendMessage("§a测试实体正在贪心前往高地（高度优势 §e" + format(pos.score()) + " §a格，粒子显示路径）");
    }

    /**
     * {@code /ai cansee}：视野判断——主实体能否看到玩家。
     * <p>
     * 同时展示两种语义：
     * <ul>
     *   <li><b>当前朝向可见</b>（FOV 90°，受实体当前面朝方向约束）</li>
     *   <li><b>转头可见</b>（360° 全向，仅看距离 + 视线——AI 转头即可看到）</li>
     * </ul>
     */
    @CommandMapping("cansee")
    public void canSee(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        boolean canSeeFov = ai.see(entity).range(16).fov(90).canSee(player);
        boolean canSeeTurn = ai.see(entity).range(16).canSee360(player);
        // 坐标版"转头可见"演示：以玩家眼部坐标为目标点
        boolean canSeeCoord = ai.see(entity).range(16).canSee(new Vector3(player.x, player.y + 1.5, player.z));
        double dx = entity.x - player.x;
        double dz = entity.z - player.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double angle = ai.see(entity).angleTo(player);
        player.sendMessage("§a===== §f视野判断结果 §a=====");
        player.sendMessage("§7水平距离: §e" + format(dist) + " §7格（阈值 16）");
        player.sendMessage("§7相对朝向夹角: §e" + format(angle) + "° §7（§8正前=0° / 正侧=90° / 正后=180°§7）");
        player.sendMessage("§7当前朝向可见(FOV 90°): " + boolText(canSeeFov));
        player.sendMessage("§7转头可见(360°全向): " + boolText(canSeeTurn));
        player.sendMessage("§7坐标版转头可见(玩家眼部): " + boolText(canSeeCoord));
    }

    /** {@code /ai seeksight}：主实体移动到能看到玩家的位置（占据视野点）。 */
    @CommandMapping("seeksight")
    public void seekSight(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        TacticalPosition pos = ai.findSightPosition(entity, player, 12);
        if (!pos.isPresent()) {
            player.sendMessage("§e附近 12 格内未找到能看到你的合适位置");
            return;
        }
        greedyWalkAndShow(entity, pos.toLevelPosition(entity.getLevel()));
        player.sendMessage("§a测试实体正在贪心移动到能看到你的位置（理想距离 10 格，粒子显示路径）");
    }

    /** {@code /ai seekapprox [半径]}：模糊位置选取——以玩家位置为不确定区域圆心，让实体移动到大致位置。 */
    @CommandMapping(value = "seekapprox", usage = "/ai seekapprox [半径]")
    public void seekApprox(@Sender Player player, @RawArgs String[] args) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        int radius = parseInt(args.length > 0 ? args[0] : null, 5);
        radius = Math.max(1, Math.min(radius, 30));
        TacticalPosition pos = ai.findApproximatePosition(entity, playerPos(player), radius);
        if (!pos.isPresent()) {
            player.sendMessage("§e玩家附近 " + radius + " 格内未找到可站立的搜索点");
            return;
        }
        boolean reached = ai.hasReachedApproximate(entity, playerPos(player), radius);
        greedyWalkAndShow(entity, pos.toLevelPosition(entity.getLevel()));
        player.sendMessage("§a测试实体正在贪心模糊搜索玩家（不确定半径 §e" + radius + " §a格，粒子显示路径）");
        player.sendMessage("§7搜索点: §e" + format(pos.position().x) + ", " + format(pos.position().y) + ", " + format(pos.position().z));
        player.sendMessage("§7是否已到达模糊区域: " + boolText(reached));
    }

    /** {@code /ai surround [数量]}：生成多个实体并包围玩家（团队战术）。 */
    @CommandMapping(value = "surround", usage = "/ai surround [数量]")
    public void surround(@Sender Player player, @RawArgs String[] args) {
        int count = parseInt(args.length > 0 ? args[0] : null, 4);
        count = Math.max(2, Math.min(count, 12));
        List<Entity> members = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Entity e = spawnEntity(player, "TestNpc");
            if (e != null) {
                members.add(e);
            }
        }
        if (members.isEmpty()) {
            player.sendMessage("§c生成团队实体失败");
            return;
        }
        team.put(player.getName(), members);
        List<TacticalPosition> positions = ai.surroundTarget(members, player, 5);
        navigateTeam(player, members, positions);
        player.sendMessage("§a已生成 §e" + members.size() + " §a个实体，正在 5 格半径环绕包围你");
    }

    /** {@code /ai rally [阵型]}：团队以指定阵型集结到玩家位置。 */
    @CommandMapping(value = "rally", usage = "/ai rally [line|column|wedge|circle|square]")
    public void rally(@Sender Player player, @RawArgs String[] args) {
        List<Entity> members = team.get(player.getName());
        if (members == null || members.isEmpty()) {
            player.sendMessage("§c没有团队实体，请先 §f/ai surround §c生成");
            return;
        }
        FormationType formation = parseFormation(args.length > 0 ? args[0] : "wedge");
        List<TacticalPosition> positions = ai.rally(members, playerPos(player), formation, 2);
        navigateTeam(player, members, positions);
        player.sendMessage("§a团队正在以 §e" + formation + " §a阵型集结到你身边（间距 2 格）");
    }

    /** {@code /ai attack}：主实体近战攻击玩家。 */
    @CommandMapping("attack")
    public void attack(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        boolean ok = ai.attack(entity).melee(player, 4.0f).fire();
        player.sendMessage(ok ? "§c测试实体对你发动近战攻击，造成 4 点伤害！" : "§e攻击未生效（可能被事件取消）");
    }

    /** {@code /ai shoot}：主实体向玩家射箭。 */
    @CommandMapping("shoot")
    public void shoot(@Sender Player player) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        boolean ok = ai.attack(entity).arrow(player).fire();
        player.sendMessage(ok ? "§a测试实体向你射出一支箭！" : "§e射箭失败");
    }

    /** {@code /ai stop}：停止主实体的全部 AI 行为（循环行为 + 导航）。 */
    @CommandMapping("stop")
    public void stop(@Sender Player player) {
        Entity entity = getPrimary(player);
        if (entity != null) {
            ai.stop(entity);
        }
        player.sendMessage("§a已停止测试实体的所有 AI 行为（循环行为/导航）");
    }

    /** {@code /ai status}：查看 AI 运行状态。 */
    @CommandMapping("status")
    public void status(@Sender Player player) {
        Entity entity = getPrimary(player);
        player.sendMessage("§a===== §fAI 运行状态 §a=====");
        player.sendMessage("§7主实体: " + (entity != null
                ? "§a存活 §7(id=" + entity.getId() + ")"
                : "§c无（请先 §f/ai spawn§c）"));
        if (entity != null) {
            player.sendMessage("§7  导航中: " + boolText(ai.isNavigating(entity)));
            player.sendMessage("§7  循环行为中(chase/wander/loop): " + boolText(ai.isLooping(entity)));
            player.sendMessage("§7  坐标: §f" + (int) entity.x + ", " + (int) entity.y + ", " + (int) entity.z);
        }
        List<Entity> t = team.get(player.getName());
        player.sendMessage("§7团队实体: §f" + (t == null ? 0 : t.size()));
        player.sendMessage("§7全局活跃导航器: §e" + ai.activeNavigatorCount());
    }

    /** {@code /ai clear}：移除所有测试实体。 */
    @CommandMapping("clear")
    public void clear(@Sender Player player) {
        int removed = 0;
        Entity p = primary.remove(player.getName());
        if (p != null && !p.closed) {
            p.close();
            removed++;
        }
        List<Entity> t = team.remove(player.getName());
        if (t != null) {
            for (Entity e : t) {
                if (e != null && !e.closed) {
                    e.close();
                    removed++;
                }
            }
        }
        player.sendMessage("§a已移除 §e" + removed + " §a个测试实体");
    }

    /** {@code /ai showpath [秒数]}：用粒子显示主实体当前寻路路径（默认 10 秒）。 */
    @CommandMapping(value = "showpath", usage = "/ai showpath [秒数]")
    public void showPath(@Sender Player player, @RawArgs String[] args) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        int duration = parseInt(args.length > 0 ? args[0] : null, 10);
        boolean ok = ai.showPath(entity, duration);
        if (ok) {
            player.sendMessage("§a正在显示寻路路径（§e" + duration + " §a秒）");
        } else {
            player.sendMessage("§c无法显示路径——实体未导航或 Plugin 未绑定");
        }
    }

    /** {@code /ai gotocoord <x> <y> <z>}：主实体寻路到指定坐标。 */
    @CommandMapping(value = "gotocoord", usage = "/ai gotocoord <x> <y> <z>")
    public void gotoCoord(@Sender Player player, @RawArgs String[] args) {
        Entity entity = requirePrimary(player);
        if (entity == null) {
            return;
        }
        if (args.length < 3) {
            player.sendMessage("§c用法：/ai gotocoord <x> <y> <z>");
            return;
        }
        try {
            double x = Double.parseDouble(args[0]);
            double y = Double.parseDouble(args[1]);
            double z = Double.parseDouble(args[2]);
            greedyWalkAndShow(entity, new Vector3(x, y, z));
            player.sendMessage("§a主实体正在贪心寻路到 §e(" + x + ", " + y + ", " + z + ")§a（粒子显示路径）");
        } catch (NumberFormatException e) {
            player.sendMessage("§c坐标必须是数字");
        }
    }

    /**
     * 插件禁用时清理所有玩家的测试实体，避免实体残留。
     * <p>
     * 由 {@code ExamplePlugin.onDisable} 调用。
     */
    public static void clearAll() {
        for (Entity e : primary.values()) {
            if (e != null && !e.closed) {
                e.close();
            }
        }
        primary.clear();
        for (List<Entity> list : team.values()) {
            for (Entity e : list) {
                if (e != null && !e.closed) {
                    e.close();
                }
            }
        }
        team.clear();
    }

    // ============================ 辅助方法 ============================

    /** 玩家脚部坐标。 */
    private static Vector3 playerPos(Player player) {
        return new Vector3(player.x, player.y, player.z);
    }

    /**
     * 在玩家前方 3 格生成指定类型的实体。
     *
     * @param player 玩家
     * @param type   实体注册名（如 {@code "Zombie"}）
     * @return 已生成的实体，或 null（类型未注册）
     */
    private static Entity spawnEntity(Player player, String type) {
        Vector3 dir = player.getDirectionVector();
        Position pos = new Position(
                player.x + dir.x * 3,
                player.y,
                player.z + dir.z * 3,
                player.getLevel());
        // 默认使用 TestNpcEntity（EntityHuman 子类，无怪物 AI），
        // 使导航器「设置 motion + 主动 move()」能稳定驱动其沿路径行走。
        // 若显式指定原版怪物（Zombie/Skeleton 等）仍可生成，但其自带 AI 会干扰寻路效果。
        if (type == null || type.isEmpty()
                || type.equalsIgnoreCase("TestNpc")
                || type.equalsIgnoreCase("Npc")) {
            return TestNpcEntity.spawnAt(pos);
        }
        Entity entity = Entity.createEntity(type, pos);
        if (entity != null) {
            entity.spawnToAll();
        }
        return entity;
    }

    /**
     * 获取玩家当前有效的主测试实体（已过滤死亡/关闭的）。
     *
     * @param player 玩家
     * @return 有效实体，或 null
     */
    private static Entity getPrimary(Player player) {
        Entity entity = primary.get(player.getName());
        if (entity == null || entity.closed || !entity.isAlive()) {
            return null;
        }
        return entity;
    }

    /**
     * 获取主测试实体，若不存在则向玩家发送提示。
     *
     * @param player 玩家
     * @return 有效实体，或 null（已发送提示）
     */
    private static Entity requirePrimary(Player player) {
        Entity entity = getPrimary(player);
        if (entity == null) {
            player.sendMessage("§c没有可用的测试实体，请先 §f/ai spawn §c生成一个");
        }
        return entity;
    }

    /**
     * 让一组实体分别导航到对应的战术位置。
     *
     * @param player    玩家（提供世界）
     * @param members   实体列表
     * @param positions 战术位置列表（与 members 一一对应）
     */
    private static void navigateTeam(Player player, List<Entity> members, List<TacticalPosition> positions) {
        for (int i = 0; i < members.size() && i < positions.size(); i++) {
            TacticalPosition pos = positions.get(i);
            if (pos.isPresent()) {
                greedyWalkAndShow(members.get(i), pos.toLevelPosition(player.getLevel()));
            }
        }
    }

    /**
     * 贪心导航到目标并自动显示路径（粒子轨迹）。
     * <p>
     * 示例默认采用贪心策略（低开销局部步进），并在寻路成功后用粒子持续显示路径轨迹，
     * 便于在游戏内直观观察实体的行进路线。去掉 {@code strategy(...)} 即用默认 A* 策略。
     *
     * @param entity 实体
     * @param target 目标坐标（Position / Vector3）
     * @return true 表示寻路成功并已启动导航
     */
    private static boolean greedyWalkAndShow(Entity entity, Vector3 target) {
        boolean ok = ai.walk(entity)
                .strategy(ai.getGreedyPathFinder())
                .to(target)
                .start() != null;
        if (ok) {
            ai.showPath(entity, 10);
        }
        return ok;
    }

    /** 解析阵型名称，无法识别时默认楔形（WEDGE）。 */
    private static FormationType parseFormation(String s) {
        if (s == null) {
            return FormationType.WEDGE;
        }
        return switch (s.toLowerCase()) {
            case "line" -> FormationType.LINE;
            case "column" -> FormationType.COLUMN;
            case "wedge" -> FormationType.WEDGE;
            case "circle" -> FormationType.CIRCLE;
            case "square" -> FormationType.SQUARE;
            default -> FormationType.WEDGE;
        };
    }

    /** 安全解析 double，失败返回默认值。 */
    private static double parseDouble(String s, double def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 安全解析 int，失败返回默认值。 */
    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 格式化评分（保留 1 位小数）。 */
    private static String format(double v) {
        return String.format("%.1f", v);
    }

    /** 布尔值转带色文本。 */
    private static String boolText(boolean v) {
        return v ? "§e是" : "§7否";
    }
}
