package dev.hauch.restartly.restart;

public record RestartRequest(
        String reason,
        long delaySeconds
) {
}
