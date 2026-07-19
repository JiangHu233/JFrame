package io.github.JiangHu.jframe.command.test;

import cn.nukkit.Server;
import cn.nukkit.command.CommandSender;
import cn.nukkit.lang.CommandOutputContainer;
import cn.nukkit.lang.TextContainer;
import cn.nukkit.permission.Permission;
import cn.nukkit.permission.PermissionAttachment;
import cn.nukkit.permission.PermissionAttachmentInfo;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.permission.PermissionAttachment;
import cn.nukkit.permission.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 测试专用的 {@link CommandSender} 桩实现（test double）。
 * <p>
 * <b>不依赖运行中的 Nukkit {@link Server}</b>，可在普通 JVM 单元测试中直接使用。
 * 收集所有 {@link #sendMessage(String)} 输出，并允许配置 {@link #hasPermission(String)} 的返回值，
 * 用于断言命令处理结果与权限校验行为。
 *
 * <p>接口中与权限附件相关的方法均返回空值/抛出 {@link UnsupportedOperationException}，
 * 因为命令路由测试不涉及动态权限附件。
 */
public class FakeCommandSender implements CommandSender {

    /** 发送者名称 */
    private final String name;

    /** 是否拥有全部权限（true 时 hasPermission 恒为 true） */
    private boolean op;

    /** 收到的全部消息 */
    private final List<String> messages = new ArrayList<>();

    public FakeCommandSender(String name) {
        this(name, true);
    }

    public FakeCommandSender(String name, boolean op) {
        this.name = name;
        this.op = op;
    }

    /** 返回收到的消息列表（可断言） */
    public List<String> getMessages() {
        return messages;
    }

    /** 返回最后一条消息 */
    public String lastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public void setOp(boolean op) {
        this.op = op;
    }

    // ========== CommandSender ==========

    @Override
    public void sendMessage(String message) {
        messages.add(message);
    }

    @Override
    public void sendMessage(TextContainer message) {
        messages.add(message.getText());
    }

    @Override
    public void sendCommandOutput(CommandOutputContainer container) {
        // 命令路由测试不使用协议级输出容器
    }

    @Override
    public Server getServer() {
        return null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isPlayer() {
        return false;
    }

    // ========== Permissible ==========

    @Override
    public boolean isPermissionSet(String name) {
        return false;
    }

    @Override
    public boolean isPermissionSet(Permission permission) {
        return false;
    }

    @Override
    public boolean hasPermission(String name) {
        return op;
    }

    @Override
    public boolean hasPermission(Permission permission) {
        return op;
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, Boolean value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void recalculatePermissions() {
        // no-op
    }

    @Override
    public Map<String, PermissionAttachmentInfo> getEffectivePermissions() {
        return Collections.emptyMap();
    }

    // ========== ServerOperator ==========

    @Override
    public boolean isOp() {
        return op;
    }
}
