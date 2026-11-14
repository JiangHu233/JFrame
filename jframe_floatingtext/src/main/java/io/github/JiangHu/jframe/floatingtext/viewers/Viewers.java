package io.github.JiangHu.jframe.floatingtext.viewers;

import cn.nukkit.Player;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 观众策略 — 判定某玩家（UUID + 所在 level）是否可见。
 *
 * <h3>语义</h3>
 * <ul>
 *   <li>{@link #all()}：策略而非快照——补发/对账时动态评估当前在线全体；</li>
 *   <li>{@link #of(UUID...)}：UUID 快照（玩家离线再上线仍匹配）；</li>
 *   <li>level 过滤由管理器在对账时叠加（同 level 才可见），本接口只管人群。</li>
 * </ul>
 *
 * <p>自定义策略（按队伍可见等）直接实现本接口，经
 * {@code FloatingText.builder().viewers(...)} 传入。
 */
@FunctionalInterface
public interface Viewers {

    /**
     * 是否匹配该玩家。
     *
     * @param playerId  玩家 UUID
     * @param levelName 玩家当前 level 名（null = 未知）
     * @return 可见 true
     */
    boolean matches(UUID playerId, String levelName);

    /**
     * 全体可见。
     *
     * @return 策略
     */
    static Viewers all() {
        return (playerId, levelName) -> true;
    }

    /**
     * 指定 UUID 集合可见。
     *
     * @param ids 玩家 UUID
     * @return 策略
     */
    static Viewers of(UUID... ids) {
        Set<UUID> set = Set.of(ids);
        return (playerId, levelName) -> set.contains(playerId);
    }

    /**
     * 指定玩家集合可见（转 UUID 快照匹配）。
     *
     * @param players 玩家
     * @return 策略
     */
    static Viewers of(Player... players) {
        return of(Arrays.stream(players)
                .map(Player::getUniqueId)
                .toArray(UUID[]::new));
    }

    /**
     * 便捷：单玩家。
     *
     * @param player 玩家
     * @return 策略
     */
    static Viewers of(Player player) {
        return of(player.getUniqueId());
    }

    /**
     * 便捷：单 UUID。
     *
     * @param id 玩家 UUID
     * @return 策略
     */
    static Viewers of(UUID id) {
        return (playerId, levelName) -> playerId.equals(id);
    }

    /**
     * 排除法：给定集合之外可见。
     *
     * @param ids 玩家 UUID
     * @return 策略
     */
    static Viewers except(UUID... ids) {
        Set<UUID> set = Arrays.stream(ids).collect(Collectors.toSet());
        return (playerId, levelName) -> !set.contains(playerId);
    }
}
