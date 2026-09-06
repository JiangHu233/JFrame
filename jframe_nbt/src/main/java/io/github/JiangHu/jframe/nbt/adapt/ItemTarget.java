package io.github.JiangHu.jframe.nbt.adapt;

import cn.nukkit.item.Item;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.Tag;
import io.github.JiangHu.jframe.nbt.core.NbtPathTypeException;
import io.github.JiangHu.jframe.nbt.core.path.NbtPathParser;

import java.util.Objects;

/**
 * 物品目标：包装 {@link Item}，把路径操作映射到物品的 NBT 树。
 *
 * <p>读写规则（DESIGN.md 3.6）：
 * <ul>
 *   <li>{@link #read()}：{@code item.getNamedTag()} 为 null（无 NBT 物品）时返回<b>临时空树</b>，
 *       不立即写回物品；后续 set/insert/merge 触发 {@link #write(Tag)} 时才真正挂到物品上；</li>
 *   <li>{@link #write(Tag)}：仅接受 {@link CompoundTag}（物品根必须是复合），经
 *       {@code item.setNamedTag} 回写。</li>
 * </ul>
 *
 * <p>典型用法：
 * <pre>{@code
 * Item sword = Item.get(ItemID.IRON_SWORD);
 * nbt.of(sword).set("display.Name", "铁剑");
 * }</pre>
 *
 * <p>注意：本类持有物品引用但不管理物品生命周期（不处理 saveNBT/网络同步），
 * 那是调用方（如 jframe_inventory）的职责。
 *
 * @author JiangHu
 */
public final class ItemTarget extends AbstractNbtTarget {

    private final Item item;

    /**
     * 构造物品目标。
     *
     * @param item   目标物品（不可为 null）
     * @param parser 路径编译器
     */
    public ItemTarget(Item item, NbtPathParser parser) {
        super(parser);
        this.item = Objects.requireNonNull(item, "item");
    }

    @Override
    public Tag read() {
        CompoundTag tag = item.getNamedTag();
        return tag != null ? tag : new CompoundTag();
    }

    @Override
    public void write(Tag tag) {
        if (!(tag instanceof CompoundTag compound)) {
            throw new NbtPathTypeException("物品 NBT 根必须是 Compound，实际为 " + tag.getClass().getSimpleName());
        }
        item.setNamedTag(compound);
    }

    @Override
    public String toString() {
        return "ItemTarget{item=" + item.getName() + "}";
    }
}
