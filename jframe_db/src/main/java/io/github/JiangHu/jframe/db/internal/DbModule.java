package io.github.JiangHu.jframe.db.internal;

import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;
import io.github.JiangHu.jframe.db.DbAPI;

import java.util.Set;

/**
 * 数据库模块工厂（模块 id {@code "db"}）。
 * <p>
 * 装配极简：DbAPI 单例（内部持有 serialize 管线的 MetadataCache/Gson、虚拟线程
 * 执行器、失效广播协调器与实例名册——均在其首次使用时激活）。
 * <p>
 * 关服顺序 400（先于默认 500 的业务模块停）：注销全部存储通道（释放区域、
 * 退订广播、清本地缓存）并关闭执行器。
 */
public class DbModule implements JFrameModule {

    /** load 时创建的实例（shutdown 回调用；容器保证单模块单实例） */
    private volatile DbAPI api;

    @Override
    public String id() {
        return "db";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(DbAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        api = new DbAPI();
        ctx.register(DbAPI.class, api);
    }

    @Override
    public int shutdownOrder() {
        return 400;
    }

    @Override
    public void shutdown(ModuleContext ctx) {
        DbAPI current = api;
        if (current != null) {
            current.shutdown();
        }
    }
}
