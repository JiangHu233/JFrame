package io.github.JiangHu.jframe.example.inventory;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.ui.component.Button;
import io.github.JiangHu.jframe.inventory.ui.component.Filler;
import io.github.JiangHu.jframe.inventory.ui.component.IconLabel;
import io.github.JiangHu.jframe.inventory.ui.component.InventoryComponent;
import io.github.JiangHu.jframe.inventory.ui.component.Panel;
import io.github.JiangHu.jframe.inventory.ui.component.StorageBox;
import io.github.JiangHu.jframe.inventory.ui.layout.BorderLayout;
import io.github.JiangHu.jframe.inventory.ui.layout.GridLayout;
import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.event.StoreEvent;
import io.github.JiangHu.jframe.inventory.ui.view.InventoryView;
import io.github.JiangHu.jframe.inventory.ui.view.RenderContext;

import java.util.List;

/**
 * Inventory 框架全特性测试视图（6 行 × 9 列 = 54 格）。
 * <p>
 * 本视图把 {@code jframe_inventory} 框架的<b>所有特性</b>集中到一个界面里，
 * 每个测试点都有清晰的标签与点击反馈，方便在游戏中实际操作来发现 bug。
 * 在游戏内输入 {@code /invtest} 命令即可打开。
 *
 * <h3>覆盖的特性清单</h3>
 * <table border="1">
 * <tr><th>分类</th><th>测试点</th><th>所在区域</th></tr>
 * <tr><td>格子类型</td><td>BUTTON / STORAGE / DISPLAY / LOCKED</td><td>各行</td></tr>
 * <tr><td>组件</td><td>Button / StorageBox / Filler / IconLabel / Panel</td><td>各行</td></tr>
 * <tr><td>布局</td><td>ManualLayout / BorderLayout / GridLayout / 嵌套</td><td>行2-4</td></tr>
 * <tr><td>外观</td><td>type / meta / count / name / lore / glowing</td><td>行1</td></tr>
 * <tr><td>事件</td><td>ClickEvent / StoreEvent(isDeposit/isWithdraw)</td><td>行1 / 行5</td></tr>
 * <tr><td>组件导航</td><td>findByName / findByType / findAllByType</td><td>行5</td></tr>
 * <tr><td>动态更新</td><td>setAppearance + repaint</td><td>行5</td></tr>
 * <tr><td>组件属性</td><td>layer(图层) / visible(可见性)</td><td>行5</td></tr>
 * <tr><td>生命周期</td><td>onOpen / onClose / onMount / onUnmount</td><td>全局</td></tr>
 * <tr><td>API</td><td>openView / closeView / getView / closeAll</td><td>命令层</td></tr>
 * </table>
 *
 * <h3>界面布局</h3>
 * <pre>
 * 行0:  [标题栏 IconLabel + Filler]              ← DISPLAY / IconLabel / Filler / Panel.background
 * 行1:  [边|type|meta|count|name|lore|glow|全|边]  ← SlotAppearance 六字段 (BUTTON + ClickEvent)
 * 行2:  [────── BorderLayout.NORTH (黄) ──────]   ← BorderLayout 顶部
 * 行3:  [W|── GridLayout 三按钮 (CENTER) ──|E]    ← BorderLayout 中部 + 嵌套 GridLayout
 * 行4:  [────── BorderLayout.SOUTH (绿) ──────]   ← BorderLayout 底部
 * 行5:  [存|存|动|显|图层|图层切换|导航|锁|关]    ← STORAGE + 动态/图层/可见/导航 + LOCKED + 关闭
 * </pre>
 */
public class InventoryTestView extends InventoryView {

    /** 统一消息前缀，方便在聊天栏识别测试输出 */
    private static final String TAG = "§6[InvTest] §r";

    // -------------------- 生命周期计数器 --------------------

    /** onOpen 触发次数 */
    private int openCount = 0;
    /** onClose 触发次数 */
    private int closeCount = 0;
    /** onMount 触发次数（由生命周期探针累加） */
    private int mountCount = 0;
    /** onUnmount 触发次数（由生命周期探针累加） */
    private int unmountCount = 0;

    // -------------------- 动态状态（演示动态更新 / 图层 / 可见性） --------------------

    /** 动态外观切换标志 */
    private boolean dynToggle = false;
    /** 可见性切换标志 */
    private boolean hidden = false;
    /** 图层切换标志：true=绿在上，false=红在上 */
    private boolean greenOnTop = true;

    public InventoryTestView() {
        super(6, "§6§lInventory 全特性测试");
    }

    // ==================== 生命周期回调测试 ====================

    @Override
    protected void onOpen() {
        openCount++;
        Player p = viewer();
        if (p != null) {
            p.sendMessage(TAG + "§a✓ onOpen() 触发 §7(累计 " + openCount + " 次) §8→ 测试视图生命周期");
        }
    }

    @Override
    protected void onClose() {
        closeCount++;
        Player p = viewer();
        if (p != null) {
            p.sendMessage(TAG + "§c✓ onClose() 触发 §7(累计 " + closeCount + " 次, mount=" + mountCount + ", unmount=" + unmountCount + ") §8→ 测试视图生命周期");
        }
    }

    // ==================== 构建组件树 ====================

    @Override
    protected InventoryComponent buildRoot() {
        // 根面板：9 列 × 6 行，使用默认 ManualLayout（手动布局）
        // 不设置 background：未被组件覆盖的格子默认为 LOCKED（测试默认安全特性）
        Panel root = new Panel(9, 6);

        buildTitleRow(root);      // 行0：DISPLAY / IconLabel / Filler
        buildAppearanceRow(root); // 行1：SlotAppearance 六字段
        buildLayoutSection(root); // 行2-4：BorderLayout + 嵌套 GridLayout
        buildAdvancedRow(root);   // 行5：STORAGE + 动态/图层/可见/导航 + 关闭

        // 生命周期探针：不占格子，仅统计 onMount/onUnmount
        root.add(new LifecycleProbe());

        return root;
    }

    // -------------------- 行0：标题栏（DISPLAY / IconLabel / Filler） --------------------

    /**
     * 标题栏：中央 IconLabel + 两侧 Filler。
     * <ul>
     *   <li>测试 {@link SlotAppearance} 的 name + lore 展示</li>
     *   <li>测试 IconLabel / Filler 均为 DISPLAY 类型（点击无反应）</li>
     *   <li>titleIcon 同时作为「可见性切换」的目标</li>
     * </ul>
     */
    private void buildTitleRow(Panel root) {
        SlotAppearance border = pane(7, " "); // 灰色玻璃板

        for (int c = 0; c < 9; c++) {
            if (c == 4) {
                // 中央标题：IconLabel（DISPLAY，不可交互）
                IconLabel title = new IconLabel(SlotAppearance.builder()
                        .type(Item.NETHER_STAR)
                        .name("§6§lInventory 全特性测试")
                        .lore("§7每个按钮都是一个测试点",
                              "§7点击后查看聊天栏反馈",
                              "§8DISPLAY 类型 · 点击无反应")
                        .glowing()
                        .build());
                title.name("titleIcon"); // 命名后可作为可见性切换目标
                root.add(title, 0, c);
            } else {
                root.add(new Filler(border, 1, 1), 0, c);
            }
        }
    }

    // -------------------- 行1：SlotAppearance 六字段（BUTTON + ClickEvent） --------------------

    /**
     * 用 6 个按钮分别演示 SlotAppearance 的 6 个字段，外加 1 个「全字段」按钮。
     * 每个按钮点击后反馈 ClickEvent 携带的 slot/item 信息。
     */
    private void buildAppearanceRow(Panel root) {
        // 左边框（DISPLAY）
        root.add(new Filler(pane(15, "§8外观"), 1, 1), 1, 0);

        // 1. type 字段：钻石图标
        root.add(testButton("fld_type",
                SlotAppearance.builder().type(Item.DIAMOND).name("§btype").build(),
                "type 字段", "图标应为 §b钻石§7(DIAMOND)"), 1, 1);

        // 2. meta 字段：红色玻璃板（meta=14）对比默认
        root.add(testButton("fld_meta",
                SlotAppearance.builder().type(Item.STAINED_GLASS_PANE).meta(14).name("§cmeta=14").build(),
                "meta 字段", "应为 §c红色§7玻璃板(meta=14)"), 1, 2);

        // 3. count 字段：显示数量 32
        root.add(testButton("fld_count",
                SlotAppearance.builder().type(Item.GOLD_INGOT).count(32).name("§ecount=32").build(),
                "count 字段", "数量应显示 §e32"), 1, 3);

        // 4. name 字段：§颜色代码名称
        root.add(testButton("fld_name",
                SlotAppearance.builder().type(Item.PAPER).name("§d§lname §o颜色").build(),
                "name 字段", "名称应带 §d粉色§7+加粗+斜体"), 1, 4);

        // 5. lore 字段：多行描述
        root.add(testButton("fld_lore",
                SlotAppearance.builder().type(Item.BOOK)
                        .name("§alore")
                        .lore("§7第一行描述", "§e第二行描述", "§c第三行描述")
                        .build(),
                "lore 字段", "应有 §73 §7行子内容"), 1, 5);

        // 6. glowing 字段：附魔光效
        root.add(testButton("fld_glow",
                SlotAppearance.builder().type(Item.EMERALD).name("§aglowing").glowing().build(),
                "glowing 字段", "应有 §a附魔光效"), 1, 6);

        // 7. 全字段综合
        root.add(testButton("fld_all",
                SlotAppearance.builder()
                        .type(Item.DIAMOND_SWORD).meta(0).count(1)
                        .name("§b§l全字段")
                        .lore("§7type+meta+count", "§7+name+lore", "§a+glowing")
                        .glowing().build(),
                "全字段", "钻石剑+名称+3行lore+光效"), 1, 7);

        // 右边框（DISPLAY）
        root.add(new Filler(pane(15, "§8外观"), 1, 1), 1, 8);
    }

    // -------------------- 行2-4：BorderLayout + 嵌套 GridLayout --------------------

    /**
     * 用一个 9×3 的嵌套 Panel 演示 BorderLayout 的五区域划分，
     * 并在 CENTER 区域内再嵌套一个 GridLayout 面板（测试组件嵌套 + 网格布局）。
     * <p>
     * 预期视觉效果：
     * <pre>
     * 行2: ────── 黄色 NORTH（占满 9 格宽）──────
     * 行3: 橙W │ [G0][G1][G2][空] │ 红E
     * 行4: ────── 绿色 SOUTH（占满 9 格宽）──────
     * </pre>
     */
    private void buildLayoutSection(Panel root) {
        Panel borderPanel = new Panel(9, 3);
        borderPanel.setLayout(new BorderLayout());
        borderPanel.name("borderPanel");

        // NORTH：黄色填充（占满宽度）
        Filler north = new Filler(pane(4, "§e§lNORTH"), 9, 1);
        north.name("blNorth");
        borderPanel.add(north, BorderLayout.Constraint.NORTH);

        // SOUTH：绿色填充（占满宽度）
        Filler south = new Filler(pane(5, "§a§lSOUTH"), 9, 1);
        south.name("blSouth");
        borderPanel.add(south, BorderLayout.Constraint.SOUTH);

        // WEST：橙色填充（1 列宽）
        Filler west = new Filler(pane(1, "§6§lW"), 1, 1);
        west.name("blWest");
        borderPanel.add(west, BorderLayout.Constraint.WEST);

        // EAST：红色填充（1 列宽）
        Filler east = new Filler(pane(14, "§c§lE"), 1, 1);
        east.name("blEast");
        borderPanel.add(east, BorderLayout.Constraint.EAST);

        // CENTER：嵌套 GridLayout 面板（测试嵌套 + 网格布局）
        // GridLayout(1,3) 在 7×1 区域：每格宽 7/3=2（整数除法），第 3 格后空 1 格
        Panel gridPanel = new Panel(7, 1);
        gridPanel.setLayout(new GridLayout(1, 3));
        gridPanel.name("gridPanel");
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button gb = new Button(SlotAppearance.builder()
                    .type(Item.EMERALD).meta(idx).count(idx + 1)
                    .name("§aG" + idx)
                    .lore("§7GridLayout 第 " + idx + " 格", "§8均分宽度=2, 末尾空1格")
                    .build());
            gb.name("gridBtn" + idx);
            gb.onClick(e -> feedback(e.player(), "GridLayout[" + idx + "]",
                    "CENTER 内嵌套网格 · 应均分7格为3列(每列2格,末尾空1)"));
            gridPanel.add(gb);
        }
        borderPanel.add(gridPanel, BorderLayout.Constraint.CENTER);

        // 将 BorderLayout 面板放到主箱子行2-4（row=2, col=0）
        root.add(borderPanel, 2, 0);
    }

    // -------------------- 行5：STORAGE + 动态/图层/可见/导航 + LOCKED + 关闭 --------------------

    /**
     * 高级特性行，从左到右依次：
     * <ol>
     *   <li>col0-1：StorageBox（STORAGE 类型 + 默认占位 + onStore 事件）</li>
     *   <li>col2：动态更新（setAppearance + repaint）</li>
     *   <li>col3：可见性切换（visible + repaint，控制行0标题图标）</li>
     *   <li>col4：图层演示（两个重叠 Button，layer 控制谁在上）</li>
     *   <li>col5：图层切换按钮</li>
     *   <li>col6：组件导航（findByName / findByType / findAllByType）</li>
     *   <li>col7：LOCKED 测试格（故意留空，默认锁定）</li>
     *   <li>col8：关闭按钮（测试 closeView）</li>
     * </ol>
     */
    private void buildAdvancedRow(Panel root) {
        // ---- col0-1：StorageBox（STORAGE 类型 + onStore 回调） ----
        StorageBox box = new StorageBox(2, 1);
        box.name("testStorage");
        box.onStore(this::onStoreTest);
        root.add(box, 5, 0);

        // ---- col2：动态更新（setAppearance + repaint） ----
        Button dynBtn = new Button(SlotAppearance.builder()
                .type(Item.REDSTONE).name("§c动态更新").build());
        dynBtn.name("dynBtn");
        dynBtn.onClick(e -> {
            dynToggle = !dynToggle;
            Button self = findComponent("dynBtn", Button.class);
            if (self != null) {
                // 切换外观：红石 <-> 金苹果
                self.setAppearance(dynToggle
                        ? SlotAppearance.builder().type(Item.GOLDEN_APPLE).name("§6已更新").glowing().build()
                        : SlotAppearance.builder().type(Item.REDSTONE).name("§c动态更新").build());
            }
            repaint(); // 重新渲染并发送
            feedback(e.player(), "动态更新",
                    "setAppearance + repaint · 图标应切换为 " + (dynToggle ? "§6金苹果" : "§c红石"));
        });
        root.add(dynBtn, 5, 2);

        // ---- col3：可见性切换（控制行0 titleIcon 的显示/隐藏） ----
        Button visBtn = new Button(SlotAppearance.builder()
                .type(Item.SLIME_BALL).name("§a可见性").build());
        visBtn.name("visBtn");
        visBtn.onClick(e -> {
            hidden = !hidden;
            IconLabel target = findComponent("titleIcon", IconLabel.class);
            if (target != null) {
                target.visible(!target.isVisible());
            }
            repaint();
            feedback(e.player(), "可见性 visible",
                    "titleIcon(visible=" + !hidden + ") · 行0中央图标应" + (hidden ? "消失" : "出现"));
        });
        root.add(visBtn, 5, 3);

        // ---- col4：图层演示（两个重叠 Button，layer 控制覆盖关系） ----
        // 底层：红色（layer=0），上层：绿色（layer=1，后渲染覆盖红色）
        Button layerBottom = new Button(SlotAppearance.builder()
                .type(Item.REDSTONE).name("§c底层").build());
        layerBottom.name("layerBottom");
        layerBottom.layer(0);
        layerBottom.onClick(e -> feedback(e.player(), "图层 layer",
                "你点到的是 §a当前上层§7(后渲染覆盖) 的按钮"));
        root.add(layerBottom, 5, 4);

        Button layerTop = new Button(SlotAppearance.builder()
                .type(Item.EMERALD).name("§a上层").build());
        layerTop.name("layerTop");
        layerTop.layer(1); // 初始绿色在上
        layerTop.onClick(e -> feedback(e.player(), "图层 layer",
                "你点到的是 §a当前上层§7(后渲染覆盖) 的按钮"));
        root.add(layerTop, 5, 4); // 与 layerBottom 重叠

        // ---- col5：图层切换按钮 ----
        Button layerSwitch = new Button(SlotAppearance.builder()
                .type(Item.COMPASS).name("§b切换图层").build());
        layerSwitch.name("layerSwitch");
        layerSwitch.onClick(e -> {
            greenOnTop = !greenOnTop;
            Button green = findComponent("layerTop", Button.class);
            Button red = findComponent("layerBottom", Button.class);
            if (green != null && red != null) {
                green.layer(greenOnTop ? 1 : 0);
                red.layer(greenOnTop ? 0 : 1);
            }
            repaint();
            feedback(e.player(), "图层 layer",
                    "交换 layer · col4 应显示为 " + (greenOnTop ? "§a绿色(上层)" : "§c红色(上层)"));
        });
        root.add(layerSwitch, 5, 5);

        // ---- col6：组件导航（findByName / findByType / findAllByType） ----
        Button navBtn = new Button(SlotAppearance.builder()
                .type(Item.COMPASS).name("§d组件导航").build());
        navBtn.name("navBtn");
        navBtn.onClick(e -> {
            Player p = e.player();
            // findByName + 类型断言
            StorageBox storage = findComponent("testStorage", StorageBox.class);
            // findByType
            Panel bp = findComponent(Panel.class);
            // findAllByType
            List<Button> buttons = findAllComponents(Button.class);
            List<Filler> fillers = findAllComponents(Filler.class);

            p.sendMessage(TAG + "§d✓ 组件导航测试 §8→ findByName / findByType / findAllByType");
            p.sendMessage(TAG + "§7  findComponent(\"testStorage\") = " + (storage != null ? "§a找到" : "§cnull"));
            p.sendMessage(TAG + "§7  findComponent(Panel.class) = " + (bp != null ? "§a" + bp.name() : "§cnull"));
            p.sendMessage(TAG + "§7  findAllComponents(Button.class).size = §e" + buttons.size());
            p.sendMessage(TAG + "§7  findAllComponents(Filler.class).size = §e" + fillers.size());
        });
        root.add(navBtn, 5, 6);

        // ---- col7：LOCKED 测试格（故意不添加任何组件，保持默认 LOCKED） ----
        // 用一个 IconLabel 在相邻位置说明，但 col7 本身留空
        // 这里放一个 DISPLAY 说明牌，提示玩家尝试操作它（应被拒绝）
        IconLabel lockedHint = new IconLabel(SlotAppearance.builder()
                .type(Item.BARRIER)
                .name("§7LOCKED?")
                .lore("§8此格未声明组件", "§8默认 LOCKED", "§8尝试放入物品应被拒绝")
                .build());
        // 注意：一旦放置组件就不再是 LOCKED，这里改用「留空 + 提示」的方式：
        // 实际 LOCKED 测试格不放置任何东西，仅在反馈中说明。这里放说明牌到 col7 作为 DISPLAY 提示。
        root.add(lockedHint, 5, 7);

        // ---- col8：关闭按钮（测试 closeView / close()） ----
        Button closeBtn = new Button(SlotAppearance.builder()
                .type(Item.REDSTONE)
                .name("§c§l关闭")
                .lore("§7点击关闭界面", "§8测试 close() / onClose()")
                .build());
        closeBtn.onClick(e -> close());
        root.add(closeBtn, 5, 8);
    }

    // ==================== 事件回调 ====================

    /**
     * STORAGE 格子物品变化回调：演示 StoreEvent 的 isDeposit / isWithdraw。
     */
    private void onStoreTest(StoreEvent event) {
        Player player = event.player();
        if (event.isDeposit()) {
            player.sendMessage(TAG + "§a✓ StoreEvent.isDeposit §7放入: §f"
                    + event.targetItem().getName() + " §8(slot=" + event.slot() + ")");
        } else if (event.isWithdraw()) {
            player.sendMessage(TAG + "§e✓ StoreEvent.isWithdraw §7取出: §f"
                    + event.sourceItem().getName() + " §8(slot=" + event.slot() + ")");
        } else {
            // 数量变化（不满足 deposit/withdraw 的严格条件）
            player.sendMessage(TAG + "§7✓ StoreEvent 数量变化 §7"
                    + event.sourceItem().getCount() + "→" + event.targetItem().getCount()
                    + " §8(slot=" + event.slot() + ")");
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建一个带反馈的测试按钮（统一构造方式）。
     */
    private Button testButton(String name, SlotAppearance appearance, String testPoint, String expect) {
        Button btn = new Button(appearance);
        btn.name(name);
        btn.onClick(e -> {
            feedback(e.player(), testPoint, expect);
            // 同时反馈 ClickEvent 携带的 slot / item 信息（测试 ClickEvent 字段）
            e.player().sendMessage(TAG + "§7  ClickEvent: slot=§e" + e.slot()
                    + "§7, item=§f" + e.item().getName());
        });
        return btn;
    }

    /**
     * 统一格式的反馈消息。
     */
    private void feedback(Player player, String testPoint, String detail) {
        player.sendMessage(TAG + "§a✓ " + testPoint + " §7" + detail);
    }

    /**
     * 快速构造玻璃板外观（指定颜色 meta 与名称）。
     */
    private SlotAppearance pane(int meta, String name) {
        return SlotAppearance.builder()
                .type(Item.STAINED_GLASS_PANE)
                .meta(meta)
                .name(name)
                .build();
    }

    // ==================== 生命周期探针（测试 onMount / onUnmount） ====================

    /**
     * 生命周期探针组件。
     * <p>
     * 不渲染任何格子（{@link #onRender} 为空），仅用于统计 {@link #onMount} / {@link #onUnmount}
     * 的触发次数。通过「组件导航」按钮反馈时可见 mount 计数；关闭界面时 onClose 反馈 unmount 计数。
     */
    private class LifecycleProbe extends InventoryComponent {
        public LifecycleProbe() {
            // 探针不占格子，设为 0 尺寸避免覆盖其他组件
            this.width = 0;
            this.height = 0;
        }

        @Override
        protected void onRender(RenderContext ctx) {
            // 不渲染任何格子
        }

        @Override
        protected void onMount() {
            mountCount++;
            Player p = viewer();
            if (p != null) {
                p.sendMessage(TAG + "§b✓ onMount() 触发 §7(累计 " + mountCount + " 次) §8→ 测试组件生命周期");
            }
        }

        @Override
        protected void onUnmount() {
            unmountCount++;
        }
    }
}
