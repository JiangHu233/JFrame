package io.github.JiangHu.jframe.inventory.ui.model.event;

import cn.nukkit.item.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BeforeStoreEvent} 的单元测试。
 * <p>
 * 测试重点：
 * <ul>
 *   <li>动作形态判定（isDeposit / isWithdraw / isReplace）</li>
 *   <li>方向判定（isEntering / isLeaving，覆盖放入/取出/交换/并入堆叠/右键拿一半）</li>
 *   <li>取消语义（cancel / cancel(String) / denyMessage）</li>
 *   <li>null 物品归一化为空气</li>
 * </ul>
 */
@DisplayName("BeforeStoreEvent 存取前置事件")
class BeforeStoreEventTest {

    private static Item air() {
        return Item.get(Item.AIR);
    }

    private static Item diamond(int count) {
        return Item.get(Item.DIAMOND, 0, count);
    }

    private static Item iron(int count) {
        return Item.get(Item.IRON_INGOT, 0, count);
    }

    // ==================== 动作形态判定 ====================

    @Nested
    @DisplayName("动作形态判定")
    class ShapeTest {

        @Test
        @DisplayName("isDeposit：空 → 有物品")
        void testDeposit() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, air(), diamond(1));
            assertTrue(event.isDeposit());
            assertFalse(event.isWithdraw());
            assertFalse(event.isReplace());
        }

        @Test
        @DisplayName("isWithdraw：有物品 → 空")
        void testWithdraw() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, diamond(1), air());
            assertTrue(event.isWithdraw());
            assertFalse(event.isDeposit());
            assertFalse(event.isReplace());
        }

        @Test
        @DisplayName("isReplace：不同物品交换")
        void testReplaceDifferentItems() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, diamond(1), iron(1));
            assertTrue(event.isReplace());
            assertFalse(event.isDeposit());
            assertFalse(event.isWithdraw());
        }

        @Test
        @DisplayName("同物数量变化不算交换（并入堆叠 / 右键拿一半）")
        void testSameStackNotReplace() {
            BeforeStoreEvent merge = new BeforeStoreEvent(null, 0, diamond(32), diamond(64));
            assertFalse(merge.isReplace());

            BeforeStoreEvent halfTake = new BeforeStoreEvent(null, 0, diamond(64), diamond(32));
            assertFalse(halfTake.isReplace());
        }

        @Test
        @DisplayName("空 → 空为无操作")
        void testNoOp() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, air(), air());
            assertFalse(event.isDeposit());
            assertFalse(event.isWithdraw());
            assertFalse(event.isReplace());
            assertFalse(event.isEntering());
            assertFalse(event.isLeaving());
        }
    }

    // ==================== 方向判定 ====================

    @Nested
    @DisplayName("方向判定（isEntering / isLeaving）")
    class DirectionTest {

        @Test
        @DisplayName("放入：只有 entering")
        void testDepositDirection() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, air(), diamond(1));
            assertTrue(event.isEntering());
            assertFalse(event.isLeaving());
        }

        @Test
        @DisplayName("取出：只有 leaving")
        void testWithdrawDirection() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, diamond(1), air());
            assertFalse(event.isEntering());
            assertTrue(event.isLeaving());
        }

        @Test
        @DisplayName("交换：entering 与 leaving 同时为 true")
        void testSwapBothDirections() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, diamond(1), iron(1));
            assertTrue(event.isEntering());
            assertTrue(event.isLeaving());
        }

        @Test
        @DisplayName("并入堆叠（32 → 64）：只有 entering")
        void testMergeDirection() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, diamond(32), diamond(64));
            assertTrue(event.isEntering());
            assertFalse(event.isLeaving());
        }

        @Test
        @DisplayName("右键拿一半（64 → 32）：只有 leaving")
        void testHalfTakeDirection() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, diamond(64), diamond(32));
            assertFalse(event.isEntering());
            assertTrue(event.isLeaving());
        }

        @Test
        @DisplayName("同物同量：既非 entering 也非 leaving")
        void testSameCountNoDirection() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, diamond(32), diamond(32));
            assertFalse(event.isEntering());
            assertFalse(event.isLeaving());
        }
    }

    // ==================== 取消语义 ====================

    @Nested
    @DisplayName("取消语义")
    class CancelTest {

        @Test
        @DisplayName("初始状态：未取消、无提示")
        void testInitialState() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, air(), diamond(1));
            assertFalse(event.isCancelled());
            assertNull(event.denyMessage());
        }

        @Test
        @DisplayName("cancel()：仅取消，不设置提示")
        void testCancelWithoutMessage() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, air(), diamond(1));
            event.cancel();
            assertTrue(event.isCancelled());
            assertNull(event.denyMessage());
        }

        @Test
        @DisplayName("cancel(message)：取消并设置提示")
        void testCancelWithMessage() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, air(), diamond(1));
            event.cancel("§c不可放入");
            assertTrue(event.isCancelled());
            assertEquals("§c不可放入", event.denyMessage());
        }

        @Test
        @DisplayName("cancel(null) / cancel(\"\")：取消但不设置提示")
        void testCancelWithEmptyMessage() {
            BeforeStoreEvent event1 = new BeforeStoreEvent(null, 0, air(), diamond(1));
            event1.cancel(null);
            assertTrue(event1.isCancelled());
            assertNull(event1.denyMessage());

            BeforeStoreEvent event2 = new BeforeStoreEvent(null, 0, air(), diamond(1));
            event2.cancel("");
            assertTrue(event2.isCancelled());
            assertNull(event2.denyMessage());
        }
    }

    // ==================== null 归一化 ====================

    @Nested
    @DisplayName("null 物品归一化")
    class NullNormalizationTest {

        @Test
        @DisplayName("sourceItem 为 null 视为空气")
        void testNullSource() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, null, diamond(1));
            assertNotNull(event.sourceItem());
            assertTrue(event.sourceItem().isNull());
            assertTrue(event.isDeposit());
        }

        @Test
        @DisplayName("targetItem 为 null 视为空气")
        void testNullTarget() {
            BeforeStoreEvent event = new BeforeStoreEvent(null, 0, diamond(1), null);
            assertNotNull(event.targetItem());
            assertTrue(event.targetItem().isNull());
            assertTrue(event.isWithdraw());
        }
    }
}
