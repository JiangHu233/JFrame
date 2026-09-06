package io.github.JiangHu.jframe.nbt.adapt;

import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.Tag;

import java.util.List;
import java.util.Map;

/**
 * NBT 操作目标抽象（DESIGN.md 3.6）：把"对哪棵 NBT 树操作、操作完写回哪里"与路径引擎解耦。
 *
 * <p>M1 提供两种实现：
 * <ul>
 *   <li>{@link CompoundTarget}：直接持有 {@link CompoundTag}，就地修改；</li>
 *   <li>{@link ItemTarget}：包装 {@link cn.nukkit.item.Item}，读取时无 NBT 返回空树，写入后经
 *       {@code setNamedTag} 回写物品。</li>
 * </ul>
 *
 * <p>接口方法均为"便捷路径字符串"版本：内部编译路径后委托引擎执行。
 * 读取类方法命中 0 个返回空列表；写入类方法语义与 {@code NbtPath} 同名方法一致。
 *
 * <p>实现类不保证线程安全；跨线程使用需外部同步（与 Nukkit 树模型本身的约束一致）。
 *
 * @author JiangHu
 * @see io.github.JiangHu.jframe.nbt.NbtAPI#of(CompoundTag)
 * @see io.github.JiangHu.jframe.nbt.NbtAPI#of(cn.nukkit.item.Item)
 */
public interface NbtTarget {

    /** 读取底层 NBT 树；目标暂无 NBT 时返回空 {@link CompoundTag}（不写入目标）。 */
    Tag read();

    /** 将（可能被就地修改过的）树写回目标；对 {@link CompoundTarget} 为空操作语义。 */
    void write(Tag tag);

    /** 选取路径命中的全部节点（文档序）；0 命中返回空列表。 */
    List<Tag> get(String path);

    /** 设置路径命中节点的值（Tag 版本，就地替换），返回根节点便于链式使用。 */
    Tag set(String path, Tag value);

    /** 设置路径命中节点的值（Java 值版本：按表 4-1 推断 + autoFit），{@code value} 为 null 时等价删除。 */
    Tag set(String path, Object value);

    /** 向路径命中的列表插入元素（Index 段定位插入 / 其余段命中列表末尾追加）。 */
    Tag insert(String path, Tag value);

    /** 向路径命中的列表插入 Java 值（推断 + autoFit）。 */
    Tag insert(String path, Object value);

    /** 深合并补丁到路径命中的复合节点（双方必须均为 Compound，否则抛 {@code NbtPathTypeException}）。 */
    Tag merge(String path, CompoundTag patch);

    /** 深合并 Java Map 补丁（Map 视为复合，内部按表 4-1 推断）。 */
    Tag merge(String path, Map<String, ?> patch);

    /** 删除路径命中的全部节点（幂等：0 命中静默返回）。 */
    Tag delete(String path);
}
