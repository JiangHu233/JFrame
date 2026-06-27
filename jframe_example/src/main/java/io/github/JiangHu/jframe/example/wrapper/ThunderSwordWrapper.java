package io.github.JiangHu.jframe.example.wrapper;

import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.weather.EntityLightning;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Position;
import cn.nukkit.nbt.tag.CompoundTag;
import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;
import io.github.JiangHu.jframe.event.annotation.Wrapper;

/**
 * 示例 3：闪电钻石剑 Wrapper —— 包装「名为 aaa 的钻石剑」这把物品本身。
 * <p>
 * 本类演示框架的<b>物品级路由</b>模式（与 {@code ExampleItem} 一致）：
 * 被包装的对象是「这把剑」，而不是玩家。
 *
 * <h3>核心设计：record 身份标识 + 注解筛选</h3>
 * <ol>
 *   <li>定义一个 {@link SwordId record} 作为「这把剑」的身份标识。
 *       record 自带 {@code equals/hashCode}，可作为框架默认缓存的 key。</li>
 *   <li>{@link KeyExtractor} 从 {@link PlayerInteractEvent} 提取出 {@link SwordId}，
 *       并用 {@link EventRoute} 的 {@code filter} 注解<b>声明筛选条件</b>：
 *       「事件中的 item 名为 aaa 且为钻石剑」。</li>
 *   <li><b>无需 {@code @InstanceProvider}</b>：框架的 {@code constructViaConstructor}
 *       会用 {@link SwordId} 作为构造器参数自动创建实例并缓存。</li>
 *   <li>{@link EventHandler} 再用 {@code filter} 进一步筛选「右键方块」动作。</li>
 * </ol>
 *
 * <h3>路由流程</h3>
 * <pre>
 * 玩家右键方块 → PlayerInteractEvent 触发
 *     ↓
 * ① @KeyExtractor extract(event) → 提取 SwordId（包装手中的剑）
 *     ↓
 * ② 框架用 SwordId 作为 key 查找/创建 ThunderSwordWrapper 实例（默认缓存）
 *     ↓
 * ③ filter isThunderSwordClick(event) → 是 aaa 钻石剑 && 右键方块？
 *     ↓ 通过
 * ④ onThunderStrike(event) → 在方块上方召唤闪电
 * </pre>
 *
 * <h3>获取这把剑</h3>
 * 在游戏内执行 {@code /sword} 命令（见 {@code ExamplePlugin}）即可获得一把名为 "aaa" 的闪电钻石剑。
 * 拿在手上右键任意方块，即可在该方块上方召唤一道闪电。
 */
@Wrapper
public class ThunderSwordWrapper {

    /** NBT 标记键（createSword 额外写入，便于扩展识别）。 */
    public static final String NBT_KEY = "thunder_sword";

    /** 物品自定义显示名称 —— 筛选依据。 */
    public static final String SWORD_NAME = "aaa";

    /**
     * 身份标识：包装「手中的剑」。
     * <p>
     * 作为 record，自动生成基于 {@link Item} 的 {@code equals/hashCode}，
     * 因此内容相同的两把剑会路由到同一个 Wrapper 实例（框架默认缓存）。
     *
     * @param item 事件中提取出的物品
     */
    public record SwordId(Item item) {
    }

    // ==================== 身份提取（static，必需） ====================

    /**
     * 从 {@link PlayerInteractEvent} 中提取手中的剑，包装为 {@link SwordId}。
     * <p>
     * {@code @EventRoute(filter = "isThunderSword")} <b>声明</b>本提取器只对
     * 「名为 aaa 的钻石剑」感兴趣；实际筛选由 {@link #isThunderSword} 实现。
     * <p>
     * <b>必须是 static</b>：提取时实例尚未创建。
     *
     * @param event 玩家交互事件
     * @return 包装手中剑的 SwordId
     */
    @EventRoute(filter = "isThunderSword")
    @KeyExtractor
    public static SwordId extract(PlayerInteractEvent event) {
        return new SwordId(event.getItem());
    }

    // ==================== 筛选方法（static） ====================

    /**
     * 筛选：事件中的 item 是否是「名为 aaa 的钻石剑」。
     * <p>
     * 同时校验物品类型（钻石剑）与自定义名称（aaa），二者缺一不可。
     */
    public static boolean isThunderSword(PlayerInteractEvent event) {
        Item item = event.getItem();
        if (item == null || item.getId() != Item.DIAMOND_SWORD) {
            return false;
        }
        return SWORD_NAME.equals(item.getCustomName());
    }

    /**
     * 复合筛选：是 aaa 钻石剑 <b>且</b> 右键方块。
     * <p>
     * 供 {@link #onThunderStrike} 的 {@code filter} 使用，确保只有
     * 「手持 aaa 钻石剑右键方块」才触发闪电。
     */
    public static boolean isThunderSwordClick(PlayerInteractEvent event) {
        return isThunderSword(event)
                && "RIGHT_CLICK_BLOCK".equals(event.getAction().name());
    }

    // ==================== 物品工厂（static） ====================

    /**
     * 创建一把「名为 aaa 的闪电钻石剑」物品实例。
     * <p>
     * 设置自定义名称（aaa）+ NBT 标记。任何名为 aaa 的钻石剑都会被
     * {@link #isThunderSword} 识别（NBT 标记为可选的额外标识）。
     *
     * @return 名为 aaa 的钻石剑
     */
    public static Item createSword() {
        Item sword = Item.get(Item.DIAMOND_SWORD);
        sword.setCustomName(SWORD_NAME);
        CompoundTag tag = sword.getNamedTag();
        if (tag == null) {
            tag = new CompoundTag();
        }
        tag.putString(NBT_KEY, SWORD_NAME);
        sword.setNamedTag(tag);
        return sword;
    }

    // ==================== 实例字段 + 构造器 ====================

    /** 本实例绑定的剑身份（由框架通过构造器注入）。 */
    private final SwordId swordId;

    /** 该剑累计召唤闪电次数。 */
    private int strikeCount = 0;

    /**
     * 构造器：接收 {@link SwordId} 身份标识。
     * <p>
     * 框架在无 {@code @InstanceProvider} 时，通过 {@code constructViaConstructor}
     * 查找首个参数兼容 {@link SwordId} 的构造器，自动创建并缓存实例。
     *
     * @param swordId 剑的身份标识
     */
    public ThunderSwordWrapper(SwordId swordId) {
        this.swordId = swordId;
    }

    // ==================== 事件处理（实例方法） ====================

    /**
     * 手持 aaa 钻石剑右键方块时，在方块上方召唤一道闪电。
     * <p>
     * {@code filter = "isThunderSwordClick"} 确保只有「aaa 钻石剑 + 右键方块」才执行。
     */
    @EventRoute(filter = "isThunderSwordClick")
    @EventHandler
    public void onThunderStrike(PlayerInteractEvent event) {
        strikeCount++;
        summonLightning(event);
        event.getPlayer().sendMessage("§b⚡ 闪电钻石剑 §f[aaa] §b已召唤闪电！本剑累计释放 §e" + strikeCount + " §b次。");
    }

    // ==================== 闪电效果 ====================

    /**
     * 在被点击方块的正上方召唤一道闪电。
     *
     * @param event 玩家交互事件
     */
    private void summonLightning(PlayerInteractEvent event) {
        Block block = event.getBlock();
        if (block == null) {
            return;
        }
        // 在方块中心、上方一格召唤闪电
        Position pos = new Position(
                block.getX() + 0.5,
                block.getY() + 1,
                block.getZ() + 0.5,
                block.getLevel()
        );
        CompoundTag nbt = Entity.getDefaultNBT(pos);
        EntityLightning lightning = new EntityLightning(pos.getChunk(), nbt);
        lightning.spawnToAll();
    }

    // ==================== Getter ====================

    public SwordId getSwordId() {
        return swordId;
    }

    public int getStrikeCount() {
        return strikeCount;
    }
}
