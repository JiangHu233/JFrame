package io.github.JiangHu.jframe.example.ecs.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.level.Position;
import io.github.JiangHu.jframe.command.annotation.CommandController;
import io.github.JiangHu.jframe.command.annotation.CommandMapping;
import io.github.JiangHu.jframe.command.annotation.PathVariable;
import io.github.JiangHu.jframe.command.annotation.Sender;
import io.github.JiangHu.jframe.engine.ecs.EcsAPI;
import io.github.JiangHu.jframe.engine.ecs.entity.EcsEntity;
import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.gameobject.GameObjectDefinition;
import io.github.JiangHu.jframe.example.ecs.component.EliteComponent;
import io.github.JiangHu.jframe.example.ecs.component.GhostMarkComponent;
import io.github.JiangHu.jframe.example.ecs.component.GuardAuraComponent;
import io.github.JiangHu.jframe.example.ecs.component.MagicChestComponent;
import io.github.JiangHu.jframe.example.ecs.component.WaveStateComponent;

import java.util.Map;

/**
 * ECS 引擎<b>实机测试控制器</b>（根命令 {@code /ecs}）。
 *
 * <h3>命令一览</h3>
 * <pre>
 *   /ecs                  显示帮助
 *   /ecs start            启动引擎（存量回溯 + 世界 diff + 事件桥 + 系统 tick），回显存量挂载统计
 *   /ecs stop             停止引擎（全部组件逆序 onUnmount，控制台逐条可见）
 *   /ecs status           引擎状态与各演示组件挂载数
 *   /ecs list             已加载的 GameObject 定义清单
 *   /ecs spawn <定义名>    以自身位置现场生成（night-ghosts / wave-manager / arena；未 start 也可用）
 *   /ecs wave             一键波次：自动补建管理器 + 生成一波幽灵
 *   /ecs arena            生成竞技场（写 marker 文件，POJO 产物代理演示）
 *   /ecs arenaoff         回收全部竞技场（destroy 触发 despawn 删文件 + 广播）
 *   /ecs refresh          全实体手动重评（演示只补挂不卸载）
 * </pre>
 *
 * <h3>依赖注入</h3>
 * {@code jframe_command} 通过<b>无参构造</b>实例化控制器，因此用静态字段持有 {@link EcsAPI}，
 * 由 {@code ExamplePlugin.onEnable} 在 scan 之前调用 {@link #setEcs} 注入。
 *
 * @see EcsAPI
 */
@CommandController("ecs")
public class EcsTestController {

    /** ECS 服务门面（由 ExamplePlugin 在 scan 前静态注入） */
    private static EcsAPI ecs;

    /**
     * 注入 ECS 服务门面。
     *
     * @param ecsAPI ECS 服务门面
     */
    public static void setEcs(EcsAPI ecsAPI) {
        ecs = ecsAPI;
    }

    // ============================ 子命令 ============================

    /** {@code /ecs}：显示帮助。 */
    @CommandMapping(value = "", desc = "ECS 实机测试命令（显示帮助）")
    public void help(@Sender Player player) {
        player.sendMessage("§a===== §f/ecs ECS 实机测试 §a=====");
        player.sendMessage("§7/ecs start §8→ §f启动引擎（存量回溯，回显挂载统计）");
        player.sendMessage("§7/ecs stop §8→ §f停止引擎（全部组件卸载）");
        player.sendMessage("§7/ecs status §8→ §f状态与各组件挂载数");
        player.sendMessage("§7/ecs list §8→ §f已加载的 GameObject 定义");
        player.sendMessage("§7/ecs spawn <定义名> §8→ §f以自身位置现场生成（未 start 也可用）");
        player.sendMessage("§7/ecs wave §8→ §f一键波次（自动补建管理器 + 生成幽灵群）");
        player.sendMessage("§7/ecs arena §8→ §f生成竞技场（写 marker 文件）");
        player.sendMessage("§7/ecs arenaoff §8→ §f回收竞技场（despawn 删文件 + 广播）");
        player.sendMessage("§7/ecs refresh §8→ §f全实体手动重评（只补挂不卸载）");
        player.sendMessage("§7实机现象：右键箱子/打开箱子出提示；打僵尸出重击提示；僵尸名牌每秒显血；");
        player.sendMessage("§7出生点 64 格内右键箱子出§e两条§7消息（多定义并集）");
    }

    /** {@code /ecs start}：启动引擎并回显存量回溯统计。 */
    @CommandMapping(value = "start", desc = "启动 ECS 引擎（存量回溯）")
    public void start(@Sender CommandSender sender) {
        try {
            ecs.start();
        } catch (IllegalStateException e) {
            sender.sendMessage("§c启动失败：" + e.getMessage());
            return;
        }
        sender.sendMessage("§aECS 引擎已启动，存量识别：");
        sender.sendMessage("§7  魔法箱 §e" + ecs.query(MagicChestComponent.class).size()
                + " §7| 精英僵尸 §e" + ecs.query(EliteComponent.class).size()
                + " §7| 守卫光环 §e" + ecs.query(GuardAuraComponent.class).size()
                + " §7| 幽灵 §e" + ecs.query(GhostMarkComponent.class).size()
                + " §7| 波次管理器 §e" + ecs.query(WaveStateComponent.class).size());
        sender.sendMessage("§7之后新放的箱子/新刷的僵尸会在下一秒自动挂载（控制台有日志）");
    }

    /** {@code /ecs stop}：停止引擎。 */
    @CommandMapping(value = "stop", desc = "停止 ECS 引擎（全部卸载）")
    public void stop(@Sender CommandSender sender) {
        ecs.stop();
        sender.sendMessage("§aECS 引擎已停止，全部组件已逆序卸载（控制台可见 onUnmount 日志）");
    }

    /** {@code /ecs status}：引擎状态与组件挂载数。 */
    @CommandMapping(value = "status", desc = "查看 ECS 状态")
    public void status(@Sender CommandSender sender) {
        sender.sendMessage("§a===== §fECS 状态 §a=====");
        sender.sendMessage("§7运行中: " + (ecs.isStarted() ? "§a是" : "§c否（/ecs start 启动）"));
        sender.sendMessage("§7GameObject 定义: §e" + ecs.definitions().size() + " §7| 实体: §e" + ecs.entities().size());
        sender.sendMessage("§7魔法箱 §e" + ecs.query(MagicChestComponent.class).size()
                + " §7| 精英僵尸 §e" + ecs.query(EliteComponent.class).size()
                + " §7| 守卫光环 §e" + ecs.query(GuardAuraComponent.class).size()
                + " §7| 幽灵 §e" + ecs.query(GhostMarkComponent.class).size());
        if (!ecs.query(WaveStateComponent.class).isEmpty()) {
            WaveStateComponent state = ecs.query(WaveStateComponent.class).get(0).get(WaveStateComponent.class);
            sender.sendMessage("§7波次: §e" + state.wave() + " §7| 剩余幽灵: §e" + state.mobsLeft());
        }
    }

    /** {@code /ecs list}：已加载定义清单。 */
    @CommandMapping(value = "list", desc = "查看已加载的 GameObject 定义")
    public void list(@Sender CommandSender sender) {
        sender.sendMessage("§a===== §fGameObject 定义（" + ecs.definitions().size() + " 条）§a=====");
        for (Map.Entry<String, GameObjectDefinition> entry : ecs.definitions().entrySet()) {
            GameObjectDefinition def = entry.getValue();
            String selector = def.selector() == null ? "-" : def.selector().getClass().getSimpleName();
            String spawner = def.spawner() == null ? "-" : def.spawner().getClass().getSimpleName();
            StringBuilder compIds = new StringBuilder();
            def.components().forEach(spec -> compIds.append(compIds.length() == 0 ? "" : ", ").append(spec.id()));
            sender.sendMessage("§e" + entry.getKey() + " §7selector=§f" + selector
                    + " §7spawn=§f" + spawner + " §7components=§f" + compIds);
        }
    }

    /** {@code /ecs spawn <定义名>}：以自身位置现场生成。 */
    @CommandMapping(value = "spawn {def}", desc = "以自身位置现场生成", usage = "/ecs spawn <定义名>")
    public void spawn(@Sender Player player, @PathVariable("def") String def) {
        Position pos = player.getPosition();
        try {
            EcsEntity entity = ecs.spawn(def, pos);
            player.sendMessage("§a已生成 §e" + def + " §a→ " + entity);
        } catch (EcsConfigException e) {
            player.sendMessage("§c生成失败：" + e.getMessage());
        }
    }

    /** {@code /ecs wave}：一键波次（自动补建管理器 + 循环单生成幽灵群）。 */
    @CommandMapping(value = "wave", desc = "一键波次（自动补建管理器 + 生成幽灵群）")
    public void wave(@Sender Player player) {
        if (ecs.query(WaveStateComponent.class).isEmpty()) {
            ecs.spawn("wave-manager", null);
            player.sendMessage("§7已自动补建波次管理器（纯虚拟 gameobject，不占世界实体）");
        }
        // 主流引擎语义：spawner 一次调用只生成一个实体，多数量由调用方循环
        int spawned = 0;
        try {
            for (int i = 0; i < 2; i++) {
                if (ecs.spawn("night-ghosts", player.getPosition()) != null) {
                    spawned++;
                }
            }
        } catch (EcsConfigException e) {
            player.sendMessage("§c生成失败：" + e.getMessage());
            return;
        }
        if (spawned == 0) {
            player.sendMessage("§c幽灵生成失败（控制台有 type 诊断日志），未开波");
            return;
        }
        WaveStateComponent state = ecs.query(WaveStateComponent.class).get(0).get(WaveStateComponent.class);
        player.sendMessage("§a第 §e" + state.wave() + " §a波已在你身边生成 §e" + spawned
                + " §a只幽灵，杀光后看全服广播");
    }

    /** {@code /ecs arena}：生成竞技场（spawn 写 marker 文件，POJO 产物代理）。 */
    @CommandMapping(value = "arena", desc = "生成竞技场（写 marker 文件）")
    public void arena(@Sender Player player) {
        try {
            EcsEntity entity = ecs.spawn("arena", null);
            if (entity.binding() instanceof io.github.JiangHu.jframe.example.ecs.spawner.ArenaRef arena) {
                player.sendMessage("§a竞技场 §e" + arena.name() + " §a已生成 → " + arena.markerPath());
            } else {
                player.sendMessage("§a竞技场已生成 → " + entity);
            }
            player.sendMessage("§7/ecs arenaoff 回收（despawn 删 marker + 全服广播）");
        } catch (EcsConfigException e) {
            player.sendMessage("§c生成失败：" + e.getMessage());
        }
    }

    /** {@code /ecs arenaoff}：回收全部竞技场（destroy 触发 despawn 回调）。 */
    @CommandMapping(value = "arenaoff", desc = "回收全部竞技场（despawn 删文件 + 广播）")
    public void arenaoff(@Sender CommandSender sender) {
        int recycled = 0;
        for (EcsEntity entity : ecs.entities()) {
            if (entity.hasTag("arena")) {
                entity.destroy();   // despawn 回调：删 marker 文件 + 广播
                recycled++;
            }
        }
        sender.sendMessage(recycled == 0
                ? "§7当前没有存活竞技场（/ecs arena 生成）"
                : "§a已回收 §e" + recycled + " §a个竞技场（marker 文件已删除，见广播）");
    }

    /** {@code /ecs refresh}：全实体手动重评（只补挂不卸载）。 */
    @CommandMapping(value = "refresh", desc = "全实体手动重评（只补挂不卸载）")
    public void refresh(@Sender CommandSender sender) {
        int before = ecs.query(MagicChestComponent.class).size() + ecs.query(EliteComponent.class).size();
        for (EcsEntity entity : ecs.entities()) {
            ecs.refresh(entity);
        }
        int after = ecs.query(MagicChestComponent.class).size() + ecs.query(EliteComponent.class).size();
        sender.sendMessage("§a已对 §e" + ecs.entities().size() + " §a个实体重评，补挂组件 §e" + Math.max(0, after - before) + " §a个");
    }
}
