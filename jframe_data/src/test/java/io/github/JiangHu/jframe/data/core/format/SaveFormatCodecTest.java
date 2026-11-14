package io.github.JiangHu.jframe.data.core.format;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.JiangHu.jframe.data.SaveFormat;
import io.github.JiangHu.jframe.data.core.engine.SaveValueBridge;
import io.github.JiangHu.jframe.data.exception.DataException;
import io.github.JiangHu.jframe.data.value.SaveValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SaveFormatCodec} 同级格式存取器测试。
 * <p>
 * 覆盖场景：JsonCodec / YamlCodec 的 write/read 往返一致性（主干货币为
 * Gson 树模型 {@link JsonElement}）、{@link SaveValueBridge} 双向转换
 * （字段边界组件）、{@link SaveFormat#getCodec()} 关联、两种格式对同一
 * 语义数据的等价还原、非法文本报错。
 */
@DisplayName("格式存取器（JsonCodec / YamlCodec）测试")
class SaveFormatCodecTest {

    /** 构建一棵覆盖全部节点类型的示例树 */
    private static JsonObject sampleTree() {
        return JsonParser.parseString("""
                {
                  "name": "Steve",
                  "level": 88,
                  "ratio": 3.5,
                  "vip": true,
                  "missing": null,
                  "tags": ["pvp", "builder"],
                  "pos": {"x": 10, "y": -20}
                }""").getAsJsonObject();
    }

    @Nested
    @DisplayName("SaveFormat 与 codec 关联")
    class FormatCodecBindingTest {

        @Test
        @DisplayName("每种格式关联正确的存取器实例")
        void codecBinding() {
            assertInstanceOf(JsonCodec.class, SaveFormat.JSON.getCodec());
            assertInstanceOf(YamlCodec.class, SaveFormat.YAML.getCodec());
        }

        @Test
        @DisplayName("扩展名与格式对应")
        void extensions() {
            assertEquals(".json", SaveFormat.JSON.getExtension());
            assertEquals(".yml", SaveFormat.YAML.getExtension());
        }
    }

    @Nested
    @DisplayName("JsonCodec")
    class JsonCodecTest {

        private final JsonCodec codec = new JsonCodec();

        @Test
        @DisplayName("write 输出 pretty JSON，read 还原等价树")
        void roundTrip() {
            JsonObject tree = sampleTree();

            String json = codec.write(tree);
            assertTrue(json.contains("\"name\""), "JSON 应包含键 name，实际:\n" + json);

            JsonElement restored = codec.read(json);
            assertEquals(tree, restored, "read(write(v)) 应与原树等价");
        }

        @Test
        @DisplayName("非法 JSON 文本报 DataException")
        void invalidJsonRejected() {
            assertThrows(DataException.class, () -> codec.read("{not valid json"));
        }
    }

    @Nested
    @DisplayName("YamlCodec")
    class YamlCodecTest {

        private final YamlCodec codec = new YamlCodec();

        @Test
        @DisplayName("write 输出块样式 YAML，read 还原等价树")
        void roundTrip() {
            JsonObject tree = sampleTree();

            String yaml = codec.write(tree);
            assertTrue(yaml.contains("name:"), "YAML 应包含键 name，实际:\n" + yaml);
            assertTrue(yaml.contains("- pvp"), "YAML 应包含列表项，实际:\n" + yaml);

            JsonElement restored = codec.read(yaml);
            assertEquals(tree, restored, "read(write(v)) 应与原树等价");
        }

        @Test
        @DisplayName("字符串 yes/no 往返一致：dump 自动加引号，读回仍是字符串")
        void yesNoStringRoundTrip() {
            // dump 字符串 "yes" 时 SnakeYAML 自动加引号（'yes'），避免与布尔歧义
            JsonObject tree = new JsonObject();
            tree.addProperty("flag", "yes");
            String yaml = codec.write(tree);
            JsonElement restored = codec.read(yaml);

            assertTrue(restored.getAsJsonObject().get("flag").isJsonPrimitive(),
                    "加引号的 'yes' 应还原为字符串");
            assertEquals("yes", restored.getAsJsonObject().get("flag").getAsString());
        }

        @Test
        @DisplayName("裸 yes/no 按 SnakeYAML 默认行为解析为布尔（与旧版一致）")
        void bareYesNoParsedAsBoolean() {
            // SnakeYAML 2.x 默认 resolver 沿用 YAML 1.1 隐式类型：裸 yes/no/on/off → 布尔。
            // 这与重构前的 YamlConverter 行为完全一致（手写配置常见约定）。
            JsonElement restored = codec.read("flag: yes\n");
            assertTrue(restored.getAsJsonObject().get("flag").getAsBoolean());
        }

        @Test
        @DisplayName("非法 YAML 文本报 DataException")
        void invalidYamlRejected() {
            assertThrows(DataException.class, () -> codec.read("a: [unclosed"));
        }
    }

    @Nested
    @DisplayName("双格式等价（同级存取器对称性）")
    class DualFormatEquivalenceTest {

        @Test
        @DisplayName("同一棵树经 JSON 与 YAML 往返后语义等价")
        void jsonAndYamlAreEquivalent() {
            JsonObject tree = sampleTree();

            JsonElement viaJson = SaveFormat.JSON.getCodec()
                    .read(SaveFormat.JSON.getCodec().write(tree));
            JsonElement viaYaml = SaveFormat.YAML.getCodec()
                    .read(SaveFormat.YAML.getCodec().write(tree));

            assertEquals(viaJson, viaYaml, "两种格式还原的树应等价");
            assertEquals(tree, viaJson);
            assertEquals(tree, viaYaml);
        }

        @Test
        @DisplayName("JSON 文本解析为树后可直接由 YamlCodec 输出（主干货币统一）")
        void jsonTextToYamlText() {
            String json = """
                    {"name": "Steve", "level": 88, "tags": ["a", "b"]}""";

            JsonElement tree = JsonParser.parseString(json);
            String yaml = SaveFormat.YAML.getCodec().write(tree);

            JsonElement back = SaveFormat.YAML.getCodec().read(yaml);
            assertEquals("Steve", back.getAsJsonObject().get("name").getAsString());
            assertEquals(88, back.getAsJsonObject().get("level").getAsInt());
            assertEquals(2, back.getAsJsonObject().get("tags").getAsJsonArray().size());
        }
    }

    @Nested
    @DisplayName("SaveValueBridge（字段边界组件）")
    class SaveValueBridgeTest {

        @Test
        @DisplayName("fromGson(null) 与 JsonNull 均返回 Null 节点")
        void nullHandling() {
            assertTrue(SaveValueBridge.fromGson(null).isNull());
            assertTrue(SaveValueBridge.fromGson(JsonNull.INSTANCE).isNull());
            assertTrue(SaveValueBridge.toGson(null).isJsonNull());
            assertTrue(SaveValueBridge.toGson(SaveValue.ofNull()).isJsonNull());
        }

        @Test
        @DisplayName("Gson LazilyParsedNumber 还原为标准数值类型")
        void lazilyParsedNumberNormalized() {
            // JsonParser 解析出的数字为 LazilyParsedNumber
            SaveValue value = SaveValueBridge.fromGson(JsonParser.parseString("42"));
            assertEquals(Integer.class, value.asNumber().getClass());

            SaveValue dbl = SaveValueBridge.fromGson(JsonParser.parseString("3.5"));
            assertEquals(Double.class, dbl.asNumber().getClass());
        }

        @Test
        @DisplayName("双向转换往返一致（toGson 后 fromGson 还原等价树）")
        void bridgeRoundTrip() {
            SaveValue.Map tree = SaveValue.map()
                    .put("name", SaveValue.of("Steve"))
                    .put("level", SaveValue.of(88))
                    .put("tags", SaveValue.list()
                            .add(SaveValue.of("pvp"))
                            .add(SaveValue.of("builder")));
            SaveValue restored = SaveValueBridge.fromGson(SaveValueBridge.toGson(tree));
            assertEquals(tree, restored);
        }
    }
}
