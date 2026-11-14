# 浮空字模块（jframe_floatingtext）

世界空间文字显示的**广义通用原语**——不预设业务用途。渲染采用**发包假实体**
（零服务器实体、零 level 注册、零区块绑定）；伤害数字样式/合并、全息配置化、
脚步触发、粒子特效等由下游基于本模块原语组合实现。

- 依赖：仅 `jframe_core` + Nukkit(provided)；不依赖 engine(ECS)/template
- 线程约定：全部 API 仅主线程调用

## 语义声明

- **瞬态不补发**：瞬态条目寿命几秒，玩家重连漏看可接受；常驻条目由监听器在
  进服/重生/换世界时自动对账补发（周期对账兜底事件丢失，默认 100 tick）。
- 条目寿命：`lifetimeTicks` 省略或 ≤0 = **常驻**；瞬态必填正寿命。

## 快速上手

```java
FloatingTextAPI ft = JFrameMain.getInstance().getFloatingTextAPI();

// 瞬态（伤害数字 / 脚步）
ft.show(FloatingText.builder()
        .position(pos).text("§c-25")
        .animation(Animations.rise(b -> b.height(1.2).durationTicks(30)))
        .lifetimeTicks(40).tag("combat").build());

// 常驻组（大厅全息，\n 单实体多行；重连自动补发）
FloatingTextGroup board = ft.group("lobby:rules", pos)
        .lines("§b=== 主城 ===", "§7规则").build();
board.setTexts("§b=== 主城 ===", "§7新内容");   // 整组刷新

ft.remove("lobby:rules");        // 按名移除
ft.removeByTag("match:step");    // 按标签批量
ft.stats();                      // 活跃数 / 包操作数
```

## 能力 × 设施对照

| 能力 | 设施 |
|---|---|
| 世界任意位置放字 / 跟随或自解算位置 | Builder + `PositionSource`（动态源每节流周期重算） |
| 定时消失 / 常驻重连恢复 | 生命周期（lifetimeTicks）+ `ViewerResyncListener` |
| 动效（上浮淡出 / 自定义物理） | `FloatingTextAnimation` SPI（内置 none/rise；`offset`/`setText` 写回） |
| 多行信息牌 | `FloatingTextGroup`（`\n` 单实体） |
| 指定人群可见 | `Viewers`（all/of/except，可自定义实现） |
| 运行中改字/改位/删除 | 句柄 `setText` / `move` / `remove` |
| 高频大量并发（脚步级） | 发包渲染零实体 + `moveIntervalTicks` 节流（可逐条覆盖为 1）+ 瞬态上限 |
| 防炸服 + 调参 | `maxActiveTransient`（DROP_NEWEST/REPLACE_OLDEST）+ `stats()` |

## 下游组合范式（fapeft 等）

**① 脚步显示**（none 零运动开销 + 敌方可见 + 对局结束批量清理）：

```java
ft.show(FloatingText.builder()
        .position(stepPos.add(0, 0.1, 0)).text("§7·")
        .animation(Animations.none()).lifetimeTicks(30)
        .viewers(Viewers.of(enemyIds)).tag("match:step").build());
// 对局结束：ft.removeByTag("match:step");
```

**② 合并伤害数字**（模块不内置合并，下游 10 行组合）：

```java
Map<Long, FloatingText> lastHit = new HashMap<>();
void showDamage(Entity victim, double amount) {
    FloatingText t = lastHit.get(victim.getId());
    if (t != null && t.valid()) {                       // 窗口内（寿命未过）
        merged += amount;
        t.setText("§c-" + (int) merged);
    } else {
        merged = amount;
        lastHit.put(victim.getId(), ft.show(FloatingText.builder()
                .positionSource(() -> victim.add(0, 1.8, 0))   // 跟随目标
                .text("§c-" + (int) amount)
                .animation(Animations.rise()).lifetimeTicks(30).build()));
    }
}
```

**③ 排行榜实时刷新**：`group("parkour:top", pos).lines(top10)` + 破纪录时 `setTexts(...)`。

**④ 爆炸碎片特效**（自定义动画 + 每 tick 更新）：

```java
public class ShardAnimation implements FloatingTextAnimation {
    private final double vx, vy, vz;      // 构造时随机径向初速
    private double x, y, z;
    public boolean tick(FloatingText text, int age) {
        x += vx;  y += vy;  z += vz;  vy -= 0.03;       // 物理
        text.offset(x, y, z);                            // 写回偏移
        if (age == 8) text.setText("§6✦");               // 颜色阶淡出
        return age < 20;
    }
}
// 每碎片：.animation(new ShardAnimation(...)).lifetimeTicks(20).moveIntervalTicks(1)
// 成本账：碎片数 × 观众数 × 更新率 —— stats() 校准，上限兜底
```

**⑤ ECS 触发对接**（引擎组件当触发方，示例属业务插件不进本模块）：

```java
@EcsComponent("combat-floating-text")
public class CombatFloatingTextComponent extends ComponentBase {
    @OnEcsEvent
    public void onDamage(EntityDamageEvent event) {
        Entity self = host().binding();
        if (self != null) {
            floatingText.show(FloatingText.builder()
                    .positionSource(() -> self.add(0, 1.8, 0))
                    .text("§c-" + (int) event.getDamage())
                    .animation(Animations.rise()).lifetimeTicks(30).build());
        }
    }
}
```

## 扩展点

| 扩展点 | 形态 | 默认 | 外接方式 |
|---|---|---|---|
| 渲染器 | `FloatingTextRenderer` SPI | `VirtualEntityRenderer`（发包） | Spring bean 覆盖 `floatingTextRenderer` |
| 动画 | `FloatingTextAnimation` | none / rise | 实现接口经 builder 传入 |
| 观众策略 | `Viewers` | all / of / except | 实现接口（按队伍可见等） |
| 位置 | `PositionSource` | 静态坐标 | 动态源函数 |

## 调参

| 参数 | 默认 | 说明 |
|---|---|---|
| `maxActiveTransient` | 400 | 瞬态上限（溢出策略 DROP_NEWEST / REPLACE_OLDEST） |
| `defaultMoveIntervalTicks` | 2 | 位置/文本节流；爆炸类逐条覆盖为 1 |
| `reconcileIntervalTicks` | 100 | 周期对账（≤0 关闭） |
| `moveIntervalTicks`（Builder） | 0=用全局 | 逐条覆盖 |
| `levelName`（Builder） | 位置自动解析 | 位置源无 level 时显式指定 |

发包元数据要点（对 MOT jar 核对）：AddEntityPacket 双填 `type=61`（旧协议）与
`id="minecraft:armor_stand"`（新协议 ≥313）；元数据 = 隐身 flag + 名牌 + 常显名牌；
虚拟 id 段 `2_000_000_000` 起单调不复用。**真机冒烟为发布门槛**（隐身/名牌/`\n` 多行
在目标客户端的实际效果）。
