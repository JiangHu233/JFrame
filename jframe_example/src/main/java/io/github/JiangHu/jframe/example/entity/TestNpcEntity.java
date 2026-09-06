package io.github.JiangHu.jframe.example.entity;

import cn.nukkit.Player;
import cn.nukkit.entity.EntityHuman;
import cn.nukkit.entity.data.Skin;
import cn.nukkit.level.Position;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.DoubleTag;
import cn.nukkit.nbt.tag.FloatTag;
import cn.nukkit.nbt.tag.ListTag;

/**
 * AI 测试专用 NPC 实体：继承 {@link EntityHuman}，<b>不带任何怪物默认 AI</b>。
 *
 * <h3>为什么不用原生 Zombie/Skeleton？</h3>
 * <p>
 * 原版怪物（{@code EntityZombie} 等）自带完整的 AI 行为树，每 tick 会在自身的
 * {@code onUpdate} 中重新决策并覆盖外部写入的 {@code motionX/Y/Z}。
 * 这导致 {@link io.github.JiangHu.jframe.ai.navigation.Navigator} 设置的速度向量被瞬间抹掉，
 * 表现为「实体完全不动」或「只按怪物自身 AI 行动，寻路/追逐效果不可见」。
 * <p>
 * {@link EntityHuman} 是玩家型实体，<b>没有怪物 AI 行为树</b>，外部设置的 motion 不会被覆盖；
 * 配合 {@link io.github.JiangHu.jframe.ai.navigation.Navigator} 在设置 motion 后主动调用
 * {@code move()}（参考 RsNPC 的 {@code processMove}），即可稳定驱动 NPC 沿路径行走。
 *
 * <h3>参考实现</h3>
 * 本实体的移动驱动方式参考了 RsNPC 插件（{@code com.smallaswater.npc.entitys.EntityRsNPC}）：
 * 其 NPC 同样继承 {@link EntityHuman}，在 {@code processMove} 中设置 {@code motionX/Y/Z} 后立即调用
 * {@code this.move(motionX, motionY, motionZ)} 完成实际位移。
 *
 * <h3>注册</h3>
 * 需在插件启用时调用 {@code Entity.registerEntity(TestNpcEntity.NETWORK_NAME, TestNpcEntity.class)}，
 * 之后既可用 {@link #spawnAt(Position)} 直接构造，也可用 {@code Entity.createEntity(NETWORK_NAME, pos)} 生成。
 *
 * @see io.github.JiangHu.jframe.ai.navigation.Navigator
 */
public class TestNpcEntity extends EntityHuman {

    /** 实体注册名（供 {@code Entity.registerEntity} / {@code Entity.createEntity} 使用） */
    public static final String NETWORK_NAME = "TestNpc";

    public TestNpcEntity(FullChunk chunk, CompoundTag nbt) {
        super(chunk, nbt);
    }

    @Override
    public int getNetworkId() {
        // EntityHuman 使用玩家网络 ID（63），客户端按玩家模型渲染
        return Player.NETWORK_ID;
    }

    @Override
    public float getWidth() {
        return 0.6f;
    }

    @Override
    public float getLength() {
        return 0.6f;
    }

    @Override
    public float getHeight() {
        return 1.8f;
    }

    @Override
    protected float getBaseOffset() {
        return 0f;
    }

    /** 不随区块持久化，避免服务器重启后残留测试实体 */
    @Override
    public boolean canBeSavedWithChunk() {
        return false;
    }

    /**
     * 在指定位置生成一个测试 NPC（自动设置默认皮肤与名称）。
     *
     * @param pos 生成位置（含世界）
     * @return 已生成并对所有玩家可见的 NPC 实体
     */
    public static TestNpcEntity spawnAt(Position pos) {
        FullChunk chunk = pos.getLevel().getChunk((int) pos.x >> 4, (int) pos.z >> 4, true);
        CompoundTag nbt = baseNbt(pos);
        TestNpcEntity entity = new TestNpcEntity(chunk, nbt);
        entity.setSkin(defaultSkin());
        entity.setNameTag("TestNPC");
        entity.spawnToAll();
        return entity;
    }

    /** 构造实体生成所需的基础 NBT（Pos / Motion / Rotation）。 */
    private static CompoundTag baseNbt(Position pos) {
        return new CompoundTag()
                .putList(new ListTag<DoubleTag>("Pos")
                        .add(new DoubleTag("", pos.x))
                        .add(new DoubleTag("", pos.y))
                        .add(new DoubleTag("", pos.z)))
                .putList(new ListTag<DoubleTag>("Motion")
                        .add(new DoubleTag("", 0))
                        .add(new DoubleTag("", 0))
                        .add(new DoubleTag("", 0)))
                .putList(new ListTag<FloatTag>("Rotation")
                        .add(new FloatTag("", 0))
                        .add(new FloatTag("", 0)));
    }

    /**
     * 生成一个 64×64 的单色 RGBA 皮肤数据，保证客户端能正确渲染经典人形模型。
     * 使用 {@link Skin#GEOMETRY_CUSTOM_SLIM} 资源补丁（经典 Steve/Alex 模型）。
     */
    private static Skin defaultSkin() {
        byte[] data = new byte[64 * 64 * 4];
        for (int i = 0; i < data.length; i += 4) {
            data[i] = (byte) 0xC8;     // R
            data[i + 1] = (byte) 0xC8; // G
            data[i + 2] = (byte) 0xC8; // B
            data[i + 3] = (byte) 0xFF; // A
        }
        Skin skin = new Skin();
        skin.setSkinData(data);
        skin.setSkinId("TestNpcDefaultSkin");
        skin.setSkinResourcePatch(Skin.GEOMETRY_CUSTOM_SLIM);
        skin.setTrusted(true);
        return skin;
    }
}
