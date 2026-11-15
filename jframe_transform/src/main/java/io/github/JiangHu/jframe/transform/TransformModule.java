package io.github.JiangHu.jframe.transform;

import cn.nukkit.plugin.Plugin;
import io.github.JiangHu.jframe.core.module.JFrameModule;
import io.github.JiangHu.jframe.core.module.ModuleContext;
import io.github.JiangHu.jframe.transform.agent.AgentInstaller;
import io.github.JiangHu.jframe.transform.bridge.Bridge;
import io.github.JiangHu.jframe.transform.lifecycle.PluginDisableListener;
import io.github.JiangHu.jframe.transform.registry.BridgeSinkImpl;
import io.github.JiangHu.jframe.transform.registry.HookRegistry;

import java.util.Set;

/**
 * 动态类增强模块工厂（设计方案 {@code plans/transform-module-plan.md}）。
 * <p>
 * 装配：HookRegistry → BridgeSink（经 bridge 分发）→ PluginDisableListener
 * （插件卸载自动 unhook + 还原）→ TransformAPI 门面。agent 自附加与 bridge jar
 * 注入<b>延迟到首次注册</b>（懒加载语义：未用到的模块零成本）。
 * <p>
 * 关闭顺序 950（晚于 logger 的 900）：停分发 → 移除 transformer → 全部织入类
 * 还原原始字节码 → 清空注册表（{@code /reload} 后重新装配，无悬挂类引用）。
 */
public class TransformModule implements JFrameModule {

    private HookRegistry registry;
    private TransformAPI api;

    @Override
    public String id() {
        return "transform";
    }

    @Override
    public Set<Class<?>> provides() {
        return Set.of(TransformAPI.class);
    }

    @Override
    public void load(ModuleContext ctx) {
        Plugin plugin = ctx.plugin();
        if (plugin != null) {
            // 运维总开关（config.yml transform.enabled，默认 true；false 时模块禁用）
            if (!plugin.getConfig().getBoolean("transform.enabled", true)) {
                throw new IllegalStateException(
                        "transform 模块已被 config.yml 的 transform.enabled=false 禁用（运维逃生口）");
            }
            AgentInstaller.configureDataDir(plugin.getDataFolder().toPath());
        }

        registry = new HookRegistry();

        ctx.bind(new PluginDisableListener(registry));

        api = new TransformAPI(registry);
        ctx.register(TransformAPI.class, api);
    }

    @Override
    public int shutdownOrder() {
        return 950;
    }

    @Override
    public void shutdown(ModuleContext ctx) {
        if (api != null) {
            // 停分发 → 移除 transformer → 还原全部织入类 → 清空（§3.6）
            try {
                Bridge.sink = null;
            } catch (Throwable ignored) {
                // bridge 类从未装载（agent 从未安装）时解析失败——安全忽略
            }
            registry.shutdown();
        }
    }
}
