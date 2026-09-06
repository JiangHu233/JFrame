package io.github.JiangHu.jframe.nbt.adapt;

import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.Tag;
import io.github.JiangHu.jframe.nbt.core.path.NbtPath;
import io.github.JiangHu.jframe.nbt.core.path.NbtPathParser;
import io.github.JiangHu.jframe.nbt.core.path.NbtWriteOption;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link NbtTarget} 的模板实现：固化"读取 → 编译路径 → 引擎操作 → 写回"流程，
 * 子类只需提供 {@link #read()}/{@link #write(Tag)} 与引擎依赖。
 *
 * <p>该类属于实现细节（DESIGN.md 5.1 未列出），用于消除 Compound/Item 两个实现的重复；
 * 若 M2 需要更多目标类型（方块实体、离线玩家数据等），直接继承本类即可。
 *
 * @author JiangHu
 */
public abstract class AbstractNbtTarget implements NbtTarget {

    /** 路径编译器（无状态，可共享）。 */
    protected final NbtPathParser parser;

    /**
     * 以指定编译器构造目标。
     *
     * @param parser 路径编译器，通常由 Spring 注入共享实例
     */
    protected AbstractNbtTarget(NbtPathParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public List<Tag> get(String path) {
        return NbtPath.compile(path).select(read());
    }

    @Override
    public Tag set(String path, Tag value) {
        return set(path, value, NbtWriteOption.DEFAULT);
    }

    @Override
    public Tag set(String path, Object value) {
        return set(path, value, NbtWriteOption.DEFAULT);
    }

    /**
     * 带写入选项的 set（Tag 版本）。
     *
     * @param path   路径表达式
     * @param value  新值
     * @param option 写入选项（类型校验/自动建路/追加等）
     * @return 操作后的根节点
     */
    public Tag set(String path, Tag value, NbtWriteOption option) {
        Tag root = read();
        NbtPath.compile(path).set(root, value, option);
        write(root);
        return root;
    }

    /**
     * 带写入选项的 set（Java 值版本：推断 + autoFit，null 等价删除）。
     *
     * @param path   路径表达式
     * @param value  新值（Java 值或 Tag）
     * @param option 写入选项
     * @return 操作后的根节点
     */
    public Tag set(String path, Object value, NbtWriteOption option) {
        Tag root = read();
        NbtPath.compile(path).setObject(root, value, option);
        write(root);
        return root;
    }

    @Override
    public Tag insert(String path, Tag value) {
        Tag root = read();
        NbtPath.compile(path).insert(root, value);
        write(root);
        return root;
    }

    @Override
    public Tag insert(String path, Object value) {
        Tag root = read();
        NbtPath.compile(path).insertObject(root, value);
        write(root);
        return root;
    }

    @Override
    public Tag merge(String path, CompoundTag patch) {
        Tag root = read();
        NbtPath.compile(path).merge(root, patch);
        write(root);
        return root;
    }

    @Override
    public Tag merge(String path, Map<String, ?> patch) {
        Tag root = read();
        NbtPath.compile(path).mergeObject(root, patch);
        write(root);
        return root;
    }

    @Override
    public Tag delete(String path) {
        Tag root = read();
        NbtPath.compile(path).delete(root);
        write(root);
        return root;
    }
}
