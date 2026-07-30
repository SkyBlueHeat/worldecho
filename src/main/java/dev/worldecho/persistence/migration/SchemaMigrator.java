package dev.worldecho.persistence.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Applies versioned migrations inside a transaction and records what was applied.
 *
 * <p>Migrations only ever move forward and never drop recorded history, which keeps the
 * "never silently delete story history" rule enforceable at the schema level.</p>
 */
public final class SchemaMigrator {

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "story event memory kernel", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS story_events (
                        id TEXT PRIMARY KEY,
                        event_type TEXT NOT NULL,
                        occurred_at INTEGER NOT NULL,
                        world_id TEXT,
                        x INTEGER,
                        y INTEGER,
                        z INTEGER,
                        player_id TEXT,
                        actor_provider TEXT,
                        actor_content_id TEXT,
                        actor_runtime_id TEXT,
                        item_provider TEXT,
                        item_content_id TEXT,
                        item_snapshot TEXT,
                        details TEXT NOT NULL
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_story_events_occurred_at
                    ON story_events(occurred_at DESC)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_story_events_player_id
                    ON story_events(player_id)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_story_events_event_type
                    ON story_events(event_type)
                    """
            )),
            new Migration(2, "tracked item identity and ownership ledger", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS tracked_items (
                        item_id TEXT PRIMARY KEY,
                        created_at INTEGER NOT NULL,
                        first_seen_at INTEGER NOT NULL,
                        last_seen_at INTEGER NOT NULL,
                        content_key TEXT NOT NULL,
                        provider_id TEXT NOT NULL,
                        initial_material TEXT NOT NULL,
                        initial_custom_name TEXT NOT NULL DEFAULT '',
                        initial_value_score INTEGER,
                        tracking_reason TEXT NOT NULL DEFAULT '',
                        created_by_subject TEXT NOT NULL DEFAULT ''
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_tracked_items_content_key
                    ON tracked_items(content_key)
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS item_ownership_ledger (
                        entry_id TEXT PRIMARY KEY,
                        item_id TEXT NOT NULL,
                        sequence_number INTEGER NOT NULL,
                        previous_subject_type TEXT,
                        previous_subject_id TEXT,
                        previous_subject_display TEXT,
                        new_subject_type TEXT NOT NULL,
                        new_subject_id TEXT NOT NULL,
                        new_subject_display TEXT NOT NULL DEFAULT '',
                        transition_reason TEXT NOT NULL,
                        occurred_at INTEGER NOT NULL,
                        recorded_at INTEGER NOT NULL,
                        source TEXT NOT NULL DEFAULT '',
                        story_event_id TEXT NOT NULL DEFAULT '',
                        idempotency_key TEXT NOT NULL DEFAULT '',
                        notes TEXT NOT NULL DEFAULT '',
                        UNIQUE(item_id, sequence_number),
                        UNIQUE(item_id, idempotency_key),
                        FOREIGN KEY(item_id) REFERENCES tracked_items(item_id)
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_ledger_item_seq_desc
                    ON item_ownership_ledger(item_id, sequence_number DESC)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_ledger_new_subject
                    ON item_ownership_ledger(new_subject_type, new_subject_id)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_ledger_occurred_at
                    ON item_ownership_ledger(occurred_at DESC)
                    """
            )),
            new Migration(3, "automatic tracking lot identity and lineage", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS tracked_item_lots (
                        lot_id TEXT PRIMARY KEY,
                        created_at INTEGER NOT NULL,
                        first_seen_at INTEGER NOT NULL,
                        last_seen_at INTEGER NOT NULL,
                        content_key TEXT NOT NULL,
                        provider_id TEXT NOT NULL DEFAULT '',
                        material TEXT NOT NULL DEFAULT '',
                        fingerprint TEXT NOT NULL,
                        initial_amount INTEGER NOT NULL DEFAULT 0,
                        current_amount INTEGER NOT NULL DEFAULT 0,
                        tracking_reason TEXT NOT NULL DEFAULT '',
                        created_by_subject TEXT NOT NULL DEFAULT ''
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_tracked_lots_fingerprint
                    ON tracked_item_lots(fingerprint)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_tracked_lots_content_key
                    ON tracked_item_lots(content_key)
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS lot_lineage (
                        entry_id TEXT PRIMARY KEY,
                        lot_id TEXT NOT NULL,
                        related_lot_id TEXT NOT NULL,
                        relation_type TEXT NOT NULL,
                        lot_amount INTEGER NOT NULL DEFAULT 0,
                        related_amount INTEGER NOT NULL DEFAULT 0,
                        occurred_at INTEGER NOT NULL,
                        source TEXT NOT NULL DEFAULT '',
                        idempotency_key TEXT NOT NULL DEFAULT '',
                        UNIQUE(lot_id, idempotency_key),
                        FOREIGN KEY(lot_id) REFERENCES tracked_item_lots(lot_id)
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_lot_lineage_lot_id
                    ON lot_lineage(lot_id)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_lot_lineage_related_lot
                    ON lot_lineage(related_lot_id)
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS item_lot_ownership_ledger (
                        entry_id TEXT PRIMARY KEY,
                        lot_id TEXT NOT NULL,
                        sequence_number INTEGER NOT NULL,
                        previous_subject_type TEXT,
                        previous_subject_id TEXT,
                        previous_subject_display TEXT,
                        new_subject_type TEXT NOT NULL,
                        new_subject_id TEXT NOT NULL,
                        new_subject_display TEXT NOT NULL DEFAULT '',
                        transition_reason TEXT NOT NULL,
                        occurred_at INTEGER NOT NULL,
                        recorded_at INTEGER NOT NULL,
                        source TEXT NOT NULL DEFAULT '',
                        story_event_id TEXT NOT NULL DEFAULT '',
                        idempotency_key TEXT NOT NULL DEFAULT '',
                        notes TEXT NOT NULL DEFAULT '',
                        UNIQUE(lot_id, sequence_number),
                        UNIQUE(lot_id, idempotency_key),
                        FOREIGN KEY(lot_id) REFERENCES tracked_item_lots(lot_id)
                    )
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_lot_ledger_lot_seq_desc
                    ON item_lot_ownership_ledger(lot_id, sequence_number DESC)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS idx_lot_ledger_new_subject
                    ON item_lot_ownership_ledger(new_subject_type, new_subject_id)
                    """
            ))
    );

    private SchemaMigrator() {
    }

    public static List<Migration> migrations() {
        return MIGRATIONS;
    }

    public static int latestVersion() {
        return MIGRATIONS.stream().mapToInt(Migration::version).max().orElse(0);
    }

    /**
     * Brings the database up to {@link #latestVersion()}.
     *
     * @return the number of migrations that were applied by this call
     */
    public static int migrate(Connection connection) throws SQLException {
        createVersionTable(connection);
        int current = currentVersion(connection);
        int applied = 0;

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (Migration migration : MIGRATIONS) {
                if (migration.version() <= current) {
                    continue;
                }
                apply(connection, migration);
                applied++;
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }

        return applied;
    }

    public static int currentVersion(Connection connection) throws SQLException {
        createVersionTable(connection);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static void apply(Connection connection, Migration migration) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : migration.statements()) {
                statement.execute(sql);
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_version(version, description, applied_at) VALUES (?, ?, ?)")) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private static void createVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER PRIMARY KEY,
                        description TEXT NOT NULL,
                        applied_at INTEGER NOT NULL
                    )
                    """);
        }
    }
}
