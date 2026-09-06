package io.github.JiangHu.jframe.inventory.ui.component;

import cn.nukkit.inventory.Inventory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享库存变更同步通知器。
 * <p>
 * 维护「外部库存 → 活跃的 {@link SharedInventorySlice} 列表」的映射。
 * 当某个切片写回外部库存后，通过本类通知所有指向同一外部库存的其他切片刷新。
 *
 * <h3>工作流程</h3>
 * <pre>{@code
 * 玩家A 取走物品
 *   → SharedInventorySlice A.onStore()
 *     → 写回外部库存
 *     → SharedInventorySync.notifyChanged(externalInventory, slot, sliceA)
 *       → 遍历同一外部库存的所有切片（排除 sliceA）
 *         → SharedInventorySlice B.onExternalChanged(slot)
 *           → 从外部库存读取最新物品
 *           → 更新玩家B的 VirtualInventory + 发包
 * }</pre>
 *
 * <h3>线程安全</h3>
 * 使用 {@link ConcurrentHashMap}，所有操作无锁。
 * 实际调用发生在 Nukkit 主线程（事件处理），无真正的并发竞争。
 *
 * @see SharedInventorySlice
 */
public final class SharedInventorySync {

    /** 外部库存 → 活跃切片集合 */
    private static final ConcurrentHashMap<Inventory, Set<SharedInventorySlice>> registry =
            new ConcurrentHashMap<>();

    private SharedInventorySync() {
    }

    /**
     * 注册切片（在 {@link SharedInventorySlice#onMount} 时调用）。
     * <p>
     * 将切片加入外部库存对应的活跃集合，使其能接收变更通知。
     *
     * @param slice 切片组件
     */
    static void register(SharedInventorySlice slice) {
        registry.computeIfAbsent(slice.externalInventory(),
                k -> ConcurrentHashMap.newKeySet()).add(slice);
    }

    /**
     * 注销切片（在 {@link SharedInventorySlice#onUnmount} 时调用）。
     * <p>
     * 将切片从外部库存对应的活跃集合中移除。当集合为空时清理映射条目，避免内存泄漏。
     *
     * @param slice 切片组件
     */
    static void unregister(SharedInventorySlice slice) {
        Set<SharedInventorySlice> slices = registry.get(slice.externalInventory());
        if (slices != null) {
            slices.remove(slice);
            // 清理空集合，避免内存泄漏
            if (slices.isEmpty()) {
                registry.remove(slice.externalInventory(), slices);
            }
        }
    }

    /**
     * 通知外部库存的某个槽位发生了变化。
     * <p>
     * 遍历指向同一外部库存的所有切片（排除发起者），调用其 {@code onExternalChanged}。
     *
     * @param externalInventory 外部库存
     * @param externalSlot      变化的槽位
     * @param source            发起变化的切片（排除自身，避免重复刷新）
     */
    static void notifyChanged(Inventory externalInventory, int externalSlot,
                              SharedInventorySlice source) {
        Set<SharedInventorySlice> slices = registry.get(externalInventory);
        if (slices == null) return;
        for (SharedInventorySlice slice : slices) {
            if (slice != source) {
                slice.onExternalChanged(externalSlot);
            }
        }
    }

    /**
     * 通知外部库存的多个槽位发生了变化。
     * <p>
     * 适用于批量操作场景（如程序化修改外部库存后一次性通知）。
     *
     * @param externalInventory 外部库存
     * @param externalSlots     变化的槽位列表
     * @param source            发起变化的切片（可为 null，表示通知所有切片）
     */
    public static void notifyChanged(Inventory externalInventory, List<Integer> externalSlots,
                                     SharedInventorySlice source) {
        Set<SharedInventorySlice> slices = registry.get(externalInventory);
        if (slices == null) return;
        for (SharedInventorySlice slice : slices) {
            if (slice != source) {
                for (int slot : externalSlots) {
                    slice.onExternalChanged(slot);
                }
            }
        }
    }

    /**
     * 通知外部库存的所有槽位刷新（全量同步）。
     * <p>
     * 适用于外部库存被整体替换或重置的场景。遍历所有切片，触发全量重新渲染。
     *
     * @param externalInventory 外部库存
     */
    public static void notifyFullRefresh(Inventory externalInventory) {
        Set<SharedInventorySlice> slices = registry.get(externalInventory);
        if (slices == null) return;
        for (SharedInventorySlice slice : slices) {
            // 触发视图重绘，onRender 会从外部库存重新读取所有物品
            if (slice.view() != null) {
                slice.view().repaint();
            }
        }
    }

    /**
     * 获取指向指定外部库存的活跃切片数量（调试/监控用）。
     *
     * @param externalInventory 外部库存
     * @return 切片数量
     */
    public static int sliceCount(Inventory externalInventory) {
        Set<SharedInventorySlice> slices = registry.get(externalInventory);
        return slices != null ? slices.size() : 0;
    }
}
