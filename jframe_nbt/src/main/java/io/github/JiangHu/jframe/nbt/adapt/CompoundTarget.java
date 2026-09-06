package io.github.JiangHu.jframe.nbt.adapt;

import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.Tag;
import io.github.JiangHu.jframe.nbt.core.path.NbtPathParser;

import java.util.Objects;

/**
 * 纯复合树目标：直接持有 {@link CompoundTag} 引用，所有操作就地修改该树。
 *
 * <p>典型用法：
 * <pre>{@code
 * CompoundTag root = new CompoundTag();
 * nbt.of(root).set("display.Name", "铁剑");
 * }</pre>
 *
 * <p>{@link #write(Tag)} 为空操作（树本身就是存储），保留接口对称性。
 *
 * @author JiangHu
 */
public final class CompoundTarget extends AbstractNbtTarget {

    private final CompoundTag root;

    /**
     * 构造复合树目标。
     *
     * @param root   目标树（不可为 null，操作就地生效）
     * @param parser 路径编译器
     */
    public CompoundTarget(CompoundTag root, NbtPathParser parser) {
        super(parser);
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public Tag read() {
        return root;
    }

    @Override
    public void write(Tag tag) {
        // 就地修改语义：树即存储，无需回写
    }

    @Override
    public String toString() {
        return "CompoundTarget{root=" + root.getName() + "}";
    }
}
