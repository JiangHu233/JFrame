package io.github.JiangHu.jframe.event.test.fixture;

import cn.nukkit.event.Event;

/**
 * 测试用事件集合（3 个轻量级 {@link Event} 子类）。
 * <p>
 * 不依赖 Nukkit 玩家 / 物品等重型对象，仅用 {@code String} 身份标识和简单数值字段，
 * 便于在纯 JVM 单元测试中构造与断言。SpEL 条件和 filter 方法可直接访问其 getter。
 */
public final class TestEvents {

    private TestEvents() {
    }

    /** 移动事件：用于基础路由、优先级、独占、默认缓存测试。 */
    public static class MoveEvent extends Event {
        private final String playerName;
        private final int x;
        private final int y;

        public MoveEvent(String playerName, int x, int y) {
            this.playerName = playerName;
            this.x = x;
            this.y = y;
        }

        public String getPlayerName() {
            return playerName;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }
    }

    /** 聊天事件：用于 SpEL condition 和 filter 方法测试。 */
    public static class ChatEvent extends Event {
        private final String playerName;
        private final String message;

        public ChatEvent(String playerName, String message) {
            this.playerName = playerName;
            this.message = message;
        }

        public String getPlayerName() {
            return playerName;
        }

        public String getMessage() {
            return message;
        }
    }

    /** 战斗事件：用于多槽位 @KeyExtractor（攻击者 / 受害者，OR 语义）测试。 */
    public static class CombatEvent extends Event {
        private final String attacker;
        private final String victim;
        private final int damage;

        public CombatEvent(String attacker, String victim, int damage) {
            this.attacker = attacker;
            this.victim = victim;
            this.damage = damage;
        }

        public String getAttacker() {
            return attacker;
        }

        public String getVictim() {
            return victim;
        }

        public int getDamage() {
            return damage;
        }
    }
}
