package io.github.JiangHu.jframe.logger;

import io.github.JiangHu.jframe.core.module.ModuleManager;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class LoggerModuleTest {

    @Test
    void moduleLoadsLazilyAndRegistersApi() {
        ModuleManager manager = new ModuleManager(null);
        manager.register(new LoggerModule());

        LoggerAPI api = manager.get(LoggerAPI.class);
        assertSame(api, manager.get(LoggerAPI.class));

        api.getLogger("DemoPlugin").info("hello from module test");
        assertEquals(Set.of("DemoPlugin"), Set.copyOf(api.sources()));
        assertEquals("server-local", api.ownServerId());
        assertEquals(18080, api.defaultWebPort());
        assertFalse(api.isWebRunning());

        manager.shutdown();
    }
}
