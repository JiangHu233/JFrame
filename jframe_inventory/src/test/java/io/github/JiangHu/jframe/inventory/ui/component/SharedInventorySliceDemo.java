package io.github.JiangHu.jframe.inventory.ui.component;

import cn.nukkit.inventory.Inventory;
import cn.nukkit.inventory.InventoryType;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.codec.FakeInventory;
import io.github.JiangHu.jframe.inventory.ui.model.event.StoreEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SharedInventorySlice} 效果演示 —— 搜打撤共享战利品箱。
 * <p>
 * 本类是「带控制台输出的活文档」，通过模拟搜打撤（Search & Extract）游戏中的
 * 战利品箱场景，直观展示多个玩家如何通过 {@code SharedInventorySlice} 共享同一个外部库存。
 * <p>
 * <b>查看演示输出：</b>在 IDE 中右键运行本类（或在 Maven 测试报告中查看 surefire stdout），
 * 即可看到完整的多人共享库存操作流程。
 *
 * <h3>演示场景</h3>
 * <ol>
 *   <li>{@link #demoTwoPlayerLoot()} —— 双人搜打撤：A 取走物品，B 实时看到变化</li>
 *   <li>{@link #demoThreePlayerLoot()} —— 三人混战：一人取走，两人同步刷新</li>
 *   <li>{@link #demoPlayerLeaveAndRejoin()} —— 玩家撤离与重返：关闭界面后不再收到通知</li>
 * </ol>
 *
 * <h3>模拟原理</h3>
 * <p>
 * 生产环境中，每个玩家有独立的 {@code InventoryView} 和 {@code VirtualInventory}。
 * 本演示用 {@link PlayerView} 模拟玩家的客户端视图（本地物品数组），
 * 用 {@link DemoSlice} 模拟切片收到通知后刷新视图的行为。
 * 数据流与生产环境完全一致：
 * <pre>
 *   玩家A 取走物品
 *     → SharedInventorySlice.onStore()
 *       → 写回外部库存
 *       → SharedInventorySync.notifyChanged()
 *         → 玩家B 的 DemoSlice.onExternalChanged()
 *           → PlayerView.refreshFromExternal()（模拟 view.setSlotItem + 发包）
 * </pre>
 *
 * @see SharedInventorySlice
 * @see SharedInventorySync
 */
@DisplayName("SharedInventorySlice 效果演示 — 搜打撤共享战利品箱")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SharedInventorySliceDemo {

    // ================================================================
    //  场景模拟器
    // ================================================================

    /**
     * 模拟一个玩家的客户端视图。
     * <p>
     * 持有本地物品数组（模拟 VirtualInventory），当收到外部库存变更通知时，
     * 从外部库存读取最新物品更新本地视图——与生产环境中 {@code onExternalChanged}
     * 调用 {@code view.setSlotItem()} 的行为一致。
     */
    static class PlayerView {

        final String name;
        final DemoSlice slice;
        final int startSlot;
        final Item[] screen;

        PlayerView(String name, Inventory external, int startSlot, int width, int height) {
            this.name = name;
            this.startSlot = startSlot;
            this.slice = new DemoSlice(external, startSlot, width, height, this);
            this.screen = new Item[width * height];
            // 注册存取回调，打印操作日志
            this.slice.onStore(event -> {
                if (event.isWithdraw()) {
                    System.out.println("    [回调] " + name + " 取走了 " + fmt(event.sourceItem()));
                } else if (event.isDeposit()) {
                    System.out.println("    [回调] " + name + " 放入了 " + fmt(event.targetItem()));
                }
            });
        }

        /** 模拟打开界面：注册切片 + 从外部库存加载初始数据 */
        void open() {
            SharedInventorySync.register(slice);
            for (int i = 0; i < screen.length; i++) {
                Item item = slice.externalInventory().getItem(startSlot + i);
                screen[i] = item != null ? item : Item.get(Item.AIR);
            }
        }

        /** 模拟关闭界面：注销切片 */
        void close() {
            SharedInventorySync.unregister(slice);
        }

        /** 模拟玩家取走指定格子的物品（linearSlot 为切片内的线性序号） */
        void withdraw(int linearSlot) {
            assertTrue(linearSlot >= 0 && linearSlot < screen.length,
                    "linearSlot 越界: " + linearSlot);
            assertFalse(screen[linearSlot].isNull(),
                    name + " 的格子 [" + linearSlot + "] 是空的，无法取出");
            Item source = screen[linearSlot];
            screen[linearSlot] = Item.get(Item.AIR);
            // width=9 时 linearSlot == absSlot（absBaseRow=absBaseCol=0）
            slice.dispatchStore(linearSlot, null, source, Item.get(Item.AIR));
        }

        /** 模拟玩家向指定格子放入物品 */
        void deposit(int linearSlot, Item item) {
            assertTrue(linearSlot >= 0 && linearSlot < screen.length,
                    "linearSlot 越界: " + linearSlot);
            Item source = screen[linearSlot];
            screen[linearSlot] = item;
            slice.dispatchStore(linearSlot, null, source, item);
        }

        /** 收到外部变更通知后，从外部库存刷新本地视图 */
        void refreshFromExternal(int externalSlot) {
            int relSlot = externalSlot - startSlot;
            if (relSlot >= 0 && relSlot < screen.length) {
                Item item = slice.externalInventory().getItem(externalSlot);
                screen[relSlot] = item != null ? item : Item.get(Item.AIR);
            }
        }

        /** 获取本地视图中指定格子的物品 */
        Item viewOf(int linearSlot) {
            return screen[linearSlot];
        }

        /** 打印玩家当前屏幕内容（仅显示有物品的格子） */
        void printScreen() {
            StringBuilder sb = new StringBuilder();
            sb.append("  [").append(name).append("] 界面: ");
            boolean any = false;
            for (int i = 0; i < screen.length; i++) {
                if (!screen[i].isNull()) {
                    if (any) sb.append(", ");
                    sb.append("[").append(i).append("]=").append(fmt(screen[i]));
                    any = true;
                }
            }
            if (!any) sb.append("(全部为空)");
            System.out.println(sb);
        }
    }

    /**
     * 演示用切片子类。
     * <p>
     * 覆盖 {@code onExternalChanged}，将变更通知转发给关联的 {@link PlayerView}，
     * 使其从外部库存读取最新物品刷新本地视图——模拟生产环境中刷新 VirtualInventory + 发包。
     */
    static class DemoSlice extends SharedInventorySlice {

        final PlayerView playerView;

        DemoSlice(Inventory external, int startSlot, int width, int height, PlayerView view) {
            super(external, startSlot, width, height);
            this.playerView = view;
        }

        @Override
        void onExternalChanged(int externalSlot) {
            playerView.refreshFromExternal(externalSlot);
        }
    }

    // ================================================================
    //  输出辅助方法
    // ================================================================

    private static void section(String title) {
        System.out.println();
        System.out.println("---------------------------------------------------------------");
        System.out.println("  " + title);
        System.out.println("---------------------------------------------------------------");
    }

    private static void step(String msg) {
        System.out.println();
        System.out.println("> " + msg);
    }

    private static void note(String msg) {
        System.out.println("    " + msg);
    }

    private static void printExternal(Inventory inv, int slots) {
        StringBuilder sb = new StringBuilder();
        sb.append("  [战利品箱] 外部库存: ");
        boolean any = false;
        for (int i = 0; i < slots; i++) {
            Item item = inv.getItem(i);
            if (!item.isNull()) {
                if (any) sb.append(", ");
                sb.append("[").append(i).append("]=").append(fmt(item));
                any = true;
            }
        }
        if (!any) sb.append("(全部为空)");
        System.out.println(sb);
    }

    private static String fmt(Item item) {
        if (item == null || item.isNull()) return "空";
        String name = itemName(item.getId());
        int count = item.getCount();
        return count > 1 ? name + "x" + count : name;
    }

    private static String itemName(int id) {
        if (id == Item.DIAMOND_SWORD) return "钻石剑";
        if (id == Item.DIAMOND) return "钻石";
        if (id == Item.IRON_INGOT) return "铁锭";
        if (id == Item.APPLE) return "苹果";
        if (id == Item.GOLD_INGOT) return "金锭";
        if (id == Item.BOW) return "弓";
        if (id == Item.ARROW) return "箭矢";
        if (id == Item.EMERALD) return "绿宝石";
        return "物品#" + id;
    }

    // ================================================================
    //  测试间清理
    // ================================================================

    private final List<PlayerView> allViews = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (PlayerView v : allViews) {
            v.close();
        }
        allViews.clear();
    }

    // ================================================================
    //  演示 1：双人搜打撤
    // ================================================================

    @Test
    @Order(1)
    @DisplayName("场景1：双人搜打撤 — A取走钻石剑，B实时看到格子变空")
    void demoTwoPlayerLoot() {
        section("场景1：双人搜打撤 — 共享战利品箱");

        // —— 1. 创建战利品箱 ——
        step("在地图上生成一个战利品箱（外部库存）");
        Inventory loot = new FakeInventory(null, InventoryType.CHEST);
        loot.setItem(0, Item.get(Item.DIAMOND_SWORD));
        loot.setItem(2, Item.get(Item.IRON_INGOT, 0, 16));
        loot.setItem(3, Item.get(Item.APPLE, 0, 3));
        loot.setItem(5, Item.get(Item.GOLD_INGOT, 0, 8));
        loot.setItem(7, Item.get(Item.BOW));
        printExternal(loot, 9);

        // —— 2. 两个玩家打开同一个箱子 ——
        step("玩家 Alice 和 Bob 同时打开这个箱子");
        PlayerView alice = new PlayerView("Alice", loot, 0, 9, 1);
        PlayerView bob = new PlayerView("Bob", loot, 0, 9, 1);
        allViews.add(alice);
        allViews.add(bob);
        alice.open();
        bob.open();
        note("当前活跃切片数: " + SharedInventorySync.sliceCount(loot));
        alice.printScreen();
        bob.printScreen();

        // —— 3. Alice 取走钻石剑 ——
        step("Alice 从格子[0]取走钻石剑");
        alice.withdraw(0);
        note("^ 外部库存已更新，Bob 的切片收到通知并自动刷新");
        printExternal(loot, 9);
        alice.printScreen();
        bob.printScreen();

        // 断言
        assertTrue(loot.getItem(0).isNull(), "外部库存[0]应为空");
        assertTrue(alice.viewOf(0).isNull(), "Alice 视图[0]应为空");
        assertTrue(bob.viewOf(0).isNull(), "Bob 视图[0]应已同步为空");
        assertFalse(bob.viewOf(2).isNull(), "Bob 视图[2]铁锭应仍在");

        // —— 4. Bob 取走铁锭 ——
        step("Bob 从格子[2]取走铁锭x16");
        bob.withdraw(2);
        note("^ 外部库存已更新，Alice 的切片收到通知并自动刷新");
        printExternal(loot, 9);
        alice.printScreen();
        bob.printScreen();

        assertTrue(loot.getItem(2).isNull(), "外部库存[2]应为空");
        assertTrue(alice.viewOf(2).isNull(), "Alice 视图[2]应已同步为空");
        assertTrue(bob.viewOf(2).isNull(), "Bob 视图[2]应为空");

        // —— 5. Alice 放入一个苹果 ——
        step("Alice 向格子[1]放入苹果x1（搜打撤中把多余物资存入公共箱）");
        alice.deposit(1, Item.get(Item.APPLE));
        note("^ 外部库存已更新，Bob 的切片收到通知并自动刷新");
        printExternal(loot, 9);
        alice.printScreen();
        bob.printScreen();

        assertFalse(loot.getItem(1).isNull(), "外部库存[1]应有苹果");
        assertEquals(Item.APPLE, bob.viewOf(1).getId(), "Bob 视图[1]应已同步为苹果");
    }

    // ================================================================
    //  演示 2：三人混战
    // ================================================================

    @Test
    @Order(2)
    @DisplayName("场景2：三人混战 — 一人取走，两人同步刷新")
    void demoThreePlayerLoot() {
        section("场景2：三人混战 — 同时搜索同一箱子");

        step("生成一个装满战利品的箱子");
        Inventory loot = new FakeInventory(null, InventoryType.CHEST);
        loot.setItem(0, Item.get(Item.DIAMOND, 0, 5));
        loot.setItem(1, Item.get(Item.GOLD_INGOT, 0, 10));
        loot.setItem(2, Item.get(Item.IRON_INGOT, 0, 32));
        printExternal(loot, 3);

        step("三个玩家同时打开箱子");
        PlayerView alice = new PlayerView("Alice", loot, 0, 9, 1);
        PlayerView bob = new PlayerView("Bob", loot, 0, 9, 1);
        PlayerView charlie = new PlayerView("Charlie", loot, 0, 9, 1);
        allViews.add(alice);
        allViews.add(bob);
        allViews.add(charlie);
        alice.open();
        bob.open();
        charlie.open();
        note("当前活跃切片数: " + SharedInventorySync.sliceCount(loot));

        step("Alice 抢先取走格子[0]的钻石x5");
        alice.withdraw(0);
        note("^ Bob 和 Charlie 同时收到通知，界面自动刷新");
        printExternal(loot, 3);
        bob.printScreen();
        charlie.printScreen();

        // 断言：两个旁观者都看到了变化
        assertTrue(bob.viewOf(0).isNull(), "Bob 应看到[0]已空");
        assertTrue(charlie.viewOf(0).isNull(), "Charlie 应看到[0]已空");
        assertFalse(bob.viewOf(1).isNull(), "Bob 应看到[1]金锭仍在");
        assertFalse(charlie.viewOf(2).isNull(), "Charlie 应看到[2]铁锭仍在");

        step("Bob 紧接着取走格子[1]的金锭x10");
        bob.withdraw(1);
        note("^ Alice 和 Charlie 同时收到通知");
        printExternal(loot, 3);
        alice.printScreen();
        charlie.printScreen();

        assertTrue(alice.viewOf(1).isNull(), "Alice 应看到[1]已空");
        assertTrue(charlie.viewOf(1).isNull(), "Charlie 应看到[1]已空");

        step("Charlie 取走最后的铁锭x32");
        charlie.withdraw(2);
        note("^ Alice 和 Bob 同时收到通知");
        printExternal(loot, 3);
        alice.printScreen();
        bob.printScreen();

        assertTrue(loot.getItem(0).isNull(), "外部库存应全部被搜刮完");
        assertTrue(loot.getItem(1).isNull(), "外部库存应全部被搜刮完");
        assertTrue(loot.getItem(2).isNull(), "外部库存应全部被搜刮完");
    }

    // ================================================================
    //  演示 3：玩家撤离与重返
    // ================================================================

    @Test
    @Order(3)
    @DisplayName("场景3：玩家撤离与重返 — 关闭界面后不再收到通知")
    void demoPlayerLeaveAndRejoin() {
        section("场景3：玩家撤离与重返");

        step("生成战利品箱");
        Inventory loot = new FakeInventory(null, InventoryType.CHEST);
        loot.setItem(0, Item.get(Item.DIAMOND_SWORD));
        loot.setItem(4, Item.get(Item.APPLE, 0, 5));
        printExternal(loot, 9);

        step("Alice 和 Bob 打开箱子");
        PlayerView alice = new PlayerView("Alice", loot, 0, 9, 1);
        PlayerView bob = new PlayerView("Bob", loot, 0, 9, 1);
        allViews.add(alice);
        allViews.add(bob);
        alice.open();
        bob.open();
        note("活跃切片数: " + SharedInventorySync.sliceCount(loot));

        step("Bob 关闭界面（撤离搜打撤区域）");
        bob.close();
        note("活跃切片数: " + SharedInventorySync.sliceCount(loot) + "（应为 1）");
        assertEquals(1, SharedInventorySync.sliceCount(loot), "Bob 关闭后应只剩 1 个切片");

        step("Alice 取走格子[0]的钻石剑");
        alice.withdraw(0);
        note("^ Bob 已注销切片，不会收到通知");
        note("^ Bob 的本地视图保持旧状态（未刷新）");
        printExternal(loot, 9);
        bob.printScreen();
        // Bob 的视图仍显示旧数据（钻石剑还在）
        assertFalse(bob.viewOf(0).isNull(),
                "Bob 已撤离，本地视图应保持旧状态（钻石剑仍在）");

        step("Bob 返回搜打撤区域，重新打开箱子");
        bob.open();
        note("活跃切片数: " + SharedInventorySync.sliceCount(loot) + "（应为 2）");
        note("^ 重新打开时从外部库存加载最新数据");
        bob.printScreen();
        // Bob 重新打开后，看到了最新状态（钻石剑已被 Alice 取走）
        assertTrue(bob.viewOf(0).isNull(), "Bob 重新打开后应看到[0]已空");
        assertFalse(bob.viewOf(4).isNull(), "Bob 应看到[4]苹果仍在");

        step("Alice 再取走格子[4]的苹果x5");
        alice.withdraw(4);
        note("^ 这次 Bob 在线，应收到通知");
        printExternal(loot, 9);
        bob.printScreen();
        assertTrue(bob.viewOf(4).isNull(), "Bob 应看到[4]苹果已被取走");
    }
}
