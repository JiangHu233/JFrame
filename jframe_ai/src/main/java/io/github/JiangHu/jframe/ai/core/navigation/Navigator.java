package io.github.JiangHu.jframe.ai.core.navigation;

import cn.nukkit.entity.Entity;
import io.github.JiangHu.jframe.ai.core.gaze.Gaze;
import io.github.JiangHu.jframe.ai.core.gaze.GazeContext;
import io.github.JiangHu.jframe.ai.core.gaze.MovementGaze;
import io.github.JiangHu.jframe.ai.pathfinding.PathResult;
import io.github.JiangHu.jframe.ai.pathfinding.astar.AStarNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 实体导航器：驱动一个 {@link Entity} 沿 {@link PathResult} 给出的方块路径逐点行进。
 * <p>
 * 导航器是<b>有状态</b>的控制器：每个 {@code Navigator} 绑定一个实体与一条路径，
 * 由 {@link NavigatorManager} 每 tick 调用 {@link #tick()} 推进，直至到达终点或被中止。
 * 段与段之间可经 {@link #retarget(PathResult)} 复用同一实例无缝续走（保留速度与视角状态）。
 *
 * <h3>移动模型</h3>
 * 采用"设置速度向量"的方式驱动实体：
 * <ul>
 *   <li>每 tick 计算指向当前路径点的水平单位向量，乘以 {@link #speed} 写入 {@code entity.motionX/motionZ}</li>
 *   <li>朝向表现交由 {@link Gaze 视角修正器}决定（默认 {@link MovementGaze}：头身同向朝移动方向）</li>
 *   <li>当目标点高于当前脚部且实体未在上升时，给予 {@code motionY} 跳跃冲量翻越台阶</li>
 *   <li>重力、摩擦、碰撞由实体自身的物理更新（{@code onUpdate}）处理</li>
 * </ul>
 * 这种方式兼容大多数 Nukkit 生物实体与自定义实体；对由客户端控制的 {@link cn.nukkit.Player} 无效。
 *
 * <h3>无缝节点过渡</h3>
 * 到达路径点的 tick 不空转：跳过所有已到达节点后<b>当 tick 继续</b>朝下一个节点移动，
 * 消除旧版"每节点固定停 1 tick（50ms）"的段落感。
 *
 * <h3>击退兼容</h3>
 * 复刻 Nukkit 原生生物的 {@code knockbackTicks} 保护期：检测到击退特征
 * （被击飞抬升 {@code motionY}，或腾空且水平速度远超导航速度）时进入恢复期，
 * 期间<b>不覆盖</b> motion、仅应用现有惯性位移（{@code move}），让击退惯性自然滑行；
 * 落地或惯性衰减到导航速度以下后自动恢复寻路。自身跳跃（由本导航器设置的 motionY）不计入击退。
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
 * @see Gaze
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
    /** 击退判定：水平速度超过导航速度的该倍数视为外力注入（击退/爆炸等） */
    private static final double KNOCKBACK_SPEED_RATIO = 1.6;
    /** 击退判定：motionY 抬升超过该值视为被击飞（排除自身跳跃） */
    private static final double KNOCKBACK_LIFT_Y = 0.2;
    /** 击退恢复期上限（tick）：期间不覆盖 motion，让惯性自然衰减 */
    private static final int RECOVER_MAX_TICKS = 15;

    private final Entity entity;
    /** 当前路径（retarget 可替换，故非 final；节点列表本身不可变） */
    private List<AStarNode> path;
    private final double speed;
    private final double jumpForce;
    /** 视角修正器（默认头身朝移动方向；可经 {@link #gaze(Gaze)} 替换） */
    private Gaze gaze = new MovementGaze();

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
    /** 击退恢复期剩余 tick（>0 表示正处于击退惯性滑行期，不覆盖 motion） */
    private int recoverTicks;
    /** 击退水平速度判定阈值平方（= KNOCKBACK_SPEED_RATIO² × speed²，构造时计算） */
    private double knockSpeedSq;
    /** 上一 tick 是否由本导航器主动设置了跳跃冲量（用于排除自身跳跃的 motionY 抬升） */
    private boolean jumpedLastTick;

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
        this.knockSpeedSq = (KNOCKBACK_SPEED_RATIO * this.speed) * (KNOCKBACK_SPEED_RATIO * this.speed);
        if (path.isEmpty()) {
            finished = true;
        } else {
            this.lastX = entity.x;
            this.lastZ = entity.z;
        }
    }

    /**
     * 设置视角修正器（导航启动前或段切换时调用）。
     * <p>
     * 修正器实例随本导航器独享；{@code null} 重置为默认 {@link MovementGaze}。
     *
     * @param gaze 修正器
     * @return this
     */
    public Navigator gaze(Gaze gaze) {
        this.gaze = gaze != null ? gaze : new MovementGaze();
        return this;
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

        // 选择目标节点：跳过所有已到达的节点后取第一个未到达者。
        // 关键修复：旧版到达节点当 tick 只 index++ 不移动（每节点固定停 1 tick，
        // 20tps 下每格一次 50ms 卡顿）；现在跳过后当 tick 继续执行移动逻辑，无缝过渡。
        AStarNode target = path.get(index);
        double dx = (target.x + 0.5) - entity.x;
        double dz = (target.z + 0.5) - entity.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        while (dist < ARRIVAL_RADIUS) {
            index++;
            stuckTicks = 0;
            if (index >= path.size()) {
                // 连续跳过至路径末尾：本段走完
                finish();
                return false;
            }
            target = path.get(index);
            dx = (target.x + 0.5) - entity.x;
            dz = (target.z + 0.5) - entity.z;
            dist = Math.sqrt(dx * dx + dz * dz);
        }

        // —— 击退/外力兼容：复刻 Nukkit 原生 knockbackTicks 保护期 ——
        // 检测到击退特征（被击飞抬升 motionY，或腾空且水平速度远超导航速度）时进入恢复期：
        // 期间不覆盖 motion、仅应用现有惯性位移（move），让击退惯性自然滑行；
        // 落地或惯性衰减到导航速度以下后自动恢复寻路。
        // 排除自身跳跃：jumpedLastTick 标记上一 tick 是否由本导航器设置了跳跃冲量。
        double hSq = entity.motionX * entity.motionX + entity.motionZ * entity.motionZ;
        boolean lifted = entity.motionY > KNOCKBACK_LIFT_Y && !jumpedLastTick;
        boolean hurled = !entity.onGround && hSq > knockSpeedSq;
        if (recoverTicks > 0 || lifted || hurled) {
            if (lifted || hurled) {
                recoverTicks = RECOVER_MAX_TICKS;
            } else {
                recoverTicks--;
            }
            boolean settled = entity.onGround && hSq < speed * speed;
            if (recoverTicks > 0 && !settled) {
                // 恢复期：保留击退惯性，仅应用位移；朝向跟随实际运动；不推进路径节点
                gaze.apply(new GazeContext(entity, entity.motionX, entity.motionZ, target));
                entity.move(entity.motionX, entity.motionY, entity.motionZ);
                jumpedLastTick = false;
                updateStuckCheck();
                return true;
            }
            recoverTicks = 0;
        }

        // 设置水平速度向量（归一化 × 速度）。
        // 关键：实体腾空（!onGround）时仅施加 AIR_CONTROL 比例的水平推力，
        // 让重力主导垂直运动，避免空中被持续全速平推造成"滑翔/腾空移动"。
        double inv = 1.0 / dist;
        double control = entity.onGround ? 1.0 : AIR_CONTROL;
        entity.motionX = dx * inv * speed * control;
        entity.motionZ = dz * inv * speed * control;

        // 视角修正：朝向表现交由修正器决定（默认头身同向朝移动方向，
        // 并同步写 headYaw——修复"身体转了、头保持旧朝向"的侧头问题）。
        gaze.apply(new GazeContext(entity, entity.motionX, entity.motionZ, target));

        // 跳跃：仅在着地（onGround）时触发，且目标点更高。
        // 修复腾空飞行：原条件仅判断 motionY<=0.05，实体下落阶段该条件恒成立，
        // 会被每 tick 反复赋予跳跃冲量导致永不落地、持续上升。加入 onGround 闸门后，
        // 实体必须先落地才能再次起跳，跳跃弧线自然，不再腾空。
        int feetY = entity.getFloorY();
        boolean selfJumped = false;
        if (entity.onGround && target.y > feetY && jumpCooldown <= 0) {
            entity.motionY = jumpForce;
            jumpCooldown = JUMP_COOLDOWN_TICKS;
            selfJumped = true;
        }
        jumpedLastTick = selfJumped;
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

        updateStuckCheck();
        return true;
    }

    /**
     * 卡住检测：若连续若干 tick 位移过小则跳过当前路径点，避免卡死。
     * <p>
     * 正常导航与击退恢复期共用：恢复期内击退位移大、moved 大，会重置 stuckTicks，
     * 不会误跳节点；正常导航卡墙时累计触发跳点。
     */
    private void updateStuckCheck() {
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
    }

    /**
     * 重定向（复活式续走）：以新路径替换当前路径，从距实体最近的节点继续。
     * <p>
     * 与"停止旧导航器 + 从路径头重建"不同，本方法：
     * <ul>
     *   <li><b>保留速度</b>：不清零 motionX/motionZ，段切换瞬间速度不中断（消除顿挫）</li>
     *   <li><b>保留视角状态</b>：修正器（含 {@code SmoothGaze} 的平滑状态）跨段延续</li>
     *   <li><b>最近节点定位</b>：从新路径中距实体最近的节点继续，不回头走旧路</li>
     * </ul>
     * 适用于旧段已走完（{@link #isFinished()} 为 true）时的无缝续段；
     * 进行中的导航请仍走"停止 + 新建"的取代语义。
     *
     * @param newResult 新寻路结果（无可走路径时忽略本次重定向）
     * @return this
     */
    public Navigator retarget(PathResult newResult) {
        if (newResult == null || !newResult.hasPath()) {
            return this;
        }
        List<AStarNode> newPath = newResult.getNodes();
        // 最近节点定位：新路径起点通常是发起寻路时的实体位置，
        // 但寻路耗时期间实体可能已移动，扫描取最近节点避免回头
        int nearest = 0;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < newPath.size(); i++) {
            AStarNode node = newPath.get(i);
            double ndx = (node.x + 0.5) - entity.x;
            double ndz = (node.z + 0.5) - entity.z;
            double dSq = ndx * ndx + ndz * ndz;
            if (dSq < bestSq) {
                bestSq = dSq;
                nearest = i;
            }
        }
        this.path = newPath;
        this.index = Math.min(nearest + 1, newPath.size());
        this.finished = false;
        this.stuckTicks = 0;
        this.lastX = entity.x;
        this.lastZ = entity.z;
        return this;
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
    public List<AStarNode> getPath() {
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
    public List<AStarNode> getRemainingPath() {
        if (finished || index >= path.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(path.subList(index, path.size()));
    }
}
