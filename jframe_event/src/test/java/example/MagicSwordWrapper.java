package example;

import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.event.annotation.EventHandler;
import io.github.JiangHu.jframe.event.annotation.EventRoute;
import io.github.JiangHu.jframe.event.annotation.InstanceProvider;
import io.github.JiangHu.jframe.event.annotation.KeyExtractor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 示例 3：NBT 魔法剑 Wrapper —— 自定义 @KeyExtractor + @InstanceProvider 工厂。
 * <p>
 * 场景：一把带有特定 NBT 标签的魔法剑，当玩家手持它右键方块时触发特殊效果。
 * <p>
 * <b>核心难点</b>：需要按 {@code Item}（手中的剑）路由，而非按玩家路由——
 * 只有拿着这把特定剑的右键才触发。
 * <p>
 * <b>新设计解决方案</b>：
 * <ul>
 *   <li>{@link KeyExtractor} — static 方法，从 PlayerInteractEvent 中提取 Item（按物品路由）</li>
 *   <li>{@link InstanceProvider} — static 工厂方法，根据 Item 查找已注册的魔法剑 Wrapper</li>
 * </ul>
 *
 * <h3>注册方式</h3>
 * <pre>{@code
 * // 1. 启动时注册类（只需一次）
 * eventService.register(MagicSwordWrapper.class);
 *
 * // 2. 创建魔法剑时，将其注册到静态表
 * Item magicSword = Item.get(Item.DIAMOND_SWORD);
 * magicSword.getNamedTag().putString("magic_type", "thunder");
 * MagicSwordWrapper.registerSword(magicSword);
 * }</pre>
 *
 * <h3>路由流程</h3>
 * <pre>
 * 玩家右键方块 → PlayerInteractEvent 触发
 *     ↓
 * ① @KeyExtractor: extractItem(event) → 得到手中的 Item
 *     ↓
 * ② @InstanceProvider: findByItem(item) → 查找已注册的魔法剑
 *     ↓ 匹配（item 在 SWORDS 表中）
 * ③ filter 检查：是否是 RIGHT_CLICK_BLOCK？
 *     ↓ 通过
 * ④ 调用 onUse() → 触发雷电/治疗/爆炸效果
 * </pre>
 *
 * @see KeyExtractor
 * @see InstanceProvider
 */
public class MagicSwordWrapper {

    /** 所有已注册的魔法剑：Item → Wrapper（线程安全） */
    private static final Map<Item, MagicSwordWrapper> SWORDS = new ConcurrentHashMap<>();

    private final Item sword;

    public MagicSwordWrapper(Item sword) {
        this.sword = sword;
    }

    /**
     * 将一把魔法剑注册到静态表，使其能被 {@link #findByItem} 查找到。
     *
     * @param sword 带有 NBT 标签的剑
     * @return 创建的 Wrapper 实例
     */
    public static MagicSwordWrapper registerSword(Item sword) {
        MagicSwordWrapper wrapper = new MagicSwordWrapper(sword);
        SWORDS.put(sword, wrapper);
        return wrapper;
    }

    /**
     * 注销一把魔法剑。
     */
    public static void unregisterSword(Item sword) {
        SWORDS.remove(sword);
    }

    // ==================== 身份提取 + 实例工厂 ====================

    /**
     * 自定义提取器：从 PlayerInteractEvent 中提取 Item（手中的物品）。
     * <p>
     * <b>必须是 static</b>：提取时实例尚未创建，无法调用实例方法。
     * 按 Item 路由（而非按玩家路由）。
     *
     * @param event 玩家交互事件
     * @return 手中的 Item，或 null（手中无物品）
     */
    @EventRoute(filter = "isMagicSword")
    @KeyExtractor
    public static Item extractItem(PlayerInteractEvent event) {
        return event.getItem();
    }

    public static boolean isMagicSword(PlayerInteractEvent event) {
        Item item = event.getItem();
        return item != null && item.getNamedTag().contains("magic_type");
    }

    /**
     * 实例工厂：根据 Item 查找已注册的魔法剑 Wrapper。
     * <p>
     * <b>必须是 static</b>：工厂在实例创建之前调用。
     * 框架将 {@link #extractItem} 返回的 Item 传给此方法，
     * 如果该 Item 是已注册的魔法剑，返回对应的 Wrapper；否则返回 null（不路由）。
     *
     * @param item 从事件中提取的 Item
     * @return 匹配的 Wrapper，或 null
     */
    @InstanceProvider
    public static MagicSwordWrapper findByItem(Item item) {
        if (item == null) return null;
        return SWORDS.get(item);
    }

    // ==================== 事件处理方法 ====================

    /**
     * 当玩家手持这把魔法剑右键方块时触发。
     * <p>
     * 由于使用了自定义 @KeyExtractor 按 Item 路由，<b>只有</b>手持这把特定剑的
     * {@code PlayerInteractEvent} 才会到达这里。其他剑或其他玩家的右键不会触发。
     * <p>
     * filter 进一步过滤：只处理右键方块的动作（排除左键、物理踩踏等）。
     */
    @EventHandler
    @EventRoute(filter = "isRightClickBlock")
    public void onUse(PlayerInteractEvent event) {
        String magicType = sword.getNamedTag().getString("magic_type");
        System.out.println("[魔法剑] 触发 '" + magicType + "' 效果！"
                + " 玩家: " + event.getPlayer().getName());

        switch (magicType) {
            case "thunder" -> castThunder(event);
            case "heal" -> castHeal(event);
            case "explosion" -> castExplosion(event);
            default -> System.out.println("[魔法剑] 未知魔法类型: " + magicType);
        }
    }

    /**
     * 筛选方法：只处理右键点击方块的动作。
     */
    private boolean isRightClickBlock(PlayerInteractEvent event) {
        return event.getAction().name().equals("RIGHT_CLICK_BLOCK");
    }

    // ==================== 魔法效果（示意） ====================

    private void castThunder(PlayerInteractEvent event) {
        System.out.println("[魔法剑] 召唤闪电 → " + event.getBlock().getLocation());
    }

    private void castHeal(PlayerInteractEvent event) {
        event.getPlayer().heal(10);
        System.out.println("[魔法剑] 治疗 → " + event.getPlayer().getName());
    }

    private void castExplosion(PlayerInteractEvent event) {
        System.out.println("[魔法剑] 爆炸 → " + event.getBlock().getLocation());
    }
}
