package io.github.JiangHu.jframe.engine.pool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ObjectPool 原语单测 — obtain/free、上限丢弃、reset 时序、指标、debug 双重释放、clear。
 */
class ObjectPoolTest {

    /** 简单可标记对象 */
    private static class Widget {
        boolean reset;
    }

    @Test
    @DisplayName("obtain：池空新建 / free 后 obtain 复用同一实例")
    void obtainAndReuse() {
        List<Widget> created = new ArrayList<>();
        ObjectPool<Widget> pool = new ObjectPool<>(() -> {
            Widget w = new Widget();
            created.add(w);
            return w;
        }, w -> w.reset = true, 4, false);

        Widget first = pool.obtain();
        assertSame(first, created.get(0), "池空走 factory 新建");
        pool.free(first);
        Widget second = pool.obtain();
        assertSame(first, second, "free 后 obtain 复用同一实例");
        assertEquals(1, created.size(), "只新建一次");
        assertEquals(1, pool.reused());
        assertTrue(second.reset, "free 时执行 resetter");
    }

    @Test
    @DisplayName("factory 为 null：空闲表空返回 null")
    void nullFactoryReturnsNull() {
        ObjectPool<Widget> pool = new ObjectPool<>(null, null, 4, false);
        assertNull(pool.obtain());
        Widget w = new Widget();
        pool.free(w);
        assertSame(w, pool.obtain(), "回收后可取出");
    }

    @Test
    @DisplayName("上限：超 maxSize 的 free 直接丢弃")
    void overflowDrops() {
        ObjectPool<Widget> pool = new ObjectPool<>(null, null, 3, false);
        Widget a = new Widget();
        Widget b = new Widget();
        Widget c = new Widget();
        Widget d = new Widget();
        Widget e = new Widget();
        pool.free(a);
        pool.free(b);
        pool.free(c);
        pool.free(d);
        pool.free(e);
        assertEquals(3, pool.size(), "空闲表封顶 maxSize");
        assertEquals(3, pool.peak(), "峰值记录");
        // 取出三个后池空（d/e 已被丢弃）
        assertSame(c, pool.obtain());
        assertSame(b, pool.obtain());
        assertSame(a, pool.obtain());
        assertNull(pool.obtain(), "d/e 已丢弃，池空返回 null");
    }

    @Test
    @DisplayName("指标：created / reused / hitRate")
    void metrics() {
        ObjectPool<Widget> pool = new ObjectPool<>(Widget::new, null, 8, false);
        Widget w1 = pool.obtain();
        pool.free(w1);
        pool.obtain();
        Widget w2 = pool.obtain();
        assertEquals(2, pool.created());
        assertEquals(1, pool.reused());
        assertEquals(1.0 / 3, pool.hitRate(), 1e-9);
        pool.free(w2);
        assertEquals(0.0, new ObjectPool<>(Widget::new, null, 8, false).hitRate(), "无记录 hitRate=0");
    }

    @Test
    @DisplayName("debug 双重释放检测；非 debug 模式不抛")
    void doubleFreeDetection() {
        ObjectPool<Widget> debugPool = new ObjectPool<>(Widget::new, null, 4, true);
        Widget w = debugPool.obtain();
        debugPool.free(w);
        assertThrows(IllegalStateException.class, () -> debugPool.free(w), "debug 模式双重 free 抛异常");
        assertThrows(IllegalStateException.class, () -> debugPool.free(new Widget()),
                "debug 模式 free 从未 obtain 的实例抛异常");

        ObjectPool<Widget> releasePool = new ObjectPool<>(Widget::new, null, 4, false);
        Widget x = releasePool.obtain();
        releasePool.free(x);
        releasePool.free(x);
        assertEquals(2, releasePool.size(), "非 debug 双重 free 不抛（同实例重复入表，调用方契约）");
    }

    @Test
    @DisplayName("clear：空闲表清空，后续 obtain 走新建")
    void clearResets() {
        ObjectPool<Widget> pool = new ObjectPool<>(Widget::new, null, 4, false);
        Widget w = pool.obtain();
        pool.free(w);
        assertEquals(1, pool.size());
        pool.clear();
        assertEquals(0, pool.size());
        Widget next = pool.obtain();
        assertNotSame(w, next, "clear 后重新新建");
    }
}
