package io.github.JiangHu.jframe.inventory.ui.model;

import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 物品外观（SlotAppearance）。
 * <p>
 * 把「格子上物品长什么样」抽象成可编程的样式对象，与组件逻辑解耦。
 * 一个 {@code SlotAppearance} 完整描述格子的<strong>外观</strong>（物品种类/图标）
 * 与<strong>展示信息</strong>（名称、子内容），开发者无需直接操作原生 {@link Item} API。
 *
 * <h3>外观与展示信息字段</h3>
 * <table border="1">
 * <tr><th>维度</th><th>字段</th><th>说明</th></tr>
 * <tr><td>外观</td><td>{@code itemId}</td><td>物品种类（决定图标外观）</td></tr>
 * <tr><td></td><td>{@code meta}</td><td>数据值/损伤值（同种物品的不同变体）</td></tr>
 * <tr><td></td><td>{@code count}</td><td>显示数量</td></tr>
 * <tr><td>展示信息</td><td>{@code name}</td><td>自定义名称（支持 § 颜色代码与格式）</td></tr>
 * <tr><td></td><td>{@code lore}</td><td>子内容/描述（多行文字列表）</td></tr>
 * <tr><td>视觉特效</td><td>{@code glowing}</td><td>附魔光效（无需真实附魔）</td></tr>
 * </table>
 *
 * <h3>Builder 构建模式</h3>
 * <pre>{@code
 * SlotAppearance appearance = SlotAppearance.builder()
 *     .type(Item.DIAMOND_SWORD)         // 外观：钻石剑图标
 *     .name("§b§l购买武器")              // 名称：蓝色加粗
 *     .lore("§7点击购买一把武器",         // 子内容：多行描述
 *           "§e价格: §f100 金币")
 *     .glowing(true)                     // 发光特效
 *     .build();
 * }</pre>
 *
 * @see SlotType
 */
public class SlotAppearance {

    /** 物品种类 ID（决定图标外观），默认空气 */
    private final int itemId;
    /** 数据值/损伤值，null 表示默认 */
    private final Integer meta;
    /** 显示数量，默认 1 */
    private final int count;
    /** 自定义名称（支持 § 颜色代码），null 表示使用默认名称 */
    private final String name;
    /** 子内容/描述（多行文字），空列表表示不设置 */
    private final List<String> lore;
    /** 是否显示附魔光效 */
    private final boolean glowing;

    private SlotAppearance(Builder builder) {
        this.itemId = builder.itemId;
        this.meta = builder.meta;
        this.count = builder.count;
        this.name = builder.name;
        this.lore = new ArrayList<>(builder.lore);
        this.glowing = builder.glowing;
    }

    /**
     * 创建 Builder。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从原生 {@link Item} 对象创建外观（便捷工厂方法）。
     * <p>
     * 提取物品的 ID、数据值、数量、自定义名称，构造对应的 {@code SlotAppearance}。
     * 空气物品（{@link Item#AIR}）或 null 返回 null。
     *
     * @param item 原生物品
     * @return 对应的 SlotAppearance，空气或 null 物品返回 null
     */
    public static SlotAppearance fromItem(Item item) {
        if (item == null || item.isNull()) {
            return null;
        }
        return builder()
                .type(item.getId())
                .meta(item.getDamage())
                .count(item.getCount())
                .name(item.hasCustomName() ? item.getCustomName() : null)
                .build();
    }

    /**
     * 将外观转换为原生 {@link Item} 对象。
     * <p>
     * 供框架内部渲染时使用，开发者一般无需直接调用。
     *
     * @return 对应的 Nukkit 物品
     */
    public Item toItem() {
        Item item = Item.get(itemId, meta == null ? 0 : meta, count);
        if (name != null) {
            item.setCustomName(name);
        }
        if (!lore.isEmpty()) {
            item.setLore(lore.toArray(new String[0]));
        }
        if (glowing) {
            // 使用效率附魔产生光效（等级 0 避免实际影响游戏性）
            Enchantment ench = Enchantment.getEnchantment(Enchantment.ID_EFFICIENCY);
            if (ench != null) {
                ench.setLevel(1);
                item.addEnchantment(ench);
            }
        }
        return item;
    }

    // -------------------- Getter --------------------

    /** 物品种类 ID */
    public int itemId() {
        return itemId;
    }

    /** 数据值，null 表示默认 */
    public Integer meta() {
        return meta;
    }

    /** 显示数量 */
    public int count() {
        return count;
    }

    /** 自定义名称，null 表示未设置 */
    public String name() {
        return name;
    }

    /** 子内容列表（不可变副本） */
    public List<String> lore() {
        return List.copyOf(lore);
    }

    /** 是否发光 */
    public boolean glowing() {
        return glowing;
    }

    // -------------------- Builder --------------------

    /**
     * SlotAppearance 构建器，支持链式调用。
     */
    public static class Builder {
        private int itemId = Item.AIR;
        private Integer meta = null;
        private int count = 1;
        private String name = null;
        private final List<String> lore = new ArrayList<>();
        private boolean glowing = false;

        private Builder() {
        }

        /**
         * 设置物品种类（决定图标外观）。
         *
         * @param itemId 物品 ID（如 {@link Item#DIAMOND_SWORD}）
         * @return 当前 Builder
         */
        public Builder type(int itemId) {
            this.itemId = itemId;
            return this;
        }

        /**
         * 设置数据值/损伤值。
         *
         * @param meta 数据值
         * @return 当前 Builder
         */
        public Builder meta(int meta) {
            this.meta = meta;
            return this;
        }

        /**
         * 设置显示数量。
         *
         * @param count 数量
         * @return 当前 Builder
         */
        public Builder count(int count) {
            this.count = count;
            return this;
        }

        /**
         * 设置自定义名称（支持 § 颜色代码与格式）。
         *
         * @param name 名称
         * @return 当前 Builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置子内容/描述（多行文字）。
         *
         * @param lines 多行描述
         * @return 当前 Builder
         */
        public Builder lore(String... lines) {
            this.lore.addAll(Arrays.asList(lines));
            return this;
        }

        /**
         * 添加一行子内容。
         *
         * @param line 单行描述
         * @return 当前 Builder
         */
        public Builder addLore(String line) {
            this.lore.add(line);
            return this;
        }

        /**
         * 设置是否发光（附魔光效）。
         *
         * @param glowing 是否发光
         * @return 当前 Builder
         */
        public Builder glowing(boolean glowing) {
            this.glowing = glowing;
            return this;
        }

        /**
         * 启用发光效果（等价于 {@code glowing(true)}）。
         *
         * @return 当前 Builder
         */
        public Builder glowing() {
            this.glowing = true;
            return this;
        }

        /**
         * 构建外观对象。
         *
         * @return 不可变的 SlotAppearance
         */
        public SlotAppearance build() {
            return new SlotAppearance(this);
        }
    }
}
