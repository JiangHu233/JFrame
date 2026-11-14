package io.github.JiangHu.jframe.example.ecs.spawner;

import io.github.JiangHu.jframe.engine.ecs.spawner.SpawnContext;
import io.github.JiangHu.jframe.engine.ecs.spawner.Spawner;
import io.github.JiangHu.jframe.engine.ecs.annotation.EcsSpawner;
import io.github.JiangHu.jframe.example.ExamplePlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 演示生成器 —— 竞技场模板（<b>创建伴随文件 IO</b> 的典型产物，模拟复制 level 目录）。
 *
 * <p>YAML 引用（arena 定义）：
 * <pre>{@code
 * spawn: { use: arena-template, params: { name: "wall-arena" } }
 * }</pre>
 *
 * <p>向插件数据目录 {@code arenas/<name>.marker} 写入 marker 文件并返回
 * {@link ArenaRef} 产物——引擎为它建实体代理（{@code binding()} 即 ArenaRef），
 * 回收侧由 {@code arena-recycle} 回调删文件（见 ArenaRecycleDespawner）。
 * 真实项目中这一步就是"复制 level 模板目录 + 加载 level"，产物即 {@code Level} 引用。
 */
@EcsSpawner("arena-template")
public class ArenaTemplateSpawner implements Spawner {

    /** 竞技场名（params.name 注入；决定 marker 文件名） */
    private String name = "wall-arena";

    @Override
    public Object spawn(SpawnContext context) {
        Path dir = ExamplePlugin.getInstance().getDataFolder().toPath().resolve("arenas");
        Path marker = dir.resolve(name + ".marker");
        try {
            Files.createDirectories(dir);
            Files.writeString(marker, "arena:" + name + "\nspawned:" + System.currentTimeMillis());
        } catch (IOException e) {
            throw new IllegalStateException("竞技场 marker 文件写入失败: " + marker, e);
        }
        return new ArenaRef(name, marker);
    }
}
