package io.github.JiangHu.jframe.floatingtext;

import cn.nukkit.level.Position;
import io.github.JiangHu.jframe.floatingtext.animation.Animations;
import io.github.JiangHu.jframe.floatingtext.viewers.Viewers;

/**
 * 命名常驻组（多行全息薄层）— 行文本 join {@code \n} 交给<b>一个</b>常驻浮空字
 * （Bedrock 名牌支持换行，多行成本最低；真机失效的退化路径为每行一实体，P2）。
 *
 * <p>组不引入新渲染概念：line = PERSISTENT + none 动画的普通 {@link FloatingText}，
 * tag = {@code group:组名}（removeByTag 可批量）。重复命名 = 拆旧建新。
 *
 * <p>用法：
 * <pre>{@code
 * FloatingTextGroup g = ft.group("lobby:rules", pos).lines("§b=== 主城 ===", "§7规则").build();
 * g.setTexts("§b=== 主城 ===", "§7新内容");   // 整组刷新
 * ft.remove("lobby:rules");                    // 移除
 * }</pre>
 */
public final class FloatingTextGroup {

    private final FloatingTextManager manager;
    private final String name;
    private final FloatingText line;

    private FloatingTextGroup(FloatingTextManager manager, String name, FloatingText line) {
        this.manager = manager;
        this.name = name;
        this.line = line;
    }

    /**
     * 组名。
     *
     * @return 名字
     */
    public String name() {
        return name;
    }

    /**
     * 组的浮空字句柄（单实体，\n 多行）。
     *
     * @return 句柄
     */
    public FloatingText text() {
        return line;
    }

    /**
     * 整组更新文本（幂等重渲）。
     *
     * @param lines 行文本
     */
    public void setTexts(String... lines) {
        if (lines == null || lines.length == 0 || !line.valid()) {
            return;
        }
        line.setText(String.join("\n", lines));
    }

    /**
     * 移除整组（幂等）。
     */
    public void remove() {
        manager.removeGroup(name);
    }

    /**
     * 仅移除组的行（管理器内部用——组名已出册，直接摘行防递归）。
     */
    void detach() {
        line.remove();
    }

    /**
     * 组 Builder。
     */
    public static final class Builder {
        private final FloatingTextManager manager;
        private final String name;
        private final Position position;
        private String[] lines;

        Builder(FloatingTextManager manager, String name, Position position) {
            this.manager = manager;
            this.name = name;
            this.position = position;
        }

        /**
         * 行文本（按序 join \n）。
         *
         * @param lines 行
         * @return self
         */
        public Builder lines(String... lines) {
            this.lines = lines;
            return this;
        }

        /**
         * 构建并注册组（重名拆旧建新）。
         *
         * @return 组
         */
        public FloatingTextGroup build() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("组名不得为空");
            }
            if (lines == null || lines.length == 0) {
                throw new IllegalArgumentException("组至少一行文本: " + name);
            }
            if (lines.length > manager.maxGroupLines()) {
                throw new IllegalArgumentException("组行数超上限（" + manager.maxGroupLines() + "）: " + name);
            }
            FloatingText line = manager.show(FloatingText.builder()
                    .text(String.join("\n", lines))
                    .position(position)
                    .lifetimeTicks(0)                    // 常驻
                    .animation(Animations.none())        // 静止
                    .viewers(Viewers.all())
                    .tag("group:" + name)
                    .build());
            if (line == null) {
                throw new IllegalStateException("组创建失败（渲染注册被拒）: " + name);
            }
            return manager.registerGroup(name, new FloatingTextGroup(manager, name, line));
        }
    }
}
