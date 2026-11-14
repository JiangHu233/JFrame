package io.github.JiangHu.jframe.example.ecs.spawner;

import java.nio.file.Path;

/**
 * 演示产物 —— 竞技场资源句柄（<b>被代理的游戏内容不限于 entity</b> 的活例）。
 *
 * <p>spawn 产出本 POJO → 引擎建 EcsEntity 代理它（{@code entity.binding()} 即本实例）；
 * despawn 回调经 {@code ctx.product()} 取回本实例执行回收（删除 marker 文件）。
 * 真实项目里它可以是 {@code Level}、{@code Server} 或任意重资源对象——引擎不关心类型。
 *
 * @param name       竞技场名（params.name 注入 ArenaTemplateSpawner）
 * @param markerPath spawn 时写入、despawn 时删除的 marker 文件（模拟 level 目录级文件 IO）
 */
public record ArenaRef(String name, Path markerPath) {
}
