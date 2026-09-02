package dev.hauch.restartly.api;


import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Immutable snapshot of everything a placeholder can render. Built per
 * message so values like {@code players} reflect the moment of emission.
 *
 * @param remaining     time left until the restart, may be null when idle
 * @param onlinePlayers current online player count
 * @param maxPlayers    server player cap
 * @param serverVersion Minecraft version string ("1.20.1")
 * @param reason        restart reason, may be null
 * @param scheduleId    identifier of the triggering schedule ("manual" for
 *                      manual restarts), may be null
 * @param timezone      active timezone
 * @param state         current Restartly state name
 * @param uptime        server uptime
 */
public record PlaceholderContext(
        Duration remaining,
        int onlinePlayers,
        int maxPlayers,
        String serverVersion,
        String reason,
        String scheduleId,
        ZoneId timezone,
        String state,
        Duration uptime,
        Instant now
) {

    public PlaceholderContext {
        timezone = timezone == null ? ZoneId.systemDefault() : timezone;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Duration remaining;
        private int onlinePlayers;
        private int maxPlayers;
        private String serverVersion;
        private String reason;
        private String scheduleId;
        private ZoneId timezone;
        private String state;
        private Duration uptime;
        private Instant now;

        public Builder remaining(Duration remaining) {
            this.remaining = remaining;
            return this;
        }

        public Builder onlinePlayers(int onlinePlayers) {
            this.onlinePlayers = onlinePlayers;
            return this;
        }

        public Builder maxPlayers(int maxPlayers) {
            this.maxPlayers = maxPlayers;
            return this;
        }

        public Builder serverVersion(String serverVersion) {
            this.serverVersion = serverVersion;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder scheduleId(String scheduleId) {
            this.scheduleId = scheduleId;
            return this;
        }

        public Builder timezone(ZoneId timezone) {
            this.timezone = timezone;
            return this;
        }

        public Builder state(String state) {
            this.state = state;
            return this;
        }

        public Builder uptime(Duration uptime) {
            this.uptime = uptime;
            return this;
        }

        public Builder now(Instant now) {
            this.now = now;
            return this;
        }

        public PlaceholderContext build() {
            return new PlaceholderContext(remaining, onlinePlayers, maxPlayers,
                    serverVersion, reason, scheduleId, timezone,
                    state == null ? "IDLE" : state, uptime, now);
        }
    }

    /**
     * Convenience so pure logic (tests, custom placeholders) can build
     * contexts without the builder ceremony.
     */
    public static PlaceholderContext simple(String state, Duration remaining) {
        return new PlaceholderContext(remaining, 0, 0, "1.20.1", null, null,
                ZoneId.of("UTC"), state, Duration.ZERO, Instant.EPOCH);
    }

    /**
     * Formats counting placeholders ({time}, {seconds}, ...) consistently.
     */
    public long totalSeconds() {
        return remaining == null ? 0 : Math.max(0, remaining.getSeconds());
    }
}