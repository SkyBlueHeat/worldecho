package dev.worldecho.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

public final class SettingsLoader {

    private SettingsLoader() {
    }

    public static WorldEchoSettings load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();

        Set<String> ignoredWorlds = new HashSet<>(
                config.getStringList("capture.player-deaths.ignored-worlds")
        );

        return new WorldEchoSettings(
                config.getString("locale", "en"),
                config.getBoolean("capture.player-deaths.enabled", true),
                config.getBoolean(
                        "capture.player-deaths.record-without-valuable-item",
                        true
                ),
                config.getInt("capture.player-deaths.minimum-item-score", 25),
                config.getBoolean(
                        "capture.player-deaths.redact-custom-item-names",
                        false
                ),
                ignoredWorlds,
                config.getString("persistence.sqlite-file", "worldecho.db"),
                Duration.ofSeconds(
                        Math.max(
                                1,
                                config.getLong(
                                        "persistence.shutdown-timeout-seconds",
                                        10
                                )
                        )
                ),
                config.getInt("commands.recent-default-count", 10),
                config.getInt("commands.recent-maximum-count", 50)
        );
    }
}
