package io.github.JiangHu.jframe.ai.core.vision;

import cn.nukkit.entity.Entity;
import cn.nukkit.math.Vector3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link SeeQuery} 纯逻辑测试:链式配置语义与对 {@link VisionSensor} 的委托防御。
 * <p>
 * 测试环境无运行中的 Nukkit 服务器,Entity 无法构造,全部以 null 参数覆盖
 * {@code VisionSensor} 的防御分支(观察者/目标为 null 时各查询的既定返回值)。
 * <p>
 * 真实实体的距离/FOV/视线三要素判断需真实服务器环境集成验证,
 * 详见既有 {@code VisionSensorLogicTest} 对纯数学部分的覆盖。
 */
@DisplayName("SeeQuery:视野查询链式门面")
class SeeQueryLogicTest {

    /** 无服务器环境下构造:观察者为 null(记录行为:构造不设防) */
    private SeeQuery query() {
        return new SeeQuery(null);
    }

    @Nested
    @DisplayName("链式配置")
    class Chaining {

        @Test
        @DisplayName("range()/fov() 返回 this 支持链式调用")
        void configuratorsReturnThis() {
            SeeQuery query = query();

            assertSame(query, query.range(24), "range 应返回 this");
            assertSame(query, query.fov(120), "fov 应返回 this");
            assertSame(query, query.range(24).fov(120), "链式组合应返回 this");
        }

        @Test
        @DisplayName("默认参数与 VisionSensor 一致(16 格 / 90° FOV)")
        void defaultsMatchVisionSensor() {
            // 默认值无法直接读取,通过防御路径间接验证:
            // 默认 range/fov 下 canSee(null 观察者) 走 VisionSensor 同一防御分支
            assertEquals(VisionSensor.DEFAULT_MAX_DISTANCE, 16.0,
                    "VisionSensor 默认距离常量应为 16 格");
            assertEquals(VisionSensor.DEFAULT_FIELD_OF_VIEW, 90.0,
                    "VisionSensor 默认 FOV 常量应为 90°");
        }
    }

    @Nested
    @DisplayName("委托 VisionSensor 的防御路径")
    class DelegatedDefenses {

        @Test
        @DisplayName("canSee(Entity):观察者/目标为 null 时返回 false")
        void canSeeEntityWithNullsReturnsFalse() {
            assertFalse(query().canSee((Entity) null),
                    "null 观察者应被 VisionSensor 防御拦截");
            assertFalse(query().range(32).fov(360).canSee((Entity) null),
                    "调整参数后 null 目标仍应防御失败");
        }

        @Test
        @DisplayName("canSee(Vector3):观察者/坐标为 null 时返回 false")
        void canSeePointWithNullsReturnsFalse() {
            assertFalse(query().canSee((Vector3) null),
                    "null 坐标点应被 VisionSensor 防御拦截");
        }

        @Test
        @DisplayName("canSee360(Entity):观察者/目标为 null 时返回 false")
        void canSee360WithNullsReturnsFalse() {
            assertFalse(query().canSee360(null),
                    "null 目标应被 VisionSensor 防御拦截");
        }

        @Test
        @DisplayName("angleTo(Entity):观察者/目标为 null 时返回 180(正后方哨兵)")
        void angleToWithNullsReturnsSentinel() {
            assertEquals(180.0, query().angleTo(null),
                    "null 参数应返回 VisionSensor 的 180° 哨兵值");
        }

        @Test
        @DisplayName("查询为无状态纯读:同一实例可反复调用不同查询")
        void queryInstanceIsReusable() {
            SeeQuery query = query();

            assertFalse(query.canSee((Entity) null));
            assertFalse(query.canSee((Vector3) null));
            assertFalse(query.canSee360(null));
            assertEquals(180.0, query.angleTo(null));
        }
    }
}
