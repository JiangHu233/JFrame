package io.github.JiangHu.jframe.inventory.ui.component;

import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.ui.model.event.BeforeStoreEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StorageBox 存取守卫（filter / withdrawFilter / beforeStore）与
 * 基类 {@code InventoryComponent.onBeforeStore} 机制的单元测试。
 * <p>
 * 通过 {@code dispatchBeforeStore} 直接驱动（无需服务器/视图），
 * 交易层集成（InventoryManager 取消整笔交易）由游戏内手测覆盖。
 */
@DisplayName("StorageBox 存取守卫")
class StorageBoxGuardTest {

    private static Item air() {
        return Item.get(Item.AIR);
    }

    private static Item diamond(int count) {
        return Item.get(Item.DIAMOND, 0, count);
    }

    private static Item dirt(int count) {
        return Item.get(Item.DIRT, 0, count);
    }

    /** 在 box 上模拟一次格子变化（source → target）并返回事件（已分发） */
    private static BeforeStoreEvent dispatch(StorageBox box, Item source, Item target) {
        BeforeStoreEvent event = new BeforeStoreEvent(null, 0, source, target);
        box.dispatchBeforeStore(event);
        return event;
    }

    // ==================== 放入校验 filter ====================

    @Nested
    @DisplayName("放入校验 filter")
    class DepositFilterTest {

        @Test
        @DisplayName("白名单外物品放入被否决，携带提示")
        void testRejectNonWhitelistedDeposit() {
            StorageBox box = new StorageBox(9, 1);
            box.filter(item -> item.getId() == Item.DIAMOND, "§c仅收钻石");

            BeforeStoreEvent event = dispatch(box, air(), dirt(1));
            assertTrue(event.isCancelled(), "白名单外物品放入应被否决");
            assertEquals("§c仅收钻石", event.denyMessage());
        }

        @Test
        @DisplayName("白名单内物品放行")
        void testAllowWhitelistedDeposit() {
            StorageBox box = new StorageBox(9, 1);
            box.filter(item -> item.getId() == Item.DIAMOND, "§c仅收钻石");

            assertFalse(dispatch(box, air(), diamond(1)).isCancelled());
        }

        @Test
        @DisplayName("取出不受放入校验影响")
        void testWithdrawUnaffectedByFilter() {
            StorageBox box = new StorageBox(9, 1);
            box.filter(item -> item.getId() == Item.DIAMOND, "§c仅收钻石");

            assertFalse(dispatch(box, diamond(1), air()).isCancelled(), "取出应不受 filter 限制");
        }

        @Test
        @DisplayName("交换换入白名单外物品被否决")
        void testRejectSwapInNonWhitelisted() {
            StorageBox box = new StorageBox(9, 1);
            box.filter(item -> item.getId() == Item.DIAMOND, "§c仅收钻石");

            // 交换：钻石换出、泥土换入
            BeforeStoreEvent event = dispatch(box, diamond(1), dirt(1));
            assertTrue(event.isCancelled(), "换入白名单外物品应被否决");
        }

        @Test
        @DisplayName("并入堆叠（同物数量增加）按放入校验")
        void testMergeCheckedAsEntering() {
            StorageBox box = new StorageBox(9, 1);
            box.filter(item -> item.getId() == Item.DIAMOND, "§c仅收钻石");

            // 泥土 32 → 64：并入堆叠，进入的是泥土，应被否决
            assertTrue(dispatch(box, dirt(32), dirt(64)).isCancelled());
            // 钻石 32 → 64：并入堆叠，放行
            assertFalse(dispatch(box, diamond(32), diamond(64)).isCancelled());
        }

        @Test
        @DisplayName("filter(null) 清除放入校验")
        void testClearFilter() {
            StorageBox box = new StorageBox(9, 1);
            box.filter(item -> item.getId() == Item.DIAMOND, "§c仅收钻石");
            box.filter(null);

            assertFalse(dispatch(box, air(), dirt(1)).isCancelled());
        }
    }

    // ==================== 取出校验 withdrawFilter ====================

    @Nested
    @DisplayName("取出校验 withdrawFilter")
    class WithdrawFilterTest {

        @Test
        @DisplayName("整格取出被否决，携带提示")
        void testRejectFullWithdraw() {
            StorageBox box = new StorageBox(9, 1);
            box.withdrawFilter(item -> false, "§c已锁定");

            BeforeStoreEvent event = dispatch(box, diamond(1), air());
            assertTrue(event.isCancelled(), "取出应被否决");
            assertEquals("§c已锁定", event.denyMessage());
        }

        @Test
        @DisplayName("右键拿一半（数量减少）被否决")
        void testRejectHalfTake() {
            StorageBox box = new StorageBox(9, 1);
            box.withdrawFilter(item -> false, "§c已锁定");

            assertTrue(dispatch(box, diamond(64), diamond(32)).isCancelled(),
                    "右键拿一半应被取出校验拦截");
        }

        @Test
        @DisplayName("放入不受取出校验影响")
        void testDepositUnaffectedByWithdrawFilter() {
            StorageBox box = new StorageBox(9, 1);
            box.withdrawFilter(item -> false, "§c已锁定");

            assertFalse(dispatch(box, air(), diamond(1)).isCancelled(), "放入应不受 withdrawFilter 限制");
        }

        @Test
        @DisplayName("并入堆叠（数量增加）不触发取出校验")
        void testMergeNotCheckedAsLeaving() {
            StorageBox box = new StorageBox(9, 1);
            box.withdrawFilter(item -> false, "§c已锁定");

            assertFalse(dispatch(box, diamond(32), diamond(64)).isCancelled(),
                    "并入堆叠不是取出，不应被拦截");
        }

        @Test
        @DisplayName("交换换出的物品按取出校验")
        void testSwapOutChecked() {
            StorageBox box = new StorageBox(9, 1);
            // 放入不限 + 钻石禁止取出
            box.withdrawFilter(item -> item.getId() != Item.DIAMOND, "§c钻石禁止取出");

            // 交换：钻石换出、泥土换入
            BeforeStoreEvent event = dispatch(box, diamond(1), dirt(1));
            assertTrue(event.isCancelled(), "换出受禁物品应被否决");
            assertEquals("§c钻石禁止取出", event.denyMessage());
        }
    }

    // ==================== 组合与高级守卫 ====================

    @Nested
    @DisplayName("组合与高级守卫")
    class CombinedTest {

        @Test
        @DisplayName("交换双向校验：放入校验先失败则用放入提示")
        void testSwapDepositCheckFailsFirst() {
            StorageBox box = new StorageBox(9, 1);
            box.filter(item -> item.getId() == Item.DIAMOND, "§c仅收钻石");
            box.withdrawFilter(item -> false, "§c已锁定");

            // 交换：泥土换出（withdrawFilter 应拦）、钻石换入（filter 放行）→ 放入校验通过，
            // 取出校验失败 → 取出提示
            BeforeStoreEvent event = dispatch(box, dirt(1), diamond(1));
            assertTrue(event.isCancelled());
            assertEquals("§c已锁定", event.denyMessage());

            // 交换：钻石换出、泥土换入 → 放入校验先失败 → 放入提示
            BeforeStoreEvent event2 = dispatch(box, diamond(1), dirt(1));
            assertTrue(event2.isCancelled());
            assertEquals("§c仅收钻石", event2.denyMessage());
        }

        @Test
        @DisplayName("beforeStore 在两个 filter 之后执行，可否决")
        void testBeforeStoreHandlerRunsAfterFilters() {
            StorageBox box = new StorageBox(9, 1);
            box.filter(item -> item.getId() == Item.DIAMOND);
            box.beforeStore(e -> {
                if (e.isEntering() && e.targetItem().getCount() > 16) {
                    e.cancel("§c单次最多放入 16 个");
                }
            });

            // filter 放行（钻石）但数量超限 → 高级守卫否决
            BeforeStoreEvent event = dispatch(box, air(), diamond(32));
            assertTrue(event.isCancelled());
            assertEquals("§c单次最多放入 16 个", event.denyMessage());

            // 数量合法 → 放行
            assertFalse(dispatch(box, air(), diamond(16)).isCancelled());
        }

        @Test
        @DisplayName("filter 已否决时 beforeStore 不再执行")
        void testBeforeStoreSkippedWhenFilterDenies() {
            boolean[] handlerCalled = {false};
            StorageBox box = new StorageBox(9, 1);
            box.filter(item -> item.getId() == Item.DIAMOND, "§c仅收钻石");
            box.beforeStore(e -> handlerCalled[0] = true);

            dispatch(box, air(), dirt(1));
            assertFalse(handlerCalled[0], "filter 否决后 beforeStore 不应执行");
        }

        @Test
        @DisplayName("未设置任何守卫时默认全部放行")
        void testNoGuardAllowsAll() {
            StorageBox box = new StorageBox(9, 1);

            assertFalse(dispatch(box, air(), dirt(1)).isCancelled());
            assertFalse(dispatch(box, diamond(1), air()).isCancelled());
            assertFalse(dispatch(box, diamond(1), dirt(1)).isCancelled());
        }
    }

    // ==================== 基类机制（自定义组件路径） ====================

    @Nested
    @DisplayName("基类机制：自定义组件重写 onBeforeStore")
    class CustomComponentTest {

        /** 模拟二次开发的自定义 STORAGE 组件：禁止取出 */
        static class NoTakeSlot extends InventoryComponent {

            NoTakeSlot() {
                this.width = 1;
                this.height = 1;
            }

            @Override
            protected void onBeforeStore(BeforeStoreEvent event) {
                if (event.isWithdraw()) {
                    event.cancel("§c禁止取出");
                }
            }
        }

        @Test
        @DisplayName("自定义组件重写钩子即可拥有存取守卫")
        void testCustomComponentGuard() {
            NoTakeSlot slot = new NoTakeSlot();

            BeforeStoreEvent denied = new BeforeStoreEvent(null, 0, diamond(1), air());
            slot.dispatchBeforeStore(denied);
            assertTrue(denied.isCancelled(), "自定义组件应能否决取出");
            assertEquals("§c禁止取出", denied.denyMessage());

            BeforeStoreEvent allowed = new BeforeStoreEvent(null, 0, air(), diamond(1));
            slot.dispatchBeforeStore(allowed);
            assertFalse(allowed.isCancelled(), "放入不受自定义守卫影响");
        }
    }
}
