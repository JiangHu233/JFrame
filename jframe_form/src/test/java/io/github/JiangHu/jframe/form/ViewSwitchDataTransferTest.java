package io.github.JiangHu.jframe.form;

import cn.nukkit.Player;
import io.github.JiangHu.jframe.form.window.JForm;
import io.github.JiangHu.jframe.form.window.SimpleForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「更换视图时传递一次性数据」的单元测试。
 * <p>
 * 验证 {@link ViewManager} 与 {@link FormView} 中所有「切换到新视图」的导航方法
 * 在附带 {@code data} 参数时，能正确触发目标视图的 {@link FormView#onData}，
 * 且不影响栈结构与无参版本的原有行为。
 * <p>
 * <b>测试隔离策略：</b>
 * <ul>
 *   <li>通过 {@code sun.misc.Unsafe#allocateInstance} 创建 {@link Player} 空壳，
 *       绕过 Nukkit 重量级构造器（与 {@code NukkitTestBootstrap} 同一技巧）。
 *       {@link ViewManager} 仅在构造时持有 player 引用，数据传递路径不调用其方法。</li>
 *   <li>对于会触发 {@link ViewManager#send()} 的方法（{@code replaceAndSend} /
 *       {@code restartWith} / {@code FormView.replaceThis} / {@code FormView.restartWith}），
 *       使用重写了 {@code send()} 的子类抑制真实表单发送，仅验证栈操作与数据传递。</li>
 * </ul>
 */
@DisplayName("更换视图时传递一次性数据（replace / insert / restartWith / replaceThis）")
class ViewSwitchDataTransferTest {

    /**
     * 记录 {@link FormView#onData} 收到的数据与调用次数。
     * <p>
     * 用 {@code receiveCount} 区分「未收到」与「收到 null」两种情况。
     */
    static class RecordingView extends FormView {
        Object received = new Object(); // 哨兵：区分「未调用 onData」与「收到 null」
        int receiveCount = 0;

        @Override
        protected JForm onBuild() {
            return new SimpleForm("test");
        }

        @Override
        protected void onData(Object data) {
            this.received = data;
            this.receiveCount++;
        }
    }

    /** 通过 Unsafe 创建不调用构造器的 {@link Player} 空壳。 */
    private static Player mockPlayer() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            return (Player) unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, Player.class);
        } catch (Throwable t) {
            throw new AssertionError("无法创建 Player 空壳：" + t.getMessage(), t);
        }
    }

    /** 创建一个抑制了真实表单发送的 {@link ViewManager}（重写 send 为空操作）。 */
    private static ViewManager quietManager() {
        return new ViewManager(mockPlayer()) {
            @Override
            public void send() {
                // 测试用：抑制真实表单发送，仅验证栈操作与数据传递
            }
        };
    }

    // -------------------- ViewManager.replace --------------------

    @Test
    @DisplayName("replace(old, next, data)：新视图收到 data，栈顶变为新视图")
    void replaceWithData() {
        ViewManager manager = new ViewManager(mockPlayer());
        RecordingView old = new RecordingView();
        RecordingView next = new RecordingView();
        manager.push(old);

        manager.replace(old, next, "payload");

        assertEquals("payload", next.received);
        assertEquals(1, next.receiveCount);
        assertSame(next, manager.current());
        assertEquals(1, manager.size());
    }

    @Test
    @DisplayName("replace(old, next) 无参版本不触发 onData（行为不变）")
    void replaceWithoutDataDoesNotTriggerOnData() {
        ViewManager manager = new ViewManager(mockPlayer());
        RecordingView old = new RecordingView();
        RecordingView next = new RecordingView();
        manager.push(old);

        manager.replace(old, next);

        assertEquals(0, next.receiveCount);
        assertSame(next, manager.current());
    }

    // -------------------- ViewManager.insert --------------------

    @Test
    @DisplayName("insert(index, view, data)：插入的视图收到 data，栈结构正确")
    void insertWithData() {
        ViewManager manager = new ViewManager(mockPlayer());
        RecordingView bottom = new RecordingView();
        RecordingView top = new RecordingView();
        RecordingView middle = new RecordingView();
        manager.push(bottom);
        manager.push(top);

        manager.insert(1, middle, 42);

        assertEquals(42, middle.received);
        assertEquals(1, middle.receiveCount);
        // 栈结构（栈底→栈顶）：bottom -> middle -> top
        assertEquals(3, manager.size());
        assertSame(top, manager.current());
    }

    // -------------------- ViewManager.replaceAndSend --------------------

    @Test
    @DisplayName("replaceAndSend(old, next, data)：新视图收到 data")
    void replaceAndSendWithData() {
        ViewManager manager = quietManager();
        RecordingView old = new RecordingView();
        RecordingView next = new RecordingView();
        manager.push(old);

        manager.replaceAndSend(old, next, "sent-payload");

        assertEquals("sent-payload", next.received);
        assertEquals(1, next.receiveCount);
        assertSame(next, manager.current());
    }

    // -------------------- ViewManager.restartWith --------------------

    @Test
    @DisplayName("restartWith(newRoot, data)：新根视图收到 data，旧栈被清空")
    void restartWithData() {
        ViewManager manager = quietManager();
        manager.push(new RecordingView());
        manager.push(new RecordingView());
        RecordingView newRoot = new RecordingView();

        manager.restartWith(newRoot, "root-data");

        assertEquals("root-data", newRoot.received);
        assertEquals(1, newRoot.receiveCount);
        assertEquals(1, manager.size());
        assertSame(newRoot, manager.current());
    }

    @Test
    @DisplayName("restartWith(newRoot) 无参版本不触发 onData（行为不变）")
    void restartWithoutDataDoesNotTriggerOnData() {
        ViewManager manager = quietManager();
        manager.push(new RecordingView());
        RecordingView newRoot = new RecordingView();

        manager.restartWith(newRoot);

        assertEquals(0, newRoot.receiveCount);
        assertSame(newRoot, manager.current());
    }

    // -------------------- FormView.replaceThis --------------------

    @Test
    @DisplayName("FormView.replaceThis(view, data)：委托 manager，新视图收到 data")
    void formViewReplaceThisWithData() {
        ViewManager manager = quietManager();
        RecordingView current = new RecordingView();
        RecordingView next = new RecordingView();
        manager.push(current);

        current.replaceThis(next, "delegated");

        assertEquals("delegated", next.received);
        assertEquals(1, next.receiveCount);
        assertSame(next, manager.current());
    }

    // -------------------- FormView.restartWith --------------------

    @Test
    @DisplayName("FormView.restartWith(newRoot, data)：委托 manager，新根视图收到 data")
    void formViewRestartWithData() {
        ViewManager manager = quietManager();
        RecordingView current = new RecordingView();
        manager.push(current);
        RecordingView newRoot = new RecordingView();

        current.restartWith(newRoot, "restart-delegated");

        assertEquals("restart-delegated", newRoot.received);
        assertEquals(1, newRoot.receiveCount);
        assertSame(newRoot, manager.current());
    }

    // -------------------- 基准对比：push(view, data) --------------------

    @Test
    @DisplayName("push(view, data)：基准——压入视图收到 data（与新增方法行为一致）")
    void pushWithDataBaseline() {
        ViewManager manager = new ViewManager(mockPlayer());
        RecordingView view = new RecordingView();

        manager.push(view, "baseline");

        assertEquals("baseline", view.received);
        assertEquals(1, view.receiveCount);
        assertSame(view, manager.current());
    }
}
