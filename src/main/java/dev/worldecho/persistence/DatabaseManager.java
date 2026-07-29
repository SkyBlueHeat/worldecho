package dev.worldecho.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {

    private final String jdbcUrl;

    public DatabaseManager(Path databasePath) {
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
    }

    public void initialize() throws SQLException, IOException {
        Path path = Path.of(jdbcUrl.substring("jdbc:sqlite:".length()));
        Files.createDirectories(path.getParent());

        try (Connection connection = openConnection()) {
            configure(connection);
            migrate(connection);
        }
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        configure(connection);
        return connection;
    }

    private void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO schema_version(version)
                    SELECT 0
                    WHERE NOT EXISTS (SELECT 1 FROM schema_version)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS story_events (
                        id TEXT PRIMARY KEY,
                        event_type TEXT NOT NULL,
                        occurred_at INTEGER NOT NULL,
                        world_id TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        player_id TEXT NOT NULL,
                        actor_provider TEXT NOT NULL,
                        actor_content_id TEXT NOT NULL,
                        actor_runtime_id TEXT NOT NULL,
                        item_provider TEXT,
                        item_content_id TEXT,
                        item_snapshot TEXT NOT NULL,
                        details TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_story_events_occurred_at
                    ON story_events(occurred_at DESC)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_story_events_player_id
                    ON story_events(player_id)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_story_events_type
                    ON story_events(event_type)
                    """);
            statement.execute("UPDATE schema_version SET version = 1");
        }
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }
}
