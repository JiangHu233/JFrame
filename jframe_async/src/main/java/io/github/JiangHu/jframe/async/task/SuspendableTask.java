package io.github.JiangHu.jframe.async.task;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.TaskHandler;

import java.util.function.BooleanSupplier;

/**
 * 可挂起的 Nukkit 主线程任务。
 * <p>
 * 基于 Nukkit {@link cn.nukkit.scheduler.ServerScheduler} 的「按需驱动、自动挂起、可恢复」任务抽象。
 * 任务在有效（{@link #isValid()} 返回 {@code true}）时由主线程 tick 循环持续驱动，
 * 完成后自动取消调度进入空闲态；外部可随时通过 {@link #execute()} 重新唤醒。
 *
 * <h3>核心语义</h3>
 * <ul>
 *   <li><b>幂等触发</b>：{@link #execute()} 被调用时，若任务正在执行则保持执行（不重复注册）；
 *       若未在执行则注册新的 {@code scheduleRepeatingTask} 启动。</li>
 *   <li><b>自动挂起</b>：{@link #isValid()} 返回 {@code false} 时，任务自动取消 Nukkit 调度，
 *       释放 tick 占用，并触发 {@link #onFinish()} 钩子。</li>
 *   <li><b>可恢复</b>：挂起后任务对象仍然存活，内部状态（进度、游标等）保留；
 *       再次调用 {@link #execute()} 即可重新注册调度、恢复执行。</li>
 * </ul>
 *
 * <h3>状态机</h3>
 * <pre>{@code
 *   Idle ──execute()──▶ Running ──isValid()==false──▶ Idle（自动挂起 + onFinish）
 *    ▲                     │
 *    │                每 interval tick:
 *    │                  isValid==true  → onTick()
 *    │                  isValid==false → cancel() + onFinish()
 *    │
 *    └─── execute() ────── 可恢复 ──────┘
 * }</pre>
 *
 * <h3>使用方式</h3>
 * <p><b>方式一：继承子类</b>
 * <pre>{@code
 * public class BuildTask extends SuspendableTask {
 *     private final List<Block> blocks;
 *     private int cursor = 0;
 *
 *     public BuildTask(Plugin plugin, List<Block> blocks) {
 *         super(plugin, 2); // 每 2 tick 放一个方块
 *         this.blocks = blocks;
 *     }
 *
 *     @Override protected void onTick() {
 *         placeBlock(blocks.get(cursor++));
 *     }
 *
 *     @Override protected boolean isValid() {
 *         return cursor < blocks.size();
 *     }
 * }
 *
 * BuildTask task = new BuildTask(plugin, blockList);
 * task.execute();   // 启动；重复调用安全（幂等）
 * }</pre>
 *
 * <p><b>方式二：函数式创建</b>（通过 {@link TaskAPI#createTask}）
 * <pre>{@code
 * SuspendableTask task = taskAPI.createTask("search", 1,
 *     () -> expandNode(),        // onTick
 *     () -> !found()             // isValid
 * );
 * task.execute();
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>{@link #execute()} 与 {@link #cancel()} 使用 {@code synchronized} 保护内部状态，
 *       可在任意线程（主线程 / 异步线程）安全调用。</li>
 *   <li>{@link #tick()} 由 Nukkit 主线程调度执行，单线程无并发。</li>
 * </ul>
 *
 * @see TaskAPI
 * @see cn.nukkit.scheduler.ServerScheduler#scheduleRepeatingTask(Plugin, Runnable, int)
 */
public abstract class SuspendableTask {

    /** 关联插件，用于注册 Nukkit 调度任务 */
    protected final Plugin plugin;

    /** 调度间隔（tick），决定 {@link #onTick()} 的调用频率 */
    protected final int interval;

    /** Nukkit 调度句柄，非 null 表示已注册调度 */
    private TaskHandler taskHandler;

    /** 任务运行标志，独立于 {@link #taskHandler}，作为运行态的单一事实来源 */
    private volatile boolean running;

    /** 任务是否已自然完成（{@link #isValid()} 返回 false 导致自动挂起） */
    private volatile boolean finished;

    /**
     * 构造可挂起任务。
     *
     * @param plugin   关联插件，用于注册 Nukkit 调度，不能为 null
     * @param interval 调度间隔（tick），必须 > 0
     * @throws NullPointerException     plugin 为 null
     * @throws IllegalArgumentException interval &le; 0
     */
    protected SuspendableTask(Plugin plugin, int interval) {
        if (plugin == null) {
            throw new NullPointerException("plugin cannot be null");
        }
        if (interval <= 0) {
            throw new IllegalArgumentException("interval must be > 0, got: " + interval);
        }
        this.plugin = plugin;
        this.interval = interval;
    }

    // ==================== 抽象方法（子类实现） ====================

    /**
     * 每个 interval tick 执行一次的增量工作逻辑。
     * <p>
     * 由 Nukkit 主线程调用。实现方应在此方法中完成一小步工作，
     * 避免单次执行耗时过长导致卡服。
     */
    protected abstract void onTick();

    /**
     * 任务是否还有有效工作。
     * <p>
     * 返回 {@code true} 表示继续执行（下个 tick 继续调用 {@link #onTick()}）；
     * 返回 {@code false} 表示任务已完成，将自动取消调度并触发 {@link #onFinish()}。
     *
     * @return {@code true} = 继续执行，{@code false} = 完成
     */
    protected abstract boolean isValid();

    // ==================== 生命周期钩子（可选覆盖） ====================

    /**
     * 任务启动钩子。
     * <p>
     * 在 {@link #execute()} 实际注册调度任务后、首次 {@link #tick()} 前调用
     * （Idle → Running 转换时）。默认空操作，可覆盖以做初始化。
     */
    protected void onStart() {
    }

    /**
     * 任务完成钩子。
     * <p>
     * 在 {@link #isValid()} 返回 {@code false}、任务自动挂起后调用。
     * 默认空操作，可覆盖以做收尾（如发通知、清理资源）。
     * <p>
     * 注意：手动 {@link #cancel()} <b>不会</b>触发此钩子。
     */
    protected void onFinish() {
    }

    // ==================== 公开 API ====================

    /**
     * 触发任务执行（幂等）。
     * <p>
     * 若任务正在执行（被 Nukkit 调度器驱动），则直接返回，保持执行；
     * 若任务未在执行，则注册新的 {@code scheduleRepeatingTask} 并启动。
     * <p>
     * 线程安全：可在任意线程调用。
     */
    public synchronized void execute() {
        if (running) {
            return; // 正在执行 → 保持，不重复注册
        }
        onStart();          // 先初始化；若抛异常则不进入运行态
        running = true;
        finished = false;
        taskHandler = scheduleTask();
    }

    /**
     * 手动取消任务调度。
     * <p>
     * 取消后任务回到 Idle 态，可再次通过 {@link #execute()} 恢复。
     * <b>不触发</b> {@link #onFinish()}（仅自然完成才触发）。
     * <p>
     * 线程安全：可在任意线程调用。
     */
    public synchronized void cancel() {
        running = false;
        if (taskHandler != null) {
            taskHandler.cancel();
            taskHandler = null;
        }
    }

    /**
     * 任务是否正在被 Nukkit 调度器驱动。
     *
     * @return {@code true} 表示正在执行
     */
    public synchronized boolean isRunning() {
        return running;
    }

    /**
     * 任务是否已自然完成。
     * <p>
     * 仅当 {@link #isValid()} 返回 {@code false} 导致自动挂起时为 {@code true}。
     * 手动 {@link #cancel()} 不会设置此标志。
     *
     * @return {@code true} 表示已自然完成
     */
    public boolean isFinished() {
        return finished;
    }

    // ==================== 内部逻辑 ====================

    /**
     * 注册 Nukkit 重复调度任务。
     * <p>
     * {@code protected} 修饰，便于子类 / 测试覆盖调度行为。
     *
     * @return Nukkit 调度句柄
     */
    protected TaskHandler scheduleTask() {
        return Server.getInstance().getScheduler()
                .scheduleRepeatingTask(plugin, this::tick, interval);
    }

    /**
     * 由 Nukkit 调度器周期调用的内部 tick 逻辑。
     * <p>
     * 先检查 {@link #isValid()}：若为 {@code false} 则自动挂起并触发 {@link #onFinish()}；
     * 若为 {@code true} 则执行 {@link #onTick()}，异常被捕获并记录，不中断后续 tick。
     * <p>
     * 包级可见，便于单元测试直接模拟调度器调用。
     */
    synchronized void tick() {
        if (!isValid()) {
            finished = true;
            cancel();
            onFinish();
            return;
        }
        try {
            onTick();
        } catch (Exception e) {
            Server.getInstance().getLogger().error(
                    "SuspendableTask tick 异常: " + getClass().getName(), e);
        }
    }

    // ==================== 函数式实现 ====================

    /**
     * 函数式可挂起任务：通过 Lambda 创建，无需写子类。
     * <p>
     * 通常通过 {@link TaskAPI#createTask(String, int, Runnable, BooleanSupplier)} 创建，
     * 也可直接实例化：
     * <pre>{@code
     * SuspendableTask task = new SuspendableTask.Functional(plugin, 1,
     *     () -> doWork(), () -> hasMore());
     * }</pre>
     */
    public static class Functional extends SuspendableTask {

        private final Runnable tickAction;
        private final BooleanSupplier validChecker;

        /**
         * @param plugin       关联插件
         * @param interval     调度间隔（tick）
         * @param tickAction   每个 tick 执行的工作，不能为 null
         * @param validChecker 是否还有有效工作，不能为 null
         */
        public Functional(Plugin plugin, int interval, Runnable tickAction, BooleanSupplier validChecker) {
            super(plugin, interval);
            if (tickAction == null) {
                throw new NullPointerException("tickAction cannot be null");
            }
            if (validChecker == null) {
                throw new NullPointerException("validChecker cannot be null");
            }
            this.tickAction = tickAction;
            this.validChecker = validChecker;
        }

        @Override
        protected void onTick() {
            tickAction.run();
        }

        @Override
        protected boolean isValid() {
            return validChecker.getAsBoolean();
        }
    }
}
