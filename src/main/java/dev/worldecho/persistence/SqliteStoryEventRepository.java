package dev.worldecho.persistence;

import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.domain.memory.MemoryEventType;
import dev.worldecho.domain.memory.StoryMemoryEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SqliteStoryEventRepository implements StoryEventRepository {

    private final DatabaseManager databaseManager;

    public SqliteStoryEventRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public void insert(StoryMemoryEvent event) throws SQLException {
        String sql = """
                INSERT INTO story_events (
                    id, event_type, occurred_at, world_id, x, y, z, player_id,
                    actor_provider, actor_content_id, actor_runtime_id,
                    item_provider, item_content_id, item_snapshot, details
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.id().toString());
            statement.setString(2, event.type().name());
            statement.setLong(3, event.occurredAt().toEpochMilli());
            statement.setString(4, event.worldId().toString());
            statement.setInt(5, event.x());
            statement.setInt(6, event.y());
            statement.setInt(7, event.z());
            statement.setString(8, event.playerId().toString());
            statement.setString(9, event.actor().providerId());
            statement.setString(10, event.actor().contentId());
            statement.setString(11, event.actorRuntimeId());

            if (event.item() == null) {
                statement.setNull(12, java.sql.Types.VARCHAR);
                statement.setNull(13, java.sql.Types.VARCHAR);
            } else {
                statement.setString(12, event.item().providerId());
                statement.setString(13, event.item().contentId());
            }

            statement.setString(14, event.itemSnapshot());
            statement.setString(15, event.details());
            statement.executeUpdate();
        }
    }

    @Override
    public List<StoryMemoryEvent> findRecent(int count) throws SQLException {
        String sql = """
                SELECT *
                FROM story_events
                ORDER BY occurred_at DESC
                LIMIT ?
                """;
        List<StoryMemoryEvent> result = new ArrayList<>();

        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, count));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
        }

        return List.copyOf(result);
    }

    @Override
    public long count() throws SQLException {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM story_events");
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private StoryMemoryEvent map(ResultSet rs) throws SQLException {
        String itemProvider = rs.getString("item_provider");
        String itemContentId = rs.getString("item_content_id");
        ContentKey item = itemProvider == null || itemContentId == null
                ? null
                : new ContentKey(itemProvider, itemContentId);

        return new StoryMemoryEvent(
                UUID.fromString(rs.getString("id")),
                MemoryEventType.valueOf(rs.getString("event_type")),
                Instant.ofEpochMilli(rs.getLong("occurred_at")),
                UUID.fromString(rs.getString("world_id")),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                UUID.fromString(rs.getString("player_id")),
                new ContentKey(
                        rs.getString("actor_provider"),
                        rs.getString("actor_content_id")
                ),
                rs.getString("actor_runtime_id"),
                item,
                rs.getString("item_snapshot"),
                rs.getString("details")
        );
    }
}
