package io.github.JiangHu.jframe.core.module;

import io.github.JiangHu.jframe.core.module.annotation.JBean;
import io.github.JiangHu.jframe.core.module.annotation.JFactoryClass;
import io.github.JiangHu.jframe.core.module.fixture.good.DependencyA;
import io.github.JiangHu.jframe.core.module.fixture.good.DependencyB;
import io.github.JiangHu.jframe.core.module.fixture.good.ScannedFactory;
import io.github.JiangHu.jframe.core.module.fixture.good.ScannedFactoryClass;
import io.github.JiangHu.jframe.core.module.fixture.good.ScannedPlain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModuleManager} 单元测试（无 Nukkit Server 环境，owner = null）。
 * <p>
 * 覆盖：四种注册形式的懒加载与单例语义、模块 A/B 引用场景、requires 递归、
 * shutdownOrder 排序、两实例注册表隔离、fail-fast 分支、注解扫描（形态 A/B）
 * 与坏 fixture 的注册期报错。
 */
class ModuleManagerTest {

    private static final String FIXTURE_GOOD = "io.github.JiangHu.jframe.core.module.fixture.good";
    private static final String FIXTURE_BAD = "io.github.JiangHu.jframe.core.module.fixture.bad";

    // ==================== 测试用类型 ====================

    static class Plain {
        static int created;

        Plain() {
            created++;
        }
    }

    static class WithArgs {
        WithArgs(String ignored) {
        }
    }

    static class Product {
        final String tag;

        Product(String tag) {
            this.tag = tag;
        }
    }

    static class ServiceA {
    }

    static class ServiceB {
        final ServiceA a;

        ServiceB(ServiceA a) {
            this.a = a;
        }
    }

    // ==================== 形式 1：无工厂直接 new ====================

    @Test
    void form1_lazyNewSingleton() {
        ModuleManager manager = new ModuleManager(null);
        Plain.created = 0;
        manager.register(Plain.class);
        assertEquals(0, Plain.created, "注册后不应实例化（懒加载）");

        Plain first = manager.get(Plain.class);
        Plain second = manager.get(Plain.class);
        assertEquals(1, Plain.created);
        assertSame(first, second, "二次获取应返回同一单例");
    }

    @Test
    void form1_missingNoArgConstructorFailsAtRegistration() {
        ModuleManager manager = new ModuleManager(null);
        assertThrows(IllegalArgumentException.class,
                () -> manager.register(WithArgs.class),
                "缺 public 无参构造应在注册期 fail-fast");
    }

    // ==================== 形式 1b：单类型工厂函数 ====================

    @Test
    void form1b_factoryLazySingleton() {
        ModuleManager manager = new ModuleManager(null);
        AtomicInteger calls = new AtomicInteger();
        manager.registerFactory(Product.class, () -> new Product("f-" + calls.incrementAndGet()));

        assertEquals(0, calls.get(), "注册后工厂不应被调用");
        Product p1 = manager.get(Product.class);
        Product p2 = manager.get(Product.class);
        assertEquals(1, calls.get(), "工厂只应调用一次");
        assertSame(p1, p2);
        assertEquals("f-1", p1.tag);
    }

    @Test
    void form1b_nullProductThrows() {
        ModuleManager manager = new ModuleManager(null);
        manager.registerFactory(Product.class, () -> null);
        assertThrows(IllegalStateException.class, () -> manager.get(Product.class),
                "工厂产出 null 应 fail-fast");
    }

    // ==================== 形式 3：现成实例 ====================

    @Test
    void form3_readyInstance() {
        ModuleManager manager = new ModuleManager(null);
        Product product = new Product("ready");
        manager.register(Product.class, product);
        assertSame(product, manager.get(Product.class));
    }

    // ==================== 形式 2：工厂类（A/B 引用场景） ====================

    @Test
    void form2_moduleProducesGraphWithInternalRefs() {
        ModuleManager manager = new ModuleManager(null);
        manager.register(new JFrameModule() {
            @Override
            public String id() {
                return "ab";
            }

            @Override
            public Set<Class<?>> provides() {
                return Set.of(ServiceA.class, ServiceB.class);
            }

            @Override
            public void load(ModuleContext ctx) {
                ServiceA a = new ServiceA();
                ServiceB b = new ServiceB(a);
                ctx.register(ServiceA.class, a);
                ctx.register(ServiceB.class, b);
            }
        });

        assertTrue(manager.isRegistered(ServiceB.class));
        ServiceB b = manager.get(ServiceB.class);
        assertNotNull(b);
        assertSame(manager.get(ServiceA.class), b.a, "B 持有的 A 应与容器单例一致");
        assertTrue(manager.isLoaded("ab"));
    }

    @Test
    void form2_registerOutsideProvidesFails() {
        ModuleManager manager = new ModuleManager(null);
        manager.register(new JFrameModule() {
            @Override
            public String id() {
                return "wrong";
            }

            @Override
            public Set<Class<?>> provides() {
                return Set.of(ServiceA.class);
            }

            @Override
            public void load(ModuleContext ctx) {
                ctx.register(ServiceB.class, new ServiceB(new ServiceA()));
            }
        });
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> manager.get(ServiceA.class), "登记类型不符应导致模块加载失败");
        assertFalse(manager.isLoaded("wrong"));
    }

    @Test
    void form2_lightweightOverload() {
        ModuleManager manager = new ModuleManager(null);
        manager.register("light", Set.of(Product.class),
                ctx -> ctx.register(Product.class, new Product("light")));
        assertEquals("light", manager.get(Product.class).tag);
    }

    @Test
    void form2_requiresLoadedRecursively() {
        ModuleManager manager = new ModuleManager(null);
        List<String> loadedOrder = new ArrayList<>();
        manager.register(new JFrameModule() {
            @Override
            public String id() {
                return "dep-base";
            }

            @Override
            public Set<Class<?>> provides() {
                return Set.of(ServiceA.class);
            }

            @Override
            public void load(ModuleContext ctx) {
                loadedOrder.add("base");
                ctx.register(ServiceA.class, new ServiceA());
            }
        });
        manager.register(new JFrameModule() {
            @Override
            public String id() {
                return "dep-top";
            }

            @Override
            public Set<String> requires() {
                return Set.of("dep-base");
            }

            @Override
            public Set<Class<?>> provides() {
                return Set.of(ServiceB.class);
            }

            @Override
            public void load(ModuleContext ctx) {
                loadedOrder.add("top");
                ctx.register(ServiceB.class, new ServiceB(ctx.get(ServiceA.class)));
            }
        });
        ServiceB b = manager.get(ServiceB.class);
        assertSame(manager.get(ServiceA.class), b.a);
        assertEquals(List.of("base", "top"), loadedOrder, "requires 依赖应先加载");
    }

    // ==================== fail-fast 分支 ====================

    @Test
    void duplicateRegistrationThrows() {
        ModuleManager manager = new ModuleManager(null);
        manager.register(Plain.class);
        assertThrows(IllegalArgumentException.class,
                () -> manager.register(Plain.class),
                "同类型重复注册应抛异常");
    }

    @Test
    void unregisteredTypeThrows() {
        ModuleManager manager = new ModuleManager(null);
        assertThrows(IllegalStateException.class, () -> manager.get(Plain.class),
                "未注册类型应列出可用类型并抛异常");
    }

    @Test
    void selfReferencingFactoryDetectedAsCycle() {
        ModuleManager manager = new ModuleManager(null);
        manager.registerFactory(Product.class, () -> manager.get(Product.class));
        assertThrows(IllegalStateException.class, () -> manager.get(Product.class),
                "工厂内递归 get 自身类型应报循环依赖");
    }

    @Test
    void operationsRejectedAfterShutdown() {
        ModuleManager manager = new ModuleManager(null);
        manager.register(Plain.class);
        manager.get(Plain.class);
        manager.shutdown();
        manager.shutdown();
        assertThrows(IllegalStateException.class, () -> manager.get(Plain.class),
                "关闭后 get 应拒绝");
        assertThrows(IllegalStateException.class, () -> manager.register(Product.class),
                "关闭后 register 应拒绝");
    }

    // ==================== shutdownOrder 排序 ====================

    @Test
    void shutdownRunsLoadedModulesInOrder() {
        ModuleManager manager = new ModuleManager(null);
        List<String> order = new ArrayList<>();
        manager.register(orderModule("late", 40, ServiceA.class, ServiceA::new, order));
        manager.register(orderModule("early", 10, ServiceB.class,
                () -> new ServiceB(new ServiceA()), order));
        manager.register(orderModule("never-loaded", 5, Product.class,
                () -> new Product("x"), order));

        manager.get(ServiceA.class);
        manager.get(ServiceB.class);
        manager.shutdown();

        assertEquals(List.of("early", "late"), order,
                "按 shutdownOrder 升序执行，未加载模块不执行");
    }

    private static JFrameModule orderModule(String id, int order, Class<?> type,
                                            java.util.function.Supplier<Object> producer, List<String> sink) {
        return new JFrameModule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<Class<?>> provides() {
                return Set.of(type);
            }

            @Override
            public void load(ModuleContext ctx) {
                registerRaw(ctx, type, producer.get());
            }

            @SuppressWarnings({"unchecked", "rawtypes"})
            private void registerRaw(ModuleContext ctx, Class<?> rawType, Object instance) {
                ctx.register((Class) rawType, instance);
            }

            @Override
            public int shutdownOrder() {
                return order;
            }

            @Override
            public void shutdown(ModuleContext ctx) {
                sink.add(id);
            }
        };
    }

    // ==================== 预加载 ====================

    @Test
    void preloadLoadsModuleWithoutGet() {
        ModuleManager manager = new ModuleManager(null);
        Plain.created = 0;
        manager.register("pre", Set.of(Plain.class),
                ctx -> ctx.register(Plain.class, new Plain()));
        assertFalse(manager.isLoaded("pre"));
        manager.preload("pre");
        assertTrue(manager.isLoaded("pre"));
        assertEquals(1, Plain.created);

        assertThrows(IllegalArgumentException.class, () -> manager.preload("no-such-id"),
                "预加载不存在的模块 id 应抛异常");
    }

    // ==================== 隔离实例语义 ====================

    @Test
    void managersAreIsolated() {
        ModuleManager first = new ModuleManager(null);
        ModuleManager second = new ModuleManager(null);
        first.register(Plain.class);
        second.register(Plain.class);

        assertNotSame(first.get(Plain.class), second.get(Plain.class),
                "两个容器实例应各自独立创建单例");
        assertThrows(IllegalStateException.class, () -> second.get(Product.class),
                "容器 2 看不到容器 1 的注册（此处两者都未注册 Product）");

        ModuleManager third = new ModuleManager(null);
        assertThrows(IllegalStateException.class, () -> third.get(Plain.class),
                "新建容器看不到其他容器的注册");
    }

    // ==================== 注解扫描 ====================

    @Test
    void scanRegistersAnnotationTargetsLazily() {
        ModuleManager manager = new ModuleManager(null);
        ScannedPlain.reset();
        ScannedFactory.reset();
        ScannedFactoryClass.reset();

        Set<Class<?>> registered = manager.scan(
                getClass().getClassLoader(), FIXTURE_GOOD);

        assertTrue(registered.contains(ScannedPlain.class));
        assertTrue(registered.contains(ScannedFactory.class));
        assertTrue(registered.contains(ScannedFactoryClass.class));
        assertEquals(0, ScannedPlain.createdCount(), "扫描不应实例化 @JBean 产品类");
        assertEquals(0, ScannedFactory.factoryUsedCount(), "扫描不应调用 @JFactory");
        assertEquals(0, ScannedFactoryClass.instantiatedCount(), "扫描不应实例化工厂类");

        ScannedPlain plain = manager.get(ScannedPlain.class);
        assertEquals(1, ScannedPlain.createdCount(), "首次 get 才无参 new");

        ScannedFactory made = manager.get(ScannedFactory.class);
        assertNotNull(made);
        assertEquals(1, ScannedFactory.factoryUsedCount(), "@JFactory 方法被用于构造");
        assertSame(made, manager.get(ScannedFactory.class));

        DependencyB b = manager.get(DependencyB.class);
        assertEquals(1, ScannedFactoryClass.instantiatedCount(), "首次 get 工厂产物才实例化工厂类");
        assertSame(manager.get(DependencyA.class), b.getA(),
                "ctx.get(A) 与直接 get(A) 应返回同一单例");
    }

    @Test
    void scanBadFixturesFailFast() {
        ModuleManager manager = new ModuleManager(null);
        assertThrows(IllegalArgumentException.class,
                () -> manager.scan(getClass().getClassLoader(), FIXTURE_BAD),
                "声明歧义的 fixture 应在扫描注册期抛出");
    }

    @Test
    void duplicateModuleIdRejected() {
        ModuleManager manager = new ModuleManager(null);
        manager.register("dup", Set.of(Plain.class),
                ctx -> ctx.register(Plain.class, new Plain()));
        assertThrows(IllegalArgumentException.class,
                () -> manager.register("dup", Set.of(Product.class),
                        ctx -> ctx.register(Product.class, new Product("x"))),
                "模块 id 重复应抛异常");
    }

    @Test
    void jBeanAndJFactoryClassConflictDetected() {
        ModuleManager manager = new ModuleManager(null);
        assertThrows(IllegalArgumentException.class,
                () -> manager.scan(getClass().getClassLoader(),
                        "io.github.JiangHu.jframe.core.module.fixture.bad2"),
                "同时标注 @JBean 与 @JFactoryClass 应在扫描期抛出");
    }
}
