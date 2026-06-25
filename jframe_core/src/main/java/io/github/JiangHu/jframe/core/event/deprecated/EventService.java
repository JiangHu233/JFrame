package io.github.JiangHu.jframe.core.event.deprecated;

import io.github.JiangHu.jframe.core.event.EventConsumer;
import cn.nukkit.event.Event;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

@Deprecated
public class EventService implements Listener {
    public static final int SPECIAL_FIRST_PRIORITY = 1;
    public static final int TYPE_SECOND_PRIORITY = 2;
    public static final int LEVEL_THIRD_PRIORITY = 3;
    public static final int LAST_FORTH_PRIORITY = 4;

    @Getter
    private final Map<Integer, ArrayList<EventConsumer>> eventConsumers = new TreeMap<Integer, ArrayList<EventConsumer>>();

    public void register(int priority, EventConsumer eventConsumer) {
        if (!eventConsumers.containsKey(priority)) {
            eventConsumers.put(priority, new ArrayList<EventConsumer>());
        }
        eventConsumers.get(priority).add(eventConsumer);
    }

    @EventHandler
    public void handleEvent(Event event) {
        for (Map.Entry<Integer, ArrayList<EventConsumer>> entry : eventConsumers.entrySet()) {
            for (EventConsumer eventConsumer : entry.getValue()) {
                // 独占事件处理则退出
                if (eventConsumer.handleEvent(event)) return;
            }
        }
    }
}
