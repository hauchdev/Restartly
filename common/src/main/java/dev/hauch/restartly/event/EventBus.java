package dev.hauch.restartly.event;

import dev.hauch.restartly.api.RestartEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Very small, dependency-free event bus. All events are fired on the server
 * thread; listeners run synchronously in registration order.
 */
public final class EventBus {

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private record Listener(Class<?> type, RestartEvent.Listener<?> handler) {
    }

    @SuppressWarnings("unchecked")
    public <T extends RestartEvent> void subscribe(Class<T> type, RestartEvent.Listener<T> handler) {
        listeners.add(new Listener(type, (RestartEvent.Listener<RestartEvent>) handler));
    }

    @SuppressWarnings("unchecked")
    public void fire(RestartEvent event) {
        for (Listener listener : listeners) {
            if (listener.type().isInstance(event)) {
                ((RestartEvent.Listener<RestartEvent>) listener.handler()).on(event);
            }
        }
    }
}