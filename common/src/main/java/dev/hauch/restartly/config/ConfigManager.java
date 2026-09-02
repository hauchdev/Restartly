package dev.hauch.restartly.config;

import dev.hauch.restartly.Restartly;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Owns the on-disk lifecycle of {@code restartly.yml}: initial write of the
 * bundled defaults, loading, migration with backups and atomic reloads.
 *
 * <p>Reload semantics: parse + validate first; only when the new file is
 * fully valid is the running snapshot swapped. An invalid reload keeps the
 * previous configuration untouched and reports every problem found.</p>
 */
public final class ConfigManager {

    private final Path configDir;
    private final Path configFile;

    private volatile RestartlyConfig current;

    public ConfigManager(Path configDir) {
        this.configDir = configDir;
        this.configFile = configDir.resolve(ConfigLoader.DEFAULT_FILE_NAME);
    }

    public Path configFile() {
        return configFile;
    }

    public RestartlyConfig get() {
        return current;
    }

    /**
     * Initial (re)load on server start. Creates the default file when none
     * exists. When the file cannot be parsed the previous configuration is
     * kept (or hardcoded defaults on first boot) and the problems logged.
     */
    public void initialize() throws IOException {
        Files.createDirectories(configDir);
        if (!Files.exists(configFile)) {
            writeDefaultFile();
            Restartly.LOGGER.info("[Restartly] Created default configuration at {}",
                    configFile.toAbsolutePath());
        }
        apply(loadFile(), true);
        if (current == null) {
            current = ConfigLoader.parseDefaults();
        }
    }

    /**
     * Reloads the configuration from disk. Returns {@code null} on success,
     * the list of concrete problems on failure (previous config is kept).
     */
    public List<String> reload() {
        try {
            ConfigResult result = loadFile();
            if (!result.problems().isEmpty()) {
                return result.problems();
            }
            apply(result, false);
            return null;
        } catch (IOException e) {
            Restartly.LOGGER.error("[Restartly] Failed to reload configuration: {}", e.getMessage());
            return List.of("Failed to read " + configFile + ": " + e.getMessage());
        }
    }

    private record ConfigResult(RestartlyConfig config, List<String> problems) {
    }

    private ConfigResult loadFile() throws IOException {
        String yamlText = Files.readString(configFile, StandardCharsets.UTF_8);
        Map<String, Object> raw;
        try {
            raw = readRaw(yamlText);
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            return new ConfigResult(null,
                    List.of("Configuration is not valid YAML: " + e.getMessage()));
        }
        int before = raw.get("version") instanceof Number n ? n.intValue() : 1;
        try {
            raw = ConfigMigrator.migrate(raw);
        } catch (ConfigException e) {
            return new ConfigResult(null, e.problems());
        }
        int after = raw.get("version") instanceof Number n ? n.intValue() : 1;
        try {
            RestartlyConfig parsed = ConfigLoader.parse(toYaml(raw));
            if (after != before) {
                backupFile(before);
            }
            return new ConfigResult(parsed, List.of());
        } catch (ConfigException e) {
            return new ConfigResult(null, e.problems());
        }
    }

    private void backupFile(int version) {
        try {
            Path backup = configFile.resolveSibling(
                    configFile.getFileName() + ".v" + version + ".bak");
            Files.copy(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
            Restartly.LOGGER.info("[Restartly] Backed up previous configuration to {}",
                    backup.getFileName());
        } catch (IOException e) {
            Restartly.LOGGER.warn("[Restartly] Could not back up configuration: {}", e.getMessage());
        }
    }

    private void apply(ConfigResult result, boolean initial) {
        if (result.config() == null) {
            List<String> problems = result.problems();
            problems.forEach(p -> Restartly.LOGGER.error("[Restartly] {}", p));
            Restartly.LOGGER.error(
                    "[Restartly] Configuration is invalid, {} previous configuration.",
                    initial ? "using built-in defaults" : "keeping");
            return;
        }
        this.current = result.config();
        if (initial) {
            Restartly.LOGGER.info("[Restartly] Loaded configuration (version {}) with {} schedules.",
                    result.config().version(), result.config().schedules().size());
        }
    }

    private void writeDefaultFile() throws IOException {
        try (InputStream stream = ConfigLoader.class.getResourceAsStream(
                "/data/restartly/restartly.yml")) {
            if (stream == null) {
                Files.writeString(configFile,
                        "# Restartly default configuration\nversion: "
                                + ConfigLoader.CURRENT_VERSION + "\n",
                        StandardCharsets.UTF_8);
                return;
            }
            Files.writeString(configFile,
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // Runtime schedule editing (/restartly schedule add|remove)
    // ------------------------------------------------------------------

    /**
     * Appends a schedule to {@code restartly.yml} and persists the file.
     * Returns {@code null} on success or the list of problems. The running
     * configuration is <em>not</em> touched; callers trigger a reload.
     */
    public List<String> addSchedule(String type, String value, String countdown) {
        try {
            Map<String, Object> raw = readRaw(Files.readString(configFile, StandardCharsets.UTF_8));
            List<Object> schedules = raw.computeIfAbsent("schedule", k -> new java.util.ArrayList<>(1)) instanceof List<?> list
                    ? new java.util.ArrayList<>(list)
                    : new java.util.ArrayList<>();

            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            String id = "runtime-" + type.toLowerCase(java.util.Locale.ROOT) + "-"
                    + (schedules.size() + 1);
            entry.put("id", id);
            entry.put("type", type);
            String key = switch (type.toLowerCase(java.util.Locale.ROOT)) {
                case "daily" -> "time";
                case "interval" -> "interval";
                case "cron" -> "cron";
                case "dates" -> "dates";
                default -> "time";
            };
            entry.put(key, value);
            if (countdown != null && !countdown.isBlank()) {
                entry.put("countdown", countdown);
            }
            schedules.add(entry);
            raw.put("schedule", schedules);
            Files.writeString(configFile, toYaml(raw), StandardCharsets.UTF_8);
            return null;
        } catch (Exception e) {
            return List.of("Failed to add schedule: " + e.getMessage());
        }
    }

    /**
     * Removes a schedule by id and persists the file. Returns {@code null}
     * on success or the list of problems.
     */
    public List<String> removeSchedule(String id) {
        try {
            Map<String, Object> raw = readRaw(Files.readString(configFile, StandardCharsets.UTF_8));
            Object schedulesRaw = raw.get("schedule");
            if (!(schedulesRaw instanceof List<?> list)) {
                return List.of("No schedules configured.");
            }
            java.util.ArrayList<Object> schedules = new java.util.ArrayList<>(list);
            boolean removed = schedules.removeIf(item -> item instanceof Map<?, ?> map
                    && id.equals(String.valueOf(map.get("id"))));
            if (!removed) {
                return List.of("Unknown schedule id '" + id + "'.");
            }
            raw.put("schedule", schedules);
            Files.writeString(configFile, toYaml(raw), StandardCharsets.UTF_8);
            return null;
        } catch (Exception e) {
            return List.of("Failed to remove schedule: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readRaw(String yamlText) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object loaded = yaml.load(yamlText);
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new ConfigException(
                    "Configuration root must be a YAML mapping, got "
                            + loaded.getClass().getSimpleName());
        }
        return (Map<String, Object>) map;
    }

    static String toYaml(Map<String, Object> raw) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(new Representer(options)).dump(raw);
    }
}