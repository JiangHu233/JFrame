package example;

import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;
import io.github.JiangHu.jframe.core.event.annotation.NukkitEvent;
import io.github.JiangHu.jframe.core.event.routing.ObjectEventRouter;
import io.github.JiangHu.jframe.core.event.routing.RoutingSpec;

/**
 * 示例 3：NBT 魔法剑 Wrapper（自定义 Key 提取器）。
 * <p>
 * 场景：一把带有特定 NBT 标签的魔法剑，当玩家手持它右键方块时触发特殊效果。
 * <p>
 * <b>核心难点</b>：{@code PlayerInteractEvent} 的全局提取器按 {@code Player} 路由，
 * 但我们需要按 {@code Item}（手中的剑）路由——只有拿着这把特定剑的右键才触发。
 * <p>
 * <b>解决方案</b>：使用 {@link RoutingSpec} 指定自定义提取器，
 * 将 {@code PlayerInteractEvent} 的 Key 从 {@code Player} 改为 {@code Item}。
 *
 * <h3>注册方式</h3>
 * <pre>{@code
 * Item magicSword = Item.get(Item.DIAMOND_SWORD);
 * magicSword.getNamedTag().putString("magic_type", "thunder");
 *
 * MagicSwordWrapper wrapper = MagicSwordWrapper.create(magicSword, router);
 * // 内部调用：router.register(wrapper, magicSword, spec)
 * //                              ^^^^^^^^^  Key = Item 对象
 * //                                          spec 将 PlayerInteractEvent 的提取器改为 getItem()
 * }</pre>
 *
 * <h3>路由流程</h3>
 * <pre>
 * 玩家右键方块 → PlayerInteractEvent 触发
 *     ↓
 * ObjectEventRouter.dispatch()
 *     ↓
 * ③ 自定义提取器：event.getItem() → 得到手中的 Item
 *     ↓
 * 比对 Key：手中的 Item == 注册时的 magicSword？
 *     ↓ 匹配
 * filter 检查：是否是 RIGHT_CLICK_BLOCK？
 *     ↓ 通过
 * 调用 onUse() → 触发雷电效果
 * </pre>
 *
 * @see RoutingSpec
 * @see ObjectEventRouter#register(Object, Object, RoutingSpec)
 */
public class MagicSwordWrapper {

    private final Item sword;
    private final ObjectEventRouter router;

    public MagicSwordWrapper(Item sword, ObjectEventRouter router) {
        this.sword = sword;
        this.router = router;
    }

    /**
     * 创建并注册魔法剑 Wrapper。
     * <p>
     * 使用 {@link RoutingSpec} 将 {@code PlayerInteractEvent} 的 Key 提取器
     * 从默认的 {@code getPlayer()} 改为 {@code getItem()}，
     * 这样只有手持这把特定剑的右键事件才会路由到此 Wrapper。
     *
     * @param sword  魔法剑 Item 对象（必须带有 NBT 标签）
     * @param router 事件路由器
     * @return 已注册的 Wrapper 实例
     */
    public static MagicSwordWrapper create(Item sword, ObjectEventRouter router) {
        MagicSwordWrapper wrapper = new MagicSwordWrapper(sword, router);

        // ★ 核心：自定义提取器，按 Item 路由而非 Player
        RoutingSpec spec = RoutingSpec.create()
                .extract(PlayerInteractEvent.class, PlayerInteractEvent::getItem);

        // Key = sword（Item 对象），提取器会从事件中提取 getItem() 并与此 Key 比对
        router.register(wrapper, sword, spec);
        return wrapper;
    }

    /**
     * 注销 Wrapper，停止监听。
     */
    public void destroy() {
        router.unregister(this);
    }

    // ==================== 事件处理方法 ====================

    /**
     * 当玩家手持这把魔法剑右键方块时触发。
     * <p>
     * 由于使用了自定义提取器按 Item 路由，<b>只有</b>手持这把特定剑的
     * {@code PlayerInteractEvent} 才会到达这里。其他剑或其他玩家的右键不会触发。
     * <p>
     * filter 进一步过滤：只处理右键方块的动作（排除左键、物理踩踏等）。
     */
    @NukkitEvent(filter = "isRightClickBlock")
    public void onUse(PlayerInteractEvent event) {
        String magicType = sword.getNamedTag().getString("magic_type");
        System.out.println("[魔法剑] 触发 '" + magicType + "' 效果！"
                + " 玩家: " + event.getPlayer().getName());

        // 根据剑的 NBT 标签施放不同效果
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
        // 在点击位置召唤闪电（示意）
        System.out.println("[魔法剑] 召唤闪电 → " + event.getBlock().getLocation());
    }

    private void castHeal(PlayerInteractEvent event) {
        // 治疗使用者（示意）
        event.getPlayer().heal(10);
        System.out.println("[魔法剑] 治疗 → " + event.getPlayer().getName());
    }

    private void castExplosion(PlayerInteractEvent event) {
        // 在点击位置制造爆炸（示意）
        System.out.println("[魔法剑] 爆炸 → " + event.getBlock().getLocation());
    }
}
