package dev.hauch.restartly.updates;

import dev.hauch.restartly.Restartly;
import dev.hauch.restartly.util.Version;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks GitHub releases asynchronously (never blocks the server thread)
 * and logs a single info line when a newer version exists. Failures are
 * silent — an unreachable network must never disturb the server.
 */
public final class UpdateChecker {

    private static final String RELEASES_URL =
            "https://api.github.com/repos/hauchdev/Restartly/releases/latest";

    private static final Pattern TAG_VERSION =
            Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9.]+)\"");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public void checkAsync(String currentVersion) {
        ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "restartly-update-check");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.execute(() -> {
            try {
                check(currentVersion);
            } finally {
                executor.shutdown();
            }
        });
    }

    private void check(String currentVersion) {
        try {
            Version running;
            try {
                running = Version.parse(currentVersion);
            } catch (IllegalArgumentException e) {
                return;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASES_URL))
                    .header("User-Agent", "Restartly")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return;
            }
            Matcher matcher = TAG_VERSION.matcher(response.body());
            if (!matcher.find()) {
                return;
            }
            Version remote = Version.parse(matcher.group(1));
            if (remote.isNewerThan(running)) {
                Restartly.LOGGER.info("[Restartly] A new version is available: {} "
                        + "(you are running {}). Update at "
                        + "https://github.com/hauchdev/Restartly/releases",
                        remote, running);
            }
        } catch (Exception e) {
            Restartly.LOGGER.debug("[Restartly] Update check failed: {}", e.getMessage());
        }
    }
}