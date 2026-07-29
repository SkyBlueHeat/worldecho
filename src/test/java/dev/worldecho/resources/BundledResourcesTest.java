package dev.worldecho.resources;

import dev.worldecho.config.SettingsLoadResult;
import dev.worldecho.config.SettingsLoader;
import dev.worldecho.config.WorldEchoSettings;
import dev.worldecho.paper.config.BukkitConfigurationSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shipped resources: the documented config must produce exactly the built-in
 * defaults, and every English message must have a Turkish counterpart.
 */
class BundledResourcesTest {

    @Test
    void shippedConfigMatchesTheBuiltInDefaults() {
        SettingsLoadResult shipped = SettingsLoader.load(
                new BukkitConfigurationSource(read("config.yml")));

        assertFalse(shipped.hasWarnings(), () -> "config.yml warnings: " + shipped.warnings());
        assertEquals(WorldEchoSettings.defaults(), shipped.settings());
    }

    @Test
    void everyEnglishMessageHasATurkishTranslation() {
        Set<String> english = new TreeSet<>(read("messages_en.yml").getKeys(true));
        Set<String> turkish = new TreeSet<>(read("messages_tr.yml").getKeys(true));

        assertEquals(english, turkish);
        assertFalse(english.isEmpty());
    }

    @Test
    void messagesUsedByTheCommandExist() {
        YamlConfiguration english = read("messages_en.yml");

        for (String key : Set.of(
                "prefix", "no-permission", "players-only", "usage", "unknown-subcommand",
                "status-header", "status-line", "status-failed",
                "recent-loading", "recent-header", "recent-empty", "recent-line",
                "recent-failed", "recent-invalid-count",
                "inspect-usage", "inspect-header", "inspect-no-item", "inspect-no-entity",
                "reload-success", "reload-warning", "reload-failed")) {
            assertTrue(english.isString(key), () -> "missing message key: " + key);
        }
    }

    private static YamlConfiguration read(String resource) {
        try (InputStream stream = BundledResourcesTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(stream, () -> resource + " is not packaged");

            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (Exception exception) {
            throw new AssertionError("Could not read " + resource, exception);
        }
    }
}
