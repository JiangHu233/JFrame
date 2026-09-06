package io.github.JiangHu.jframe.nbt.core.value;

import cn.nukkit.nbt.tag.ByteArrayTag;
import cn.nukkit.nbt.tag.ByteTag;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.DoubleTag;
import cn.nukkit.nbt.tag.EndTag;
import cn.nukkit.nbt.tag.FloatTag;
import cn.nukkit.nbt.tag.IntArrayTag;
import cn.nukkit.nbt.tag.IntTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.nbt.tag.LongTag;
import cn.nukkit.nbt.tag.ShortTag;
import cn.nukkit.nbt.tag.StringTag;
import cn.nukkit.nbt.tag.Tag;

/**
 * NBT 类型枚举 —— 对 {@link Tag} 子类体系的枚举化描述（DESIGN.md 5.1）。
 *
 * <p>供 {@link SnbtLiteral}（过滤字面量的类型锚定）与 {@link NbtValues}（类型识别层）
 * 使用，使「NBT 类型」成为可比较、可序列化到消息中的一等值。
 *
 * <p>{@link #LONG_ARRAY} 为<b>预留占位</b>：Nukkit MOT 平台当前没有 {@code LongArrayTag}
 * 类（DESIGN.md 9.4 技术风险的预判成立），因此 {@link #of(Tag)} 永不返回它；
 * 对应的 {@code long[]} 写入映射降级为抛 {@code NbtTypeMismatchException}。
 * 若未来平台补齐该类型，仅需在此枚举挂上 tagClass 并放开降级分支。
 */
public enum NbtValueType {

    END("end", EndTag.class, Tag.TAG_End),
    BYTE("byte", ByteTag.class, Tag.TAG_Byte),
    SHORT("short", ShortTag.class, Tag.TAG_Short),
    INT("int", IntTag.class, Tag.TAG_Int),
    LONG("long", LongTag.class, Tag.TAG_Long),
    FLOAT("float", FloatTag.class, Tag.TAG_Float),
    DOUBLE("double", DoubleTag.class, Tag.TAG_Double),
    BYTE_ARRAY("byte[]", ByteArrayTag.class, Tag.TAG_Byte_Array),
    STRING("string", StringTag.class, Tag.TAG_String),
    LIST("list", ListTag.class, Tag.TAG_List),
    COMPOUND("compound", CompoundTag.class, Tag.TAG_Compound),
    INT_ARRAY("int[]", IntArrayTag.class, Tag.TAG_Int_Array),

    /**
     * 预留占位：Nukkit MOT 无 {@code LongArrayTag}（见类 javadoc）。
     * NBT 规范中 long[] 的 typeId 为 12。
     */
    LONG_ARRAY("long[]", null, (byte) 12);

    /** 中文可读名（异常消息用）。 */
    private final String displayName;
    /** 对应 Tag 类；{@link #LONG_ARRAY} 为 null（平台缺失）。 */
    private final Class<? extends Tag> tagClass;
    /** NBT 二进制 typeId。 */
    private final byte id;

    NbtValueType(String displayName, Class<? extends Tag> tagClass, byte id) {
        this.displayName = displayName;
        this.tagClass = tagClass;
        this.id = id;
    }

    public String displayName() {
        return displayName;
    }

    public byte id() {
        return id;
    }

    /** 对应 Tag 类；平台缺失的类型（LONG_ARRAY）返回 null。 */
    public Class<? extends Tag> tagClass() {
        return tagClass;
    }

    /** 是否为数值类型（byte/short/int/long/float/double）。 */
    public boolean isNumeric() {
        return this == BYTE || this == SHORT || this == INT || this == LONG || this == FLOAT || this == DOUBLE;
    }

    /** 由 Tag 实例判定类型（LONG_ARRAY 永不返回，见类 javadoc）。 */
    public static NbtValueType of(Tag tag) {
        if (tag instanceof CompoundTag) return COMPOUND;
        if (tag instanceof ListTag) return LIST;
        if (tag instanceof StringTag) return STRING;
        if (tag instanceof IntTag) return INT;
        if (tag instanceof DoubleTag) return DOUBLE;
        if (tag instanceof ByteTag) return BYTE;
        if (tag instanceof ShortTag) return SHORT;
        if (tag instanceof LongTag) return LONG;
        if (tag instanceof FloatTag) return FLOAT;
        if (tag instanceof ByteArrayTag) return BYTE_ARRAY;
        if (tag instanceof IntArrayTag) return INT_ARRAY;
        if (tag instanceof EndTag) return END;
        throw new IllegalArgumentException("未知的 NBT Tag 类型: " + tag.getClass().getName());
    }

    /** 由 NBT 二进制 typeId 判定类型。 */
    public static NbtValueType of(byte id) {
        for (NbtValueType t : values()) {
            if (t.id == id) return t;
        }
        throw new IllegalArgumentException("未知的 NBT typeId: " + id);
    }
}
