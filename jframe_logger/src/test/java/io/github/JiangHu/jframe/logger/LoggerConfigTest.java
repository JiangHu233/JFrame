package io.github.JiangHu.jframe.logger;

import cn.nukkit.utils.ConfigSection;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LoggerConfigTest {

    @Test
    void defaultsWhenSectionMissing() {
        LoggerConfig c = LoggerConfig.fromSection(null, null);
        assertEquals(5000, c.perSourceLimit());
        assertEquals(20000, c.globalLimit());
        assertFalse(c.persist());
        assertEquals("0.0.0.0", c.bind());
        assertEquals(18080, c.port());
        assertEquals("", c.serverIdOverride());
        assertEquals("", c.token());
    }

    @Test
    void parsesNestedValuesWithFallbacks() {
        ConfigSection logger = new ConfigSection();
        logger.put("buffer", Map.of("per-source", 100, "global", "999"));
        logger.put("persist", true);
        ConfigSection web = new ConfigSection();
        web.put("bind", "127.0.0.1");
        web.put("port", 19090);
        web.put("server-id", "panel-eu-1");
        web.put("token", "s3cret");
        logger.put("web", web);

        LoggerConfig c = LoggerConfig.fromSection(logger, null);
        assertEquals(100, c.perSourceLimit());
        assertEquals(999, c.globalLimit());
        assertEquals(true, c.persist());
        assertEquals("127.0.0.1", c.bind());
        assertEquals(19090, c.port());
        assertEquals("panel-eu-1", c.serverIdOverride());
        assertEquals("s3cret", c.token());
    }

    @Test
    void malformedValuesFallBackToDefaults() {
        ConfigSection logger = new ConfigSection();
        logger.put("buffer", Map.of("per-source", "not-a-number"));
        LoggerConfig c = LoggerConfig.fromSection(logger, null);
        assertEquals(5000, c.perSourceLimit());
    }
}
