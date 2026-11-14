package io.github.JiangHu.jframe.logger;

import cn.nukkit.Server;
import cn.nukkit.lang.CommandOutputContainer;
import cn.nukkit.lang.TextContainer;
import cn.nukkit.permission.Permission;
import cn.nukkit.permission.PermissionAttachment;
import cn.nukkit.permission.PermissionAttachmentInfo;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.command.CommandSender;
import io.github.JiangHu.jframe.logger.core.LogStore;
import io.github.JiangHu.jframe.logger.web.WebRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JLogCommandTest {

    static class StubSender implements CommandSender {
        final List<String> messages = new ArrayList<>();

        String joined() {
            return String.join("\n", messages);
        }

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
        }

        @Override
        public Server getServer() {
            return null;
        }

        @Override
        public String getName() {
            return "Stub";
        }

        @Override
        public boolean isPlayer() {
            return false;
        }

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
            return true;
        }

        @Override
        public boolean hasPermission(Permission permission) {
            return true;
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin) {
            return null;
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, String name) {
            return null;
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, String name, Boolean value) {
            return null;
        }

        @Override
        public void removeAttachment(PermissionAttachment attachment) {
        }

        @Override
        public void recalculatePermissions() {
        }

        @Override
        public Map<String, PermissionAttachmentInfo> getEffectivePermissions() {
            return Map.of();
        }

        @Override
        public boolean isOp() {
            return true;
        }

        @Override
        public void setOp(boolean value) {
        }
    }

    private final List<LoggerAPI> apis = new ArrayList<>();

    @AfterEach
    void tearDown() {
        apis.forEach(api -> {
            try {
                api.shutdownInternal();
            } catch (Throwable ignored) {
            }
        });
    }

    private LoggerAPI newApi(int configPort) {
        LoggerConfig config = new LoggerConfig(5000, 20000, false, "127.0.0.1", configPort, "srv-test", "", null);
        Supplier<String> id = () -> "srv-test";
        LoggerAPI api = new LoggerAPI(new LogStore(config.perSourceLimit(), config.globalLimit()), config, null, id);
        apis.add(api);
        return api;
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void noArgsShowsHelp() {
        LoggerAPI api = newApi(18080);
        StubSender sender = new StubSender();
        JLogCommand.execute(api, sender, new String[0]);
        assertTrue(sender.joined().contains("/jlog start"));
        assertTrue(sender.joined().contains("/jlog status"));
    }

    @Test
    void bareStartUsesConfigPortAndStopCycle() throws Exception {
        int port = freePort();
        LoggerAPI api = newApi(port);
        StubSender sender = new StubSender();

        JLogCommand.execute(api, sender, new String[]{"status"});
        assertTrue(sender.joined().contains("未运行"));

        JLogCommand.execute(api, sender, new String[]{"start"});
        assertTrue(sender.joined().contains("HOST"), sender.joined());
        assertTrue(api.isWebRunning());
        assertEquals(WebRole.HOST, api.webRole());
        assertEquals(port, api.webPort());

        JLogCommand.execute(api, sender, new String[]{"start"});
        assertTrue(sender.joined().contains("已在运行"), sender.joined());

        JLogCommand.execute(api, sender, new String[]{"stop"});
        assertTrue(sender.messages.get(sender.messages.size() - 1).contains("已停止"));
        assertFalse(api.isWebRunning());
        assertEquals(-1, api.webPort());

        JLogCommand.execute(api, sender, new String[]{"stop"});
        assertTrue(sender.messages.get(sender.messages.size() - 1).contains("未在运行"));
    }

    @Test
    void autoStartAssignsUnusedPort() throws Exception {
        LoggerAPI api = newApi(18080);
        StubSender sender = new StubSender();
        JLogCommand.execute(api, sender, new String[]{"start", "auto"});
        assertTrue(sender.joined().contains("HOST"), sender.joined());
        assertTrue(api.webPort() > 0);
    }

    @Test
    void invalidPortArgumentsShowUsage() {
        LoggerAPI api = newApi(18080);
        StubSender sender = new StubSender();
        JLogCommand.execute(api, sender, new String[]{"start", "abc"});
        assertTrue(sender.joined().contains("端口参数非法"));
        JLogCommand.execute(api, sender, new String[]{"start", "0"});
        assertTrue(sender.joined().contains("1~65535"));
        JLogCommand.execute(api, sender, new String[]{"start", "70000"});
        assertTrue(sender.joined().contains("1~65535"));
        assertFalse(api.isWebRunning());
    }

    @Test
    void occupiedPortFailsWithAutoHint() throws Exception {
        int port = freePort();
        try (ServerSocket blocker = new ServerSocket(port)) {
            LoggerAPI api = newApi(port);
            StubSender sender = new StubSender();
            JLogCommand.execute(api, sender, new String[]{"start"});
            assertTrue(sender.joined().contains("非 JFrame logger"), sender.joined());
            assertTrue(sender.joined().contains("auto"));
            assertFalse(api.isWebRunning());
        }
    }
}
