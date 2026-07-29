package dev.worldecho.config;

import java.time.Duration;
import java.util.Set;

public record WorldEchoSettings(
        String locale,
        boolean playerDeathCaptureEnabled,
        boolean recordDeathWithoutValuableItem,
        int minimumItemScore,
        boolean redactCustomItemNames,
        Set<String> ignoredWorlds,
        String sqliteFile,
        Duration shutdownTimeout,
        int recentDefaultCount,
        int recentMaximumCount
) {

    public WorldEchoSettings {
        locale = normalizeLocale(locale);
        minimumItemScore = Math.max(0, minimumItemScore);
        ignoredWorlds = Set.copyOf(ignoredWorlds);
        sqliteFile = sqliteFile == null || sqliteFile.isBlank()
                ? "worldecho.db"
                : sqliteFile.trim();
        shutdownTimeout = shutdownTimeout == null
                ? Duration.ofSeconds(10)
                : shutdownTimeout;
        recentDefaultCount = Math.max(1, recentDefaultCount);
        recentMaximumCount = Math.max(recentDefaultCount, recentMaximumCount);
    }

    private static String normalizeLocale(String locale) {
        if (locale == null) {
            return "en";
        }
        return locale.trim().equalsIgnoreCase("tr") ? "tr" : "en";
    }
}
