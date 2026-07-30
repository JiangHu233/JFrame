package io.github.JiangHu.jframe.ai.navigation;

import cn.nukkit.entity.Entity;
import io.github.JiangHu.jframe.ai.pathfinding.BlockNode;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 实体导航器：驱动一个 {@link Entity} 沿 {@link PathResult} 给出的方块路径逐点行进。
 * <p>
 * 导航器是<b>有状态、单次使用</b>的控制器：每个 {@code Navigator} 绑定一个实体与一条路径，
 * 由 {@link NavigatorManager} 每 tick 调用 {@link #tick()} 推进，直至到达终点或被中止。
 *
 * <h3>移动模型</h3>
 * 采用"设置速度向量"的方式驱动实体：
 * <ul>
 *   <li>每 tick 计算指向当前路径点的水平单位向量，乘以 {@link #speed} 写入 {@code entity.motionX/motionZ}</li>
 *   <li>将 {@code entity.yaw} 旋转至行进方向，使实体面朝前方</li>
 *   <li>当目标点高于当前脚部且实体未在上升时，给予 {@code motionY} 跳跃冲量翻越台阶</li>
 *   <li>重力、摩擦、碰撞由实体自身的物理更新（{@code onUpdate}）处理</li>
 * </ul>
 * 这种方式兼容大多数 Nukkit 生物实体与自定义实体；对由客户端控制的 {@link cn.nukkit.Player} 无效。
 *
 * <h3>鲁棒性</h3>
 * <ul>
 *   <li>实体死亡、关闭或切换世界时自动终止</li>
 *   <li>内置卡住检测：连续若干 tick 位移过小则跳过当前路径点，避免卡死</li>
 *   <li>到达终点时触发 {@link #onComplete} 回调</li>
 * </ul>
 *
 * @see NavigatorManager
 * @see PathResult
 */
public class Navigator {

    /** 到达单个路径点的判定半径（方块） */
    private static final double ARRIVAL_RADIUS = 0.6;
    /** 默认行进速度（方块/tick，约 5 格/秒） */
    public static final double DEFAULT_SPEED = 0.25;
    /** 默认跳跃冲量（约可越过 1 格高） */
    public static final double DEFAULT_JUMP_FORCE = 0.42;
    /** 卡住判定：连续多少 tick 位移不足即视为卡住 */
    private static final int STUCK_THRESHOLD = 12;
    /** 卡住判定的最小位移（方块） */
    private static final double STUCK_MOVE_DELTA = 0.02;
    /** 空中水平控制系数：实体腾空时仅施加该比例的水平推力，保留物理惯性，避免"滑翔/腾空" */
    private static final double AIR_CONTROL = 0.4;
    /** 跳跃冷却（tick）：着地后需等待若干 tick 才能再次起跳，防止连跳与抖动 */
    private static final int JUMP_COOLDOWN_TICKS = 4;

    private final Entity entity;
    private final List<BlockNode> path;
    private final double speed;
    private final double jumpForce;

    /** 下一个目标点在 path 中的索引（从 1 开始，0 为起点） */
    private int index = 1;
    private boolean finished;
    private Consumer<Navigator> onComplete;

    // 卡住检测状态
    private double lastX;
    private double lastZ;
    private int stuckTicks;
    /** 跳跃冷却剩余 tick */
    private int jumpCooldown;

    /**
     * 以默认速度构造导航器。
     *
     * @param entity 被导航的实体
     * @param result 寻路结果（必须成功）
     */
    public Navigator(Entity entity, PathResult result) {
        this(entity, result, DEFAULT_SPEED);
    }

    /**
     * 构造导航器。
     *
     * @param entity 被导航的实体
     * @param result 寻路结果（必须成功，否则立即完成）
     * @param speed  行进速度（方块/tick）
     */
    public Navigator(Entity entity, PathResult result, double speed) {
        this(entity, result, speed, DEFAULT_JUMP_FORCE);
    }

    /**
     * 构造导航器（完整参数）。
     *
     * @param entity    被导航的实体
     * @param result    寻路结果
     * @param speed     行进速度（方块/tick）
     * @param jumpForce 跳跃冲量（motionY）
     */
    public Navigator(Entity entity, PathResult result, double speed, double jumpForce) {
        this.entity = entity;
        this.path = result == null ? List.of() : result.getNodes();
        this.speed = Math.max(0.01, speed);
        this.jumpForce = jumpForce;
        if (path.isEmpty()) {
            finished = true;
        } else {
            this.lastX = entity.x;
            this.lastZ = entity.z;
        }
    }

    /**
     * 设置到达终点时的回调。
     *
     * @param onComplete 回调，接收本导航器
     * @return this
     */
    public Navigator onComplete(Consumer<Navigator> onComplete) {
        this.onComplete = onComplete;
        return this;
    }

    /**
     * 由调度器每 tick 调用一次，推进实体移动。
     *
     * @return true 表示仍在导航中；false 表示已结束（应从管理器移除）
     */
    public boolean tick() {
        if (finished) {
            return false;
        }
        // 终止条件：实体失效
        if (entity == null || entity.closed || !entity.isAlive()) {
            finish();
            return false;
        }
        // 路径已走完
        if (index >= path.size()) {
            finish();
            return false;
        }

        BlockNode target = path.get(index);
        double dx = (target.x + 0.5) - entity.x;
        double dz = (target.z + 0.5) - entity.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        // 到达当前路径点 → 前进到下一个
        if (dist < ARRIVAL_RADIUS) {
            index++;
            stuckTicks = 0;
            return true;
        }

        // 设置水平速度向量（归一化 × 速度）。
        // 关键：实体腾空（!onGround）时仅施加 AIR_CONTROL 比例的水平推力，
        // 让重力主导垂直运动，避免空中被持续全速平推造成"滑翔/腾空移动"。
        double inv = 1.0 / dist;
        double control = entity.onGround ? 1.0 : AIR_CONTROL;
        entity.motionX = dx * inv * speed * control;
        entity.motionZ = dz * inv * speed * control;

        // 朝向行进方向（Nukkit yaw：0 朝 +Z，顺时针为正）
        entity.yaw = Math.toDegrees(Math.atan2(-dx, dz));

        // 跳跃：仅在着地（onGround）时触发，且目标点更高。
        // 修复腾空飞行：原条件仅判断 motionY<=0.05，实体下落阶段该条件恒成立，
        // 会被每 tick 反复赋予跳跃冲量导致永不落地、持续上升。加入 onGround 闸门后，
        // 实体必须先落地才能再次起跳，跳跃弧线自然，不再腾空。
        int feetY = entity.getFloorY();
        if (entity.onGround && target.y > feetY && jumpCooldown <= 0) {
            entity.motionY = jumpForce;
            jumpCooldown = JUMP_COOLDOWN_TICKS;
        }
        if (jumpCooldown > 0) {
            jumpCooldown--;
        }

        // 主动调用 move() 完成本 tick 的实际位移（参考 RsNPC 的 processMove）。
        // 关键修复：仅设置 motionX/Y/Z 不足以让实体移动——
        //   1) 原生怪物（Zombie 等）自带 AI，每 tick 会覆盖外部写入的 motion；
        //   2) EntityHuman 不会在 onUpdate 中自动把 motion 转成位移。
        // 因此导航器必须主动调用 move() 应用位移（move 内部含碰撞检测，不会穿墙）。
        // 配合 TestNpcEntity（EntityHuman 子类，无怪物 AI）可稳定驱动 NPC 沿路径行走。
        entity.move(entity.motionX, entity.motionY, entity.motionZ);

        // 卡住检测：若长时间未移动则跳过当前点
        double moved = Math.sqrt(
                (entity.x - lastX) * (entity.x - lastX) + (entity.z - lastZ) * (entity.z - lastZ));
        if (moved < STUCK_MOVE_DELTA) {
            if (++stuckTicks > STUCK_THRESHOLD) {
                index++;
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        lastX = entity.x;
        lastZ = entity.z;
        return true;
    }

    /**
     * 主动停止导航（清零水平速度）。
     */
    public void stop() {
        if (!finished && entity != null && !entity.closed) {
            entity.motionX = 0;
            entity.motionZ = 0;
        }
        finish();
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (onComplete != null) {
            try {
                onComplete.accept(this);
            } catch (Exception ignored) {
                // 回调异常不影响管理器
            }
        }
    }

    public boolean isFinished() {
        return finished;
    }

    public Entity getEntity() {
        return entity;
    }

    /**
     * 当前目标点索引（用于观察进度）。
     *
     * @return 索引
     */
    public int currentIndex() {
        return index;
    }

    /**
     * 路径总点数。
     *
     * @return 点数
     */
    public int pathLength() {
        return path.size();
    }

    /**
     * 获取完整路径（不可变视图）。
     * <p>
     * 返回的列表是只读的，调用者不应尝试修改。路径包含起点（index 0）到终点的所有方块节点。
     *
     * @return 完整路径（不可变），若路径为空返回空列表
     */
    public List<BlockNode> getPath() {
        return Collections.unmodifiableList(path);
    }

    /**
     * 获取剩余未走过的路径（从当前目标点到终点）。
     * <p>
     * 随着导航推进，{@link #currentIndex()} 递增，剩余路径逐渐缩短。
     * 返回的是新列表副本，调用者可自由修改。
     *
     * @return 剩余路径（新列表），若已到达终点或路径为空返回空列表
     */
    public List<BlockNode> getRemainingPath() {
        if (finished || index >= path.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(path.subList(index, path.size()));
    }
}
