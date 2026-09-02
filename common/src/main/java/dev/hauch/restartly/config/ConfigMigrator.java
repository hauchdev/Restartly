package dev.hauch.restartly.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Step-wise configuration migrations. A migration is a function that turns
 * a raw configuration {@code Map} from version N into version N+1.
 *
 * <p>Migrations run before validation, always against the raw document, and
 * the previous file is backed up by {@link ConfigManager} whenever the
 * version changed. There is currently exactly one schema version; new
 * migrations should be registered here following the existing pattern.</p>
 */
public final class ConfigMigrator {

    public static final int CURRENT_VERSION = ConfigLoader.CURRENT_VERSION;

    private ConfigMigrator() {
    }

    private interface Migration {
        Map<String, Object> apply(Map<String, Object> raw);
    }

    private static final SortedMap<Integer, Migration> MIGRATIONS = new TreeMap<>();

    static {
        // Future schema changes register here, e.g.:
        // MIGRATIONS.put(1, ConfigMigrator::migrateV1ToV2);
    }

    /**
     * Migrates a raw configuration map to the current version.
     *
     * @throws ConfigException when the document version is newer than this
     *                         build understands (downgrade protection).
     */
    public static Map<String, Object> migrate(Map<String, Object> raw) {
        int version = raw.get("version") instanceof Number n ? n.intValue() : 1;
        Map<String, Object> current = new LinkedHashMap<>(raw);

        if (version > CURRENT_VERSION) {
            throw new ConfigException("Configuration version " + version
                    + " is newer than supported version " + CURRENT_VERSION
                    + ". Update Restartly before using this file.");
        }
        if (version < 1) {
            throw new ConfigException("Configuration version " + version + " is invalid.");
        }

        for (Map.Entry<Integer, Migration> entry : MIGRATIONS.entrySet()) {
            if (entry.getKey() >= version) {
                current = entry.getValue().apply(current);
                current.put("version", entry.getKey() + 1);
            }
        }
        if (raw.get("version") == null) {
            current.put("version", CURRENT_VERSION);
        }
        return current;
    }

    /**
     * Reports whether running migrations would change the file (used to
     * decide whether a backup is required).
     */
    public static boolean wouldChange(Map<String, Object> raw) {
        int version = raw.get("version") instanceof Number n ? n.intValue() : 1;
        return version < CURRENT_VERSION;
    }

    static List<Integer> appliedMigrationVersions(Map<String, Object> raw) {
        int version = raw.get("version") instanceof Number n ? n.intValue() : 1;
        List<Integer> applied = new ArrayList<>();
        for (Integer key : MIGRATIONS.keySet()) {
            if (key >= version) {
                applied.add(key);
            }
        }
        return applied;
    }
}