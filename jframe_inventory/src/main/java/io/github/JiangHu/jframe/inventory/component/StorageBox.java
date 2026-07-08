package io.github.JiangHu.jframe.inventory.component;

import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.inventory.model.SlotAppearance;
import io.github.JiangHu.jframe.inventory.model.SlotType;
import io.github.JiangHu.jframe.inventory.model.event.StoreEvent;
import io.github.JiangHu.jframe.inventory.view.RenderContext;

import java.util.function.Consumer;

/**
 * 存储格组件。
 * <p>
 * 将指定区域内的格子标记为 {@link SlotType#STORAGE}，允许玩家自由放入/取出物品。
 * 物品变化时触发 {@code onStore} 回调。
 * <p>
 * <strong>注意</strong>：STORAGE 格子不设置框架外观（appearance 为 null），
 * 因此不会覆盖玩家放入的物品。
 *
 * <h3>默认物品（空槽占位）</h3>
 * <p>
 * 可通过 {@link #defaultItem(SlotAppearance)} 为存储格设置一个「空槽占位物品」：
 * 当格子为空时显示该物品作为视觉提示，玩家放入物品后占位物品会被真实物品覆盖。
 *
 * <pre>{@code
 * StorageBox box = new StorageBox(3, 1);  // 3 格宽、1 格高的存储区
 * box.defaultItem(SlotAppearance.builder()   // 空槽时显示灰色玻璃板占位
 *         .type(Item.STAINED_GLASS_PANE)
 *         .meta(7)
 *         .name("§7放入物品")
 *         .build());
 * box.onStore(event -> {
 *     if (event.isDeposit()) {
 *         player.sendMessage("你放入了物品！");
 *     }
 * });
 * }</pre>
 *
 * @see SlotType#STORAGE
 */
public class StorageBox extends InventoryComponent {

    /** 存取回调（可选） */
    private Consumer<StoreEvent> storeHandler;

    /** 默认物品外观（空槽占位，可选） */
    private SlotAppearance defaultAppearance;

    /**
     * 创建存储格组件。
     *
     * @param width  宽度（格子数）
     * @param height 高度（格子数）
     */
    public StorageBox(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * 设置存取回调。
     *
     * @param handler 回调函数
     * @return 当前组件（链式调用）
     */
    public StorageBox onStore(Consumer<StoreEvent> handler) {
        this.storeHandler = handler;
        return this;
    }

    /**
     * 设置空槽占位物品。
     * <p>
     * 当存储格为空时显示该物品作为视觉占位；玩家放入物品后占位物品会被覆盖。
     *
     * @param defaultAppearance 占位物品外观，{@code null} 表示不显示占位物品
     * @return 当前组件（链式调用）
     */
    public StorageBox defaultItem(SlotAppearance defaultAppearance) {
        this.defaultAppearance = defaultAppearance;
        return this;
    }

    /**
     * 设置空槽占位物品（便捷方法，传入原生物品）。
     *
     * @param item 占位物品，{@code null} 表示清除占位设置
     * @return 当前组件（链式调用）
     */
    public StorageBox defaultItem(Item item) {
        if (item == null) {
            this.defaultAppearance = null;
        } else {
            this.defaultAppearance = SlotAppearance.builder()
                    .type(item.getId())
                    .meta(item.getDamage())
                    .count(item.getCount())
                    .name(item.hasCustomName() ? item.getCustomName() : null)
                    .build();
        }
        return this;
    }

    /**
     * 获取当前默认物品外观。
     *
     * @return 默认物品外观，未设置时返回 null
     */
    public SlotAppearance defaultItem() {
        return defaultAppearance;
    }

    @Override
    protected void onRender(RenderContext ctx) {
        // 将区域内所有格子标记为 STORAGE 类型
        // appearance 传 null：不覆盖玩家放入的物品
        // defaultAppearance 传占位物品：空槽时由框架显示
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                ctx.slot(r, c, null, defaultAppearance, SlotType.STORAGE);
            }
        }
    }

    @Override
    protected void onStore(StoreEvent event) {
        if (storeHandler != null) {
            storeHandler.accept(event);
        }
    }
}
