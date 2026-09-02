package dev.hauch.restartly.persistence;

import dev.hauch.restartly.Restartly;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists the minimal amount of state required to recover cleanly after a
 * crash or server restart:
 *
 * <ul>
 *   <li>{@code last_restart} — anchor for interval schedules;</li>
 *   <li>{@code maintenance} — restored maintenance mode.</li>
 * </ul>
 *
 * <p>Active countdowns are deliberately <em>not</em> persisted: restoring a
 * half finished countdown after a crash would either fire instantly or wait
 * forever. The scheduler simply recomputes the next fire time.</p>
 */
public final class StateStore {

    private final Path file;
    private volatile Instant lastRestart;
    private volatile boolean maintenance;

    public StateStore(Path configDir) {
        this.file = configDir.resolve("state.yml");
    }

    public void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new SafeConstructor(options));
            Object loaded = yaml.load(Files.readString(file, StandardCharsets.UTF_8));
            if (loaded instanceof Map<?, ?> map) {
                Object last = map.get("last_restart");
                if (last != null) {
                    try {
                        lastRestart = Instant.parse(String.valueOf(last));
                    } catch (Exception e) {
                        Restartly.LOGGER.warn(
                                "[Restartly] Ignoring invalid last_restart in state.yml: {}", last);
                    }
                }
                Object maintenanceValue = map.get("maintenance");
                if (maintenanceValue instanceof Boolean b) {
                    maintenance = b;
                }
            }
        } catch (IOException | org.yaml.snakeyaml.error.YAMLException e) {
            Restartly.LOGGER.warn("[Restartly] Could not read state.yml: {}", e.getMessage());
        }
    }

    public synchronized void save() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("last_restart", lastRestart == null ? null : lastRestart.toString());
        map.put("maintenance", maintenance);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, new Yaml().dump(map), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException e) {
            Restartly.LOGGER.debug("[Restartly] Could not save state.yml: {}", e.getMessage());
        }
    }

    public Instant lastRestart() {
        return lastRestart;
    }

    public void setLastRestart(Instant instant) {
        this.lastRestart = instant;
    }

    public boolean maintenance() {
        return maintenance;
    }

    public void setMaintenance(boolean maintenance) {
        this.maintenance = maintenance;
        save();
    }
}