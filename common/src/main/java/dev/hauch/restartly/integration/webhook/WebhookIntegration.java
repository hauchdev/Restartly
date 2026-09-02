package dev.hauch.restartly.integration.webhook;

import dev.hauch.restartly.Restartly;
import dev.hauch.restartly.api.RestartEvent;
import dev.hauch.restartly.config.ConfigManager;
import dev.hauch.restartly.config.RestartlyConfig;
import dev.hauch.restartly.core.RestartlyCore;
import dev.hauch.restartly.event.EventBus;
import dev.hauch.restartly.integration.IntegrationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Posts JSON payloads to a webhook URL (Discord compatible) when restart
 * events fire. Never touches the server thread: dispatch happens on a single
 * dedicated daemon thread via {@link HttpClient}.
 *
 * <p>The configured URL is deliberately never logged outside DEBUG, and even
 * then only its host, so secrets cannot leak into server logs.</p>
 */
public final class WebhookIntegration implements IntegrationManager.Integration {

    private static final int HTTP_TIMEOUT_SECONDS = 10;

    private final EventBus eventBus;
    private final RestartlyCore core;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .build();

    private volatile RestartlyConfig.WebhookConfig config;
    private volatile boolean running;
    private ScheduledExecutorService executor;

    public WebhookIntegration(EventBus eventBus, RestartlyCore core) {
        this.eventBus = eventBus;
        this.core = core;
    }

    private void subscribe() {
        eventBus.subscribe(RestartEvent.RestartScheduled.class, e -> dispatch("restart_scheduled", e));
        eventBus.subscribe(RestartEvent.RestartStarted.class, e -> dispatch("restart_started", e));
        eventBus.subscribe(RestartEvent.RestartWarning.class, e -> dispatch("restart_warning", e));
        eventBus.subscribe(RestartEvent.RestartCancelled.class, e -> dispatch("restart_cancelled", e));
        eventBus.subscribe(RestartEvent.RestartWaiting.class, e -> dispatch("restart_waiting", e));
        eventBus.subscribe(RestartEvent.RestartCompleted.class, e -> dispatch("restart_completed", e));
        eventBus.subscribe(RestartEvent.MaintenanceChanged.class, e -> dispatch("maintenance_changed", e));
    }

    @Override
    public void configure(RestartlyConfig.IntegrationsConfig config, ConfigManager configManager) {
        this.config = config.webhook();
    }

    @Override
    public void start() {
        if (config == null || !config.enabled() || config.url().isBlank()) {
            return;
        }
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "restartly-webhook");
                thread.setDaemon(true);
                return thread;
            });
        }
        running = true;
        Restartly.LOGGER.info("[Restartly] Webhook integration enabled (host: {}).",
                safeHost(config.url()));
    }

    @Override
    public void shutdown() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void dispatch(String event, RestartEvent source) {
        RestartlyConfig.WebhookConfig active = config;
        if (!running || active == null || !active.enabled() || !active.wants(event)) {
            return;
        }
        String url = active.url();
        Map<String, Object> payload = payloadFor(event, source);
        executor.execute(() -> send(url, payload, active.retries(), event));
    }

    private Map<String, Object> payloadFor(String event, RestartEvent source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("timestamp", Instant.now().toString());
        payload.put("server_version", core.platform().serverVersion());
        payload.put("loader", core.platform().loaderName());
        payload.put("players", core.platform().onlinePlayers().size());
        payload.put("state", core.state().name());
        if (source instanceof RestartEvent.RestartScheduled scheduled) {
            payload.put("fire_time", scheduled.fireTime().toString());
            payload.put("countdown", scheduled.countdown().toString());
            payload.put("reason", scheduled.reason());
            payload.put("schedule", scheduled.scheduleId());
        } else if (source instanceof RestartEvent.RestartStarted started) {
            payload.put("countdown", started.countdown().toString());
            payload.put("reason", started.reason());
            payload.put("schedule", started.scheduleId());
        } else if (source instanceof RestartEvent.RestartWarning warning) {
            payload.put("remaining", warning.remaining().toString());
            payload.put("step", warning.stepLabel());
        } else if (source instanceof RestartEvent.RestartCancelled cancelled) {
            payload.put("reason", cancelled.reason());
            payload.put("automatic", cancelled.automatic());
        } else if (source instanceof RestartEvent.RestartWaiting waiting) {
            payload.put("max_delay", waiting.maxDelay().toString());
            payload.put("reason", waiting.reason());
        } else if (source instanceof RestartEvent.RestartCompleted completed) {
            payload.put("reason", completed.reason());
            payload.put("schedule", completed.scheduleId());
        } else if (source instanceof RestartEvent.MaintenanceChanged changed) {
            payload.put("maintenance", changed.active());
        }
        return payload;
    }

    private void send(String url, Map<String, Object> payload, int retries, String event) {
        if (!running || retries < 0) {
            Restartly.LOGGER.debug("[Restartly] Webhook '{}' gave up after {} retries.",
                    event, retries);
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return;
            }
            Restartly.LOGGER.debug("[Restartly] Webhook '{}' responded with status {}; retrying.",
                    event, response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            Restartly.LOGGER.debug("[Restartly] Webhook '{}' delivery failed: {}",
                    event, e.getMessage());
        }
        // Retry with a backoff on the same dedicated thread, never blocking
        // the server thread and never sleeping.
        executor.schedule(() -> send(url, payload, retries - 1, event),
                1_000L, TimeUnit.MILLISECONDS);
    }

    private static String toJson(Map<String, Object> payload) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(String.valueOf(entry.getValue()))).append('"');
        }
        return json.append('}').toString();
    }

    private static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String safeHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }
}