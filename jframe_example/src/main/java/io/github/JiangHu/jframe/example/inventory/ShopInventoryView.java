package io.github.JiangHu.jframe.example.inventory;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.ui.component.Button;
import io.github.JiangHu.jframe.inventory.ui.component.Filler;
import io.github.JiangHu.jframe.inventory.ui.component.InventoryComponent;
import io.github.JiangHu.jframe.inventory.ui.component.Panel;
import io.github.JiangHu.jframe.inventory.ui.component.StorageBox;
import io.github.JiangHu.jframe.inventory.ui.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.ui.model.event.StoreEvent;
import io.github.JiangHu.jframe.inventory.ui.view.InventoryView;

/**
 * 箱子商店示例界面（3 行 × 9 列 = 27 格）。
 * <p>
 * 综合演示 {@code jframe_inventory} 框架的四大核心功能：
 * <ul>
 *   <li><strong>DISPLAY 格子</strong> —— 第 0 行标题装饰（灰色玻璃板），不可交互</li>
 *   <li><strong>BUTTON 格子</strong> —— 购买钻石剑 / 购买金苹果 / 清空存储 / 关闭界面</li>
 *   <li><strong>STORAGE 格子</strong> —— 左右两侧各 3 格存储区，玩家可自由放入/取出物品（含空槽默认占位物品）</li>
 *   <li><strong>LOCKED 格子</strong> —— 第 2 行底部装饰边框（默认锁定，不可改变）</li>
 * </ul>
 *
 * <h3>界面布局</h3>
 * <pre>
 * 行0:  [灰][灰][灰][灰][灰][灰][灰][灰][灰]   ← DISPLAY 标题装饰
 * 行1:  [存][存][存][剑][果][清][存][存][存]   ← STORAGE + BUTTON
 * 行2:  [灰][灰][灰][灰][关][灰][灰][灰][灰]   ← LOCKED + BUTTON(关闭)
 * </pre>
 *
 * <h3>运行方式</h3>
 * 在游戏内输入 {@code /shop} 命令即可打开本界面。
 */
public class ShopInventoryView extends InventoryView {

    public ShopInventoryView() {
        super(3, "§6§l武器商店");
    }

    @Override
    protected InventoryComponent buildRoot() {
        Panel root = new Panel(9, 3);

        // -------------------- 公共外观 --------------------

        // 灰色玻璃板（标题装饰 & 底部边框）
        SlotAppearance border = SlotAppearance.builder()
                .type(Item.STAINED_GLASS_PANE)
                .meta(7) // 灰色
                .name(" ")
                .build();

        // -------------------- 第 0 行：标题装饰（DISPLAY） --------------------

        for (int col = 0; col < 9; col++) {
            root.add(new Filler(border, 1, 1), 0, col);
        }

        // -------------------- 第 1 行：存储区 + 功能按钮 --------------------

        // 左侧存储区（列 0-2）：玩家可放入/取出物品
        StorageBox leftBox = new StorageBox(3, 1);
        leftBox.name("leftStorage");
        leftBox.onStore(this::onItemStored);
        root.add(leftBox, 1, 0);

        // 购买钻石剑（列 3）
        Button buySword = new Button(SlotAppearance.builder()
                .type(Item.DIAMOND_SWORD)
                .name("§b购买钻石剑")
                .lore("§7点击获得一把钻石剑", "§e价格: §f免费")
                .glowing()
                .build());
        buySword.onClick(click -> {
            click.player().getInventory().addItem(Item.get(Item.DIAMOND_SWORD));
            click.player().sendMessage("§b⚡ 你获得了一把钻石剑！");
        });
        root.add(buySword, 1, 3);

        // 购买金苹果（列 4）
        Button buyApple = new Button(SlotAppearance.builder()
                .type(Item.GOLDEN_APPLE)
                .name("§6购买金苹果")
                .lore("§7点击获得一个金苹果", "§e价格: §f免费")
                .build());
        buyApple.onClick(click -> {
            click.player().getInventory().addItem(Item.get(Item.GOLDEN_APPLE));
            click.player().sendMessage("§6🍎 你获得了一个金苹果！");
        });
        root.add(buyApple, 1, 4);

        // 清空存储区（列 5）—— 演示组件查找
        Button clearBtn = new Button(SlotAppearance.builder()
                .type(Item.BUCKET)
                .name("§c查找存储区")
                .lore("§7点击演示组件查找", "§8findAllByType / findByName")
                .build());
        clearBtn.name("clearBtn");
        clearBtn.onClick(click -> {
            // 演示 1：findAllByType 查找所有 StorageBox 组件
            java.util.List<StorageBox> boxes = findAllComponents(StorageBox.class);
            click.player().sendMessage("§e找到 §f" + boxes.size() + " §e个存储区组件");

            // 演示 2：findByName 按名称查找特定组件
            StorageBox left = findComponent("leftStorage", StorageBox.class);
            if (left != null) {
                click.player().sendMessage("§a左侧存储区: §f" + left.name());
            }

            // 演示 3：findByName 查找按钮自身
            Button self = findComponent("clearBtn", Button.class);
            if (self != null) {
                click.player().sendMessage("§b自身按钮: §f" + self.name());
            }

            repaint();
        });
        root.add(clearBtn, 1, 5);

        // 右侧存储区（列 6-8）
        StorageBox rightBox = new StorageBox(3, 1);
        rightBox.name("rightStorage");
        rightBox.onStore(this::onItemStored);
        root.add(rightBox, 1, 6);

        // -------------------- 第 2 行：底部边框 + 关闭按钮 --------------------

        // 左侧边框（列 0-3）
        root.add(new Filler(border, 4, 1), 2, 0);

        // 关闭按钮（列 4）
        Button closeBtn = new Button(SlotAppearance.builder()
                .type(Item.REDSTONE)
                .name("§c关闭商店")
                .lore("§7点击关闭界面")
                .build());
        closeBtn.onClick(click -> close());
        root.add(closeBtn, 2, 4);

        // 右侧边框（列 5-8）
        root.add(new Filler(border, 4, 1), 2, 5);

        return root;
    }

    /**
     * 存储区物品变化回调。
     * <p>
     * 当玩家在 STORAGE 格子放入或取出物品时触发。
     *
     * @param event 存取事件
     */
    private void onItemStored(StoreEvent event) {
        Player player = event.player();
        if (event.isDeposit()) {
            player.sendMessage("§a📦 你放入了: §f" + event.targetItem().getName());
        } else if (event.isWithdraw()) {
            player.sendMessage("§e📤 你取出了: §f" + event.sourceItem().getName());
        }
    }
}
