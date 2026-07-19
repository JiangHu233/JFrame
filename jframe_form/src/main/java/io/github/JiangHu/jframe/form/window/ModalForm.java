package io.github.JiangHu.jframe.form.window;

import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.window.FormWindowModal;
import io.github.JiangHu.jframe.form.FormView;
import io.github.JiangHu.jframe.form.response.FormResult;

import java.util.function.Consumer;

/**
 * 模态框（二选一确认）。
 * <p>
 * 对应 Nukkit {@link FormWindowModal}，仅含两个按钮，常用于「确认 / 取消」这类二元决策。
 * 可分别通过 {@link #onConfirm} / {@link #onCancel} 注册回调，或用 {@link #onClick}
 * 统一处理（参数为 {@code true} 表示点击了第一个按钮）。
 *
 * <pre>{@code
 * new ModalForm("确认传送", "是否传送到主城？")
 *     .buttons("§a确认", "§c取消")
 *     .onConfirm(ctx -> teleport(ctx.player()))
 *     .onCancel(ctx -> ctx.close());
 * }</pre>
 */
public class ModalForm extends JForm {

    private String content = "";
    private String button1 = "确认";
    private String button2 = "取消";

    private Consumer<Boolean> clickHandler;

    public ModalForm(String title) {
        super(title);
    }

    /**
     * 设置正文内容（链式）。
     *
     * @param content 正文
     * @return 当前表单
     */
    public ModalForm content(String content) {
        this.content = content;
        return this;
    }

    /**
     * 设置两个按钮的文本（链式）。
     *
     * @param button1 第一个按钮（通常为「确认」）
     * @param button2 第二个按钮（通常为「取消」）
     * @return 当前表单
     */
    public ModalForm buttons(String button1, String button2) {
        this.button1 = button1;
        this.button2 = button2;
        return this;
    }

    /**
     * 注册「点击第一个按钮」的回调（链式）。
     *
     * @param handler 回调
     * @return 当前表单
     */
    public ModalForm onConfirm(Runnable handler) {
        Consumer<Boolean> prev = this.clickHandler;
        this.clickHandler = clicked -> {
            if (prev != null) prev.accept(clicked);
            if (Boolean.TRUE.equals(clicked)) handler.run();
        };
        return this;
    }

    /**
     * 注册「点击第二个按钮」的回调（链式）。
     *
     * @param handler 回调
     * @return 当前表单
     */
    public ModalForm onCancel(Runnable handler) {
        Consumer<Boolean> prev = this.clickHandler;
        this.clickHandler = clicked -> {
            if (prev != null) prev.accept(clicked);
            if (Boolean.FALSE.equals(clicked)) handler.run();
        };
        return this;
    }

    /**
     * 注册统一点击回调（链式）。
     *
     * @param handler 回调，参数为 {@code true} 表示点击了第一个按钮
     * @return 当前表单
     */
    public ModalForm onClick(Consumer<Boolean> handler) {
        Consumer<Boolean> prev = this.clickHandler;
        this.clickHandler = prev == null ? handler : prev.andThen(handler);
        return this;
    }

    @Override
    public Type type() {
        return Type.MODAL;
    }

    @Override
    protected FormWindowModal buildWindow() {
        return new FormWindowModal(title, content, button1, button2);
    }

    @Override
    public FormResult buildResult(Player player) {
        if (window == null) {
            return FormResult.closed(player, Type.MODAL);
        }
        FormResponseModal resp = (FormResponseModal) window.getResponse();
        int id = resp.getClickedButtonId();
        // -1 通常表示关闭，视为未提交
        if (id < 0) {
            return FormResult.closed(player, Type.MODAL);
        }
        return FormResult.modal(player, id == 0);
    }

    @Override
    public void dispatch(FormView view, FormResult result) {
        if (result.wasClosed()) return;
        if (clickHandler != null) {
            clickHandler.accept(result.clickedButton1());
        }
    }
}
