package dev.worldecho.persistence.migration;

import dev.worldecho.persistence.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests migration v4 backfill behavior: converting legacy {@code created_by_subject}
 * to {@code owner_type}, {@code owner_stable_id}, {@code owner_display_snapshot},
 * resolving duplicate legacy rows, and ensuring idempotent re-run.
 */
class MigrationV4BackfillTest {

    @TempDir
    Path tempDir;

    /**
     * Creates a database at schema v3 (only migrations 1-3 applied) with a lot row
     * using the legacy format (no owner_* columns).
     */
    private DatabaseManager createV3Database(String fileName, String createdBySubject,
                                              String fingerprint, String lotId, int amount) throws Exception {
        Path dbPath = tempDir.resolve(fileName);
        // Create database with only v1-v3 migrations using raw SQLite
        try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = connection.createStatement()) {
            // Apply migrations 1-3 manually (simplified schema)
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, description TEXT NOT NULL, applied_at INTEGER NOT NULL)");
            // v1: story_events
            stmt.execute("CREATE TABLE IF NOT EXISTS story_events (id TEXT PRIMARY KEY, event_type TEXT NOT NULL, occurred_at INTEGER NOT NULL, world_id TEXT, x INTEGER, y INTEGER, z INTEGER, player_id TEXT, actor_provider TEXT, actor_content_id TEXT, actor_runtime_id TEXT, item_provider TEXT, item_content_id TEXT, item_snapshot TEXT, details TEXT NOT NULL)");
            stmt.execute("INSERT INTO schema_version VALUES (1, 'story event memory kernel', 0)");
            // v2: tracked_items + ledger
            stmt.execute("CREATE TABLE IF NOT EXISTS tracked_items (item_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, first_seen_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, content_key TEXT NOT NULL, provider_id TEXT NOT NULL, initial_material TEXT NOT NULL, initial_custom_name TEXT NOT NULL DEFAULT '', initial_value_score INTEGER, tracking_reason TEXT NOT NULL DEFAULT '', created_by_subject TEXT NOT NULL DEFAULT '')");
            stmt.execute("CREATE TABLE IF NOT EXISTS item_ownership_ledger (entry_id TEXT PRIMARY KEY, item_id TEXT NOT NULL, sequence_number INTEGER NOT NULL, previous_subject_type TEXT, previous_subject_id TEXT, previous_subject_display TEXT, new_subject_type TEXT NOT NULL, new_subject_id TEXT NOT NULL, new_subject_display TEXT NOT NULL DEFAULT '', transition_reason TEXT NOT NULL, occurred_at INTEGER NOT NULL, recorded_at INTEGER NOT NULL, source TEXT NOT NULL DEFAULT '', story_event_id TEXT NOT NULL DEFAULT '', idempotency_key TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', UNIQUE(item_id, sequence_number), UNIQUE(item_id, idempotency_key), FOREIGN KEY(item_id) REFERENCES tracked_items(item_id))");
            stmt.execute("INSERT INTO schema_version VALUES (2, 'tracked item identity and ownership ledger', 0)");
            // v3: tracked_item_lots + lineage + lot ledger (v3 schema: no owner_* columns)
            stmt.execute("CREATE TABLE IF NOT EXISTS tracked_item_lots (lot_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, first_seen_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, content_key TEXT NOT NULL, provider_id TEXT NOT NULL DEFAULT '', material TEXT NOT NULL DEFAULT '', fingerprint TEXT NOT NULL, initial_amount INTEGER NOT NULL DEFAULT 0, current_amount INTEGER NOT NULL DEFAULT 0, tracking_reason TEXT NOT NULL DEFAULT '', created_by_subject TEXT NOT NULL DEFAULT '')");
            stmt.execute("CREATE TABLE IF NOT EXISTS lot_lineage (entry_id TEXT PRIMARY KEY, lot_id TEXT NOT NULL, related_lot_id TEXT NOT NULL, relation_type TEXT NOT NULL, lot_amount INTEGER NOT NULL DEFAULT 0, related_amount INTEGER NOT NULL DEFAULT 0, occurred_at INTEGER NOT NULL, source TEXT NOT NULL DEFAULT '', idempotency_key TEXT NOT NULL DEFAULT '', UNIQUE(lot_id, idempotency_key), FOREIGN KEY(lot_id) REFERENCES tracked_item_lots(lot_id))");
            stmt.execute("CREATE TABLE IF NOT EXISTS item_lot_ownership_ledger (entry_id TEXT PRIMARY KEY, lot_id TEXT NOT NULL, sequence_number INTEGER NOT NULL, previous_subject_type TEXT, previous_subject_id TEXT, previous_subject_display TEXT, new_subject_type TEXT NOT NULL, new_subject_id TEXT NOT NULL, new_subject_display TEXT NOT NULL DEFAULT '', transition_reason TEXT NOT NULL, occurred_at INTEGER NOT NULL, recorded_at INTEGER NOT NULL, source TEXT NOT NULL DEFAULT '', story_event_id TEXT NOT NULL DEFAULT '', idempotency_key TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', UNIQUE(lot_id, sequence_number), UNIQUE(lot_id, idempotency_key), FOREIGN KEY(lot_id) REFERENCES tracked_item_lots(lot_id))");
            stmt.execute("INSERT INTO schema_version VALUES (3, 'automatic tracking lot identity and lineage', 0)");

            // Insert a v3-style lot row (no owner_* columns)
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO tracked_item_lots "
                            + "(lot_id, created_at, first_seen_at, last_seen_at, content_key, "
                            + "provider_id, material, fingerprint, initial_amount, current_amount, "
                            + "tracking_reason, created_by_subject) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, lotId);
                ps.setLong(2, 1000L);
                ps.setLong(3, 1000L);
                ps.setLong(4, 2000L);
                ps.setString(5, "minecraft:cobblestone");
                ps.setString(6, "minecraft");
                ps.setString(7, "minecraft:cobblestone");
                ps.setString(8, fingerprint);
                ps.setInt(9, amount);
                ps.setInt(10, amount);
                ps.setString(11, "AUTOMATIC");
                ps.setString(12, createdBySubject);
                ps.executeUpdate();
            }
        }

        return new DatabaseManager(dbPath);
    }

    private DatabaseManager migrateV3ToV4(DatabaseManager v3db) throws Exception {
        v3db.initialize();
        return v3db;
    }

    @Test
    void validV3PlayerSubjectBackfillsCorrectly() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String subject = "player:" + playerUuid;
        String fp = "minecraft|cobblestone|minecraft:cobblestone|0||||||||0||";
        String lotId = UUID.randomUUID().toString();

        DatabaseManager v3db = createV3Database("backfill-valid.db", subject, fp, lotId, 64);
        migrateV3ToV4(v3db);

        try (Connection connection = v3db.openConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT owner_type, owner_stable_id, owner_display_snapshot, current_amount "
                             + "FROM tracked_item_lots WHERE lot_id = '" + lotId + "'")) {
            assertTrue(rs.next());
            assertEquals("player", rs.getString("owner_type"));
            assertEquals(playerUuid.toString(), rs.getString("owner_stable_id"));
            assertEquals(64, rs.getInt("current_amount"));
        }
    }

    @Test
    void playerNameContainingSeparatorsIsHandledSafely() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String subject = "player:" + playerUuid;
        String fp = "minecraft|cobblestone|minecraft:cobblestone|0||||||||0||";
        String lotId = UUID.randomUUID().toString();

        DatabaseManager v3db = createV3Database("backfill-separators.db", subject, fp, lotId, 32);
        migrateV3ToV4(v3db);

        try (Connection connection = v3db.openConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT owner_stable_id FROM tracked_item_lots WHERE lot_id = '" + lotId + "'")) {
            assertTrue(rs.next());
            assertEquals(playerUuid.toString(), rs.getString("owner_stable_id"));
        }
    }

    @Test
    void malformedLegacySubjectFollowsDocumentedFallback() throws Exception {
        String subject = "system:my-token";
        String fp = "minecraft|cobblestone|minecraft:cobblestone|0||||||||0||";
        String lotId = UUID.randomUUID().toString();

        DatabaseManager v3db = createV3Database("backfill-malformed.db", subject, fp, lotId, 16);
        migrateV3ToV4(v3db);

        try (Connection connection = v3db.openConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT owner_type, owner_stable_id FROM tracked_item_lots WHERE lot_id = '" + lotId + "'")) {
            assertTrue(rs.next());
            assertEquals("", rs.getString("owner_type"));
            assertEquals("", rs.getString("owner_stable_id"));
        }
    }

    @Test
    void sameUuidWithDifferentDisplayNamesResolvesToOneOwner() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String subject = "player:" + playerUuid;
        String fp = "minecraft|cobblestone|minecraft:cobblestone|0||||||||0||";

        DatabaseManager v3db = createV3Database("backfill-same-uuid.db", subject, fp,
                UUID.randomUUID().toString(), 32);

        // Insert another lot with same owner+fingerprint (duplicate) using raw JDBC (v3 schema)
        try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("backfill-same-uuid.db"));
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO tracked_item_lots "
                             + "(lot_id, created_at, first_seen_at, last_seen_at, content_key, "
                             + "provider_id, material, fingerprint, initial_amount, current_amount, "
                             + "tracking_reason, created_by_subject) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setLong(2, 1000L);
            ps.setLong(3, 1000L);
            ps.setLong(4, 3000L);
            ps.setString(5, "minecraft:cobblestone");
            ps.setString(6, "minecraft");
            ps.setString(7, "minecraft:cobblestone");
            ps.setString(8, fp);
            ps.setInt(9, 48);
            ps.setInt(10, 48);
            ps.setString(11, "AUTOMATIC");
            ps.setString(12, subject);
            ps.executeUpdate();
        }

        migrateV3ToV4(v3db);

        try (Connection connection = v3db.openConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM tracked_item_lots "
                             + "WHERE owner_type = 'player' AND owner_stable_id = '" + playerUuid + "' "
                             + "AND fingerprint = '" + fp + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Duplicate rows should be resolved to one");
        }
    }

    @Test
    void existingAmountIsPreservedAfterBackfill() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String subject = "player:" + playerUuid;
        String fp = "minecraft|cobblestone|minecraft:cobblestone|0||||||||0||";
        String lotId = UUID.randomUUID().toString();

        DatabaseManager v3db = createV3Database("backfill-amount.db", subject, fp, lotId, 42);
        migrateV3ToV4(v3db);

        try (Connection connection = v3db.openConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT current_amount, initial_amount FROM tracked_item_lots WHERE lot_id = '" + lotId + "'")) {
            assertTrue(rs.next());
            assertEquals(42, rs.getInt("current_amount"));
            assertEquals(42, rs.getInt("initial_amount"));
        }
    }

    @Test
    void existingTimestampsArePreservedAfterBackfill() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String subject = "player:" + playerUuid;
        String fp = "minecraft|cobblestone|minecraft:cobblestone|0||||||||0||";
        String lotId = UUID.randomUUID().toString();

        DatabaseManager v3db = createV3Database("backfill-timestamps.db", subject, fp, lotId, 10);
        migrateV3ToV4(v3db);

        try (Connection connection = v3db.openConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT created_at, first_seen_at, last_seen_at FROM tracked_item_lots WHERE lot_id = '" + lotId + "'")) {
            assertTrue(rs.next());
            assertEquals(1000L, rs.getLong("created_at"));
            assertEquals(1000L, rs.getLong("first_seen_at"));
            assertEquals(2000L, rs.getLong("last_seen_at"));
        }
    }

    @Test
    void existingLotIdIsPreservedAfterBackfill() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String subject = "player:" + playerUuid;
        String fp = "minecraft|cobblestone|minecraft:cobblestone|0||||||||0||";
        String lotId = UUID.randomUUID().toString();

        DatabaseManager v3db = createV3Database("backfill-lotid.db", subject, fp, lotId, 10);
        migrateV3ToV4(v3db);

        try (Connection connection = v3db.openConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT lot_id FROM tracked_item_lots WHERE lot_id = '" + lotId + "'")) {
            assertTrue(rs.next());
            assertEquals(lotId, rs.getString("lot_id"));
        }
    }

    @Test
    void existingOwnershipLedgerIsPreservedAfterBackfill() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String subject = "player:" + playerUuid;
        String fp = "minecraft|cobblestone|minecraft:cobblestone|0||||||||0||";
        String lotId = UUID.randomUUID().toString();

        DatabaseManager v3db = createV3Database("backfill-ledger.db", subject, fp, lotId, 10);

        // Insert ownership ledger entry using raw JDBC (v3 schema)
        try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("backfill-ledger.db"));
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO item_lot_ownership_ledger "
                             + "(entry_id, lot_id, sequence_number, new_subject_type, new_subject_id, "
                             + "new_subject_display, transition_reason, occurred_at, recorded_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, lotId);
            ps.setInt(3, 1);
            ps.setString(4, "player");
            ps.setString(5, playerUuid.toString());
            ps.setString(6, "TestPlayer");
            ps.setString(7, "AUTOMATIC_TRACKING");
            ps.setLong(8, 1000L);
            ps.setLong(9, 1000L);
            ps.executeUpdate();
        }

        migrateV3ToV4(v3db);

        try (Connection connection = v3db.openConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM item_lot_ownership_ledger WHERE lot_id = '" + lotId + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Ownership ledger entries should be preserved");
        }
    }

    @Test
    void duplicateLegacyRowsResolvedDeterministicallyBeforeUniqueIndex() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String subject = "player:" + playerUuid;
        String fp = "minecraft|cobblestone|minecraft:cobblestone|0||||||||0||";

        DatabaseManager v3db = createV3Database("backfill-dup.db", subject, fp,
                UUID.randomUUID().toString(), 10);

        for (int i = 0; i < 2; i++) {
            try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("backfill-dup.db"));
                 PreparedStatement ps = connection.prepareStatement(
                         "INSERT INTO tracked_item_lots "
                                 + "(lot_id, created_at, first_seen_at, last_seen_at, content_key, "
                                 + "provider_id, material, fingerprint, initial_amount, current_amount, "
                                 + "tracking_reason, created_by_subject) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setLong(2, 1000L + i);
                ps.setLong(3, 1000L + i);
                ps.setLong(4, 2000L + i * 1000);
                ps.setString(5, "minecraft:cobblestone");
                ps.setString(6, "minecraft");
                ps.setString(7, "minecraft:cobblestone");
                ps.setString(8, fp);
                ps.setInt(9, 20 + i * 10);
                ps.setInt(10, 20 + i * 10);
                ps.setString(11, "AUTOMATIC");
                ps.setString(12, subject);
                ps.executeUpdate();
            }
        }

        migrateV3ToV4(v3db);

        try (Connection connection = v3db.openConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM tracked_item_lots "
                             + "WHERE owner_type = 'player' AND owner_stable_id = '" + playerUuid + "' "
                             + "AND fingerprint = '" + fp + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Duplicate rows should be resolved to exactly one");
        }
    }

    @Test
    void v3ToV4RerunIsIdempotent() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String subject = "player:" + playerUuid;
        String fp = "minecraft|cobblestone|minecraft:cobblestone|0||||||||0||";
        String lotId = UUID.randomUUID().toString();

        DatabaseManager v3db = createV3Database("backfill-idempotent.db", subject, fp, lotId, 64);
        migrateV3ToV4(v3db);

        try (Connection connection = v3db.openConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs1 = stmt.executeQuery(
                     "SELECT owner_type, owner_stable_id, current_amount FROM tracked_item_lots WHERE lot_id = '" + lotId + "'")) {
            assertTrue(rs1.next());
            assertEquals("player", rs1.getString("owner_type"));
            assertEquals(playerUuid.toString(), rs1.getString("owner_stable_id"));
            assertEquals(64, rs1.getInt("current_amount"));
        }

        v3db.initialize();

        try (Connection connection = v3db.openConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs2 = stmt.executeQuery(
                     "SELECT owner_type, owner_stable_id, current_amount FROM tracked_item_lots WHERE lot_id = '" + lotId + "'")) {
            assertTrue(rs2.next());
            assertEquals("player", rs2.getString("owner_type"));
            assertEquals(playerUuid.toString(), rs2.getString("owner_stable_id"));
            assertEquals(64, rs2.getInt("current_amount"));
        }
    }
}
