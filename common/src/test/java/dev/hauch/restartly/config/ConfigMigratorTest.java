package dev.hauch.restartly.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigratorTest {

    private Map<String, Object> raw(int version) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", version);
        return map;
    }

    @Test
    void currentVersionPassesThrough() {
        Map<String, Object> migrated = ConfigMigrator.migrate(raw(1));
        assertEquals(1, migrated.get("version"));
    }

    @Test
    void missingVersionBecomesCurrent() {
        Map<String, Object> migrated = ConfigMigrator.migrate(new LinkedHashMap<>());
        assertEquals(ConfigMigrator.CURRENT_VERSION, migrated.get("version"));
    }

    @Test
    void newerVersionIsRejected() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigMigrator.migrate(raw(99)));
        assertTrue(e.getMessage().contains("newer"));
    }

    @Test
    void invalidVersionIsRejected() {
        assertThrows(ConfigException.class, () -> ConfigMigrator.migrate(raw(0)));
    }

    @Test
    void wouldChangeDetectsUpgrades() {
        assertTrue(ConfigMigrator.wouldChange(raw(0)));
        assertTrue(!ConfigMigrator.wouldChange(raw(1)));
    }
}