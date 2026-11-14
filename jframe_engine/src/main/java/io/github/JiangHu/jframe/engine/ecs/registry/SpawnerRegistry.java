package io.github.JiangHu.jframe.engine.ecs.registry;

import io.github.JiangHu.jframe.engine.ecs.exception.EcsConfigException;
import io.github.JiangHu.jframe.engine.ecs.spawner.Spawner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ?????? - ???????????????????????????
 */
public class SpawnerRegistry {

    /** ??? -> ???? */
    private final Map<String, Class<? extends Spawner>> byName = new LinkedHashMap<>();

    /**
     * ???????
     *
     * @param name ???????? spawn.use ???
     * @param type ??????????
     * @throws EcsConfigException ?????????
     */
    public synchronized void register(String name, Class<? extends Spawner> type) {
        if (name == null || name.isBlank()) {
            throw new EcsConfigException("????????");
        }
        if (byName.containsKey(name)) {
            throw new EcsConfigException("????????: '" + name + "'??? " + byName.get(name).getName() + "?");
        }
        ComponentRegistry.requireNoArgConstructor(type);
        byName.put(name, type);
    }

    /**
     * ???????????????????
     *
     * @param name ???
     * @return ????
     * @throws EcsConfigException ???
     */
    public synchronized Class<? extends Spawner> require(String name) {
        Class<? extends Spawner> type = byName.get(name);
        if (type == null) {
            throw new EcsConfigException("????????: '" + name + "'????: " + byName.keySet() + "?");
        }
        return type;
    }

    /**
     * ??????????????????????????????????????
     *
     * @param name   ???
     * @param params ???
     * @return ???????????
     * @throws EcsConfigException ????????
     */
    public synchronized Spawner create(String name, Map<String, Object> params) {
        Class<? extends Spawner> type = require(name);
        Spawner spawner;
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.trySetAccessible();
            spawner = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new EcsConfigException("????????: " + type.getName(), e);
        }
        ParamBinder.bind(spawner, params);
        return spawner;
    }

    /**
     * ?????????
     *
     * @return ??? -> ?
     */
    public synchronized Map<String, Class<? extends Spawner>> all() {
        return Map.copyOf(byName);
    }
}
