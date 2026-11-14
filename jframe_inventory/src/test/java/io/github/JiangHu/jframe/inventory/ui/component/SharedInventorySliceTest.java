package io.github.JiangHu.jframe.inventory.ui.component;

import cn.nukkit.inventory.Inventory;
import cn.nukkit.inventory.InventoryType;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.codec.FakeInventory;
import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.event.StoreEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SharedInventorySlice} 与 {@link SharedInventorySync} 的单元测试。
 * <p>
 * 测试重点：
 * <ul>
 *   <li>同步注册器的注册/注销/计数</li>
 *   <li>变更通知的路由逻辑（排除发起者）</li>
 *   <li>onStore 写回外部库存</li>
 *   <li>SlotAppearance.fromItem 转换</li>
 * </ul>
 */
@DisplayName("SharedInventorySlice 共享库存切片")
class SharedInventorySliceTest {

    /** 测试结束后清理同步注册器，避免测试间状态泄漏 */
    @AfterEach
    void cleanupSync() {
        // 通过创建新实例触发清理是不现实的，这里依赖各测试自行 unregister
    }

    // ==================== SharedInventorySync 注册器 ====================

    @Nested
    @DisplayName("SharedInventorySync 注册器")
    class SyncRegistryTest {

        @Test
        @DisplayName("注册与注销：sliceCount 正确反映活跃切片数")
        void testRegisterUnregister() {
            Inventory external = new FakeInventory(null, InventoryType.CHEST);
            SharedInventorySlice slice1 = new SharedInventorySlice(external, 0, 9, 2);
            SharedInventorySlice slice2 = new SharedInventorySlice(external, 0, 9, 2);

            assertEquals(0, SharedInventorySync.sliceCount(external));

            SharedInventorySync.register(slice1);
            assertEquals(1, SharedInventorySync.sliceCount(external));

            SharedInventorySync.register(slice2);
            assertEquals(2, SharedInventorySync.sliceCount(external));

            SharedInventorySync.unregister(slice1);
            assertEquals(1, SharedInventorySync.sliceCount(external));

            SharedInventorySync.unregister(slice2);
            assertEquals(0, SharedInventorySync.sliceCount(external));
        }

        @Test
        @DisplayName("不同外部库存的切片互不影响")
        void testDifferentInventoriesIndependent() {
            Inventory externalA = new FakeInventory(null, InventoryType.CHEST);
            Inventory externalB = new FakeInventory(null, InventoryType.CHEST);
            SharedInventorySlice sliceA = new SharedInventorySlice(externalA, 0, 9, 1);
            SharedInventorySlice sliceB = new SharedInventorySlice(externalB, 0, 9, 1);

            SharedInventorySync.register(sliceA);
            SharedInventorySync.register(sliceB);

            assertEquals(1, SharedInventorySync.sliceCount(externalA));
            assertEquals(1, SharedInventorySync.sliceCount(externalB));

            SharedInventorySync.unregister(sliceA);
            assertEquals(0, SharedInventorySync.sliceCount(externalA));
            assertEquals(1, SharedInventorySync.sliceCount(externalB));

            SharedInventorySync.unregister(sliceB);
        }

        @Test
        @DisplayName("重复注销安全（幂等）")
        void testUnregisterIdempotent() {
            Inventory external = new FakeInventory(null, InventoryType.CHEST);
            SharedInventorySlice slice = new SharedInventorySlice(external, 0, 9, 1);

            SharedInventorySync.register(slice);
            SharedInventorySync.unregister(slice);
            // 再次注销不应抛异常
            assertDoesNotThrow(() -> SharedInventorySync.unregister(slice));
            assertEquals(0, SharedInventorySync.sliceCount(external));
        }
    }

    // ==================== 变更通知路由 ====================

    @Nested
    @DisplayName("变更通知路由")
    class SyncNotifyTest {

        @Test
        @DisplayName("notifyChanged 排除发起者，只通知其他切片")
        void testNotifyExcludesSource() {
            Inventory external = new FakeInventory(null, InventoryType.CHEST);
            TrackingSlice source = new TrackingSlice(external, 0, 9, 2);
            TrackingSlice other = new TrackingSlice(external, 0, 9, 2);

            SharedInventorySync.register(source);
            SharedInventorySync.register(other);

            // 通知：source 是发起者
            SharedInventorySync.notifyChanged(external, 5, source);

            // source 不应被通知
            assertTrue(source.changedSlots.isEmpty(), "发起者不应收到自己的变更通知");
            // other 应被通知
            assertEquals(1, other.changedSlots.size(), "其他切片应收到变更通知");
            assertEquals(5, other.changedSlots.get(0));

            SharedInventorySync.unregister(source);
            SharedInventorySync.unregister(other);
        }

        @Test
        @DisplayName("notifyChanged 通知所有其他切片（多切片场景）")
        void testNotifyMultipleSlices() {
            Inventory external = new FakeInventory(null, InventoryType.CHEST);
            TrackingSlice source = new TrackingSlice(external, 0, 9, 1);
            TrackingSlice other1 = new TrackingSlice(external, 0, 9, 1);
            TrackingSlice other2 = new TrackingSlice(external, 0, 9, 1);

            SharedInventorySync.register(source);
            SharedInventorySync.register(other1);
            SharedInventorySync.register(other2);

            SharedInventorySync.notifyChanged(external, 3, source);

            assertTrue(source.changedSlots.isEmpty());
            assertEquals(1, other1.changedSlots.size());
            assertEquals(3, other1.changedSlots.get(0));
            assertEquals(1, other2.changedSlots.size());
            assertEquals(3, other2.changedSlots.get(0));

            SharedInventorySync.unregister(source);
            SharedInventorySync.unregister(other1);
            SharedInventorySync.unregister(other2);
        }

        @Test
        @DisplayName("notifyChanged 对无切片的外部库存安全调用")
        void testNotifyNoSlices() {
            Inventory external = new FakeInventory(null, InventoryType.CHEST);
            SharedInventorySlice dummy = new SharedInventorySlice(external, 0, 9, 1);

            // 没有注册任何切片，调用不应抛异常
            assertDoesNotThrow(() -> SharedInventorySync.notifyChanged(external, 0, dummy));
        }

        @Test
        @DisplayName("notifyChanged 批量通知多个槽位")
        void testNotifyBatchSlots() {
            Inventory external = new FakeInventory(null, InventoryType.CHEST);
            TrackingSlice source = new TrackingSlice(external, 0, 9, 2);
            TrackingSlice other = new TrackingSlice(external, 0, 9, 2);

            SharedInventorySync.register(source);
            SharedInventorySync.register(other);

            SharedInventorySync.notifyChanged(external, List.of(0, 5, 10), source);

            assertTrue(source.changedSlots.isEmpty());
            assertEquals(3, other.changedSlots.size());
            assertEquals(0, other.changedSlots.get(0));
            assertEquals(5, other.changedSlots.get(1));
            assertEquals(10, other.changedSlots.get(2));

            SharedInventorySync.unregister(source);
            SharedInventorySync.unregister(other);
        }
    }

    // ==================== SharedInventorySlice.onStore 写回 ====================

    @Nested
    @DisplayName("onStore 写回外部库存")
    class OnStoreTest {

        @Test
        @DisplayName("取出物品：外部库存对应槽位变为空气")
        void testWithdrawWritesBack() {
            Inventory external = new FakeInventory(null, InventoryType.CHEST);
            Item sword = Item.get(Item.DIAMOND_SWORD);
            external.setItem(0, sword);

            // 切片从外部库存槽位 0 开始，宽 9 高 1，位于视图 (0,0)
            // absBaseRow/absBaseCol 默认为 0，与位置 (0,0) 一致
            SharedInventorySlice slice = new SharedInventorySlice(external, 0, 9, 1);

            // 模拟取出：slot 0 的物品被取走（sourceItem=sword, targetItem=AIR）
            StoreEvent event = new StoreEvent(null, 0, sword, Item.get(Item.AIR));
            slice.onStore(event);

            // 外部库存槽位 0 应变为空气
            assertTrue(external.getItem(0).isNull(), "取出后外部库存对应槽位应为空");
        }

        @Test
        @DisplayName("放入物品：外部库存对应槽位获得物品")
        void testDepositWritesBack() {
            Inventory external = new FakeInventory(null, InventoryType.CHEST);

            SharedInventorySlice slice = new SharedInventorySlice(external, 0, 9, 1);

            Item apple = Item.get(Item.APPLE);
            // 模拟放入：slot 0 从空变为有物品
            StoreEvent event = new StoreEvent(null, 0, Item.get(Item.AIR), apple);
            slice.onStore(event);

            // 外部库存槽位 0 应有苹果
            Item result = external.getItem(0);
            assertFalse(result.isNull(), "放入后外部库存对应槽位应有物品");
            assertEquals(Item.APPLE, result.getId());
        }

        @Test
        @DisplayName("onStore 触发用户回调")
        void testOnStoreCallback() {
            Inventory external = new FakeInventory(null, InventoryType.CHEST);
            SharedInventorySlice slice = new SharedInventorySlice(external, 0, 9, 1);

            boolean[] callbackCalled = {false};
            slice.onStore(event -> {
                callbackCalled[0] = true;
                assertTrue(event.isWithdraw(), "回调中应能判断为取出操作");
            });

            Item sword = Item.get(Item.DIAMOND_SWORD);
            external.setItem(0, sword);
            StoreEvent event = new StoreEvent(null, 0, sword, Item.get(Item.AIR));
            slice.onStore(event);

            assertTrue(callbackCalled[0], "用户回调应被触发");
        }

        @Test
        @DisplayName("onStore 后通知其他同源切片")
        void testOnStoreNotifiesOthers() {
            Inventory external = new FakeInventory(null, InventoryType.CHEST);
            TrackingSlice sliceA = new TrackingSlice(external, 0, 9, 1);
            TrackingSlice sliceB = new TrackingSlice(external, 0, 9, 1);

            SharedInventorySync.register(sliceA);
            SharedInventorySync.register(sliceB);

            Item sword = Item.get(Item.DIAMOND_SWORD);
            external.setItem(0, sword);

            // sliceA 执行取出操作
            StoreEvent event = new StoreEvent(null, 0, sword, Item.get(Item.AIR));
            sliceA.onStore(event);

            // sliceA 不应收到通知（自己是发起者）
            assertTrue(sliceA.changedSlots.isEmpty());
            // sliceB 应收到槽位 0 的变更通知
            assertEquals(1, sliceB.changedSlots.size());
            assertEquals(0, sliceB.changedSlots.get(0));

            SharedInventorySync.unregister(sliceA);
            SharedInventorySync.unregister(sliceB);
        }
    }

    // ==================== SlotAppearance.fromItem ====================

    @Nested
    @DisplayName("SlotAppearance.fromItem")
    class FromItemTest {

        @Test
        @DisplayName("普通物品正确转换")
        void testFromItemNormal() {
            Item item = Item.get(Item.DIAMOND_SWORD);
            item.setCustomName("Test Sword");

            SlotAppearance appearance = SlotAppearance.fromItem(item);

            assertNotNull(appearance);
            assertEquals(Item.DIAMOND_SWORD, appearance.itemId());
            assertEquals(1, appearance.count());
            assertEquals("Test Sword", appearance.name());
        }

        @Test
        @DisplayName("带数量的物品正确转换")
        void testFromItemWithCount() {
            Item item = Item.get(Item.APPLE, 0, 32);

            SlotAppearance appearance = SlotAppearance.fromItem(item);

            assertNotNull(appearance);
            assertEquals(Item.APPLE, appearance.itemId());
            assertEquals(32, appearance.count());
        }

        @Test
        @DisplayName("空气物品返回 null")
        void testFromItemAir() {
            SlotAppearance appearance = SlotAppearance.fromItem(Item.get(Item.AIR));
            assertNull(appearance, "空气物品应返回 null");
        }

        @Test
        @DisplayName("null 物品返回 null")
        void testFromItemNull() {
            SlotAppearance appearance = SlotAppearance.fromItem(null);
            assertNull(appearance, "null 物品应返回 null");
        }

        @Test
        @DisplayName("fromItem → toItem 往返转换保持一致")
        void testFromItemRoundTrip() {
            Item original = Item.get(Item.IRON_INGOT);
            original.setCustomName("§bIron");

            SlotAppearance appearance = SlotAppearance.fromItem(original);
            assertNotNull(appearance);

            Item converted = appearance.toItem();
            assertEquals(Item.IRON_INGOT, converted.getId());
            assertEquals("§bIron", converted.getCustomName());
        }
    }

    // ==================== 辅助类 ====================

    /**
     * 跟踪 onExternalChanged 调用的测试用切片子类。
     * <p>
     * 覆盖 onExternalChanged 记录被通知的槽位，用于验证同步路由逻辑。
     */
    static class TrackingSlice extends SharedInventorySlice {

        /** 记录被通知的外部库存槽位 */
        final List<Integer> changedSlots = new ArrayList<>();

        TrackingSlice(Inventory externalInventory, int externalStartSlot,
                      int width, int height) {
            super(externalInventory, externalStartSlot, width, height);
        }

        @Override
        void onExternalChanged(int externalSlot) {
            changedSlots.add(externalSlot);
        }
    }
}
