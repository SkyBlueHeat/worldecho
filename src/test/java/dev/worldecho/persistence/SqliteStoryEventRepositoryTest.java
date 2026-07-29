package dev.worldecho.persistence;

import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.domain.memory.MemoryEventType;
import dev.worldecho.domain.memory.StoryMemoryEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteStoryEventRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesInsertsAndReadsRecentEvents() throws Exception {
        DatabaseManager database = new DatabaseManager(
                tempDir.resolve("worldecho-test.db")
        );
        database.initialize();

        StoryEventRepository repository =
                new SqliteStoryEventRepository(database);

        StoryMemoryEvent event = new StoryMemoryEvent(
                UUID.randomUUID(),
                MemoryEventType.PLAYER_KILLED_BY_ENTITY,
                Instant.parse("2026-07-29T11:00:00Z"),
                UUID.randomUUID(),
                12,
                64,
                -7,
                UUID.randomUUID(),
                new ContentKey("vanilla", "minecraft:zombie"),
                UUID.randomUUID().toString(),
                new ContentKey("vanilla", "minecraft:diamond_sword"),
                "material=minecraft:diamond_sword;score=45",
                "test=true"
        );

        repository.insert(event);

        assertEquals(1L, repository.count());
        List<StoryMemoryEvent> recent = repository.findRecent(10);
        assertEquals(1, recent.size());
        assertEquals(event.id(), recent.getFirst().id());
        assertEquals(event.actor(), recent.getFirst().actor());
        assertEquals(event.item(), recent.getFirst().item());
    }
}
