package dev.worldecho.persistence;

import dev.worldecho.domain.memory.StoryMemoryEvent;

import java.sql.SQLException;
import java.util.List;

public interface StoryEventRepository {

    void insert(StoryMemoryEvent event) throws SQLException;

    List<StoryMemoryEvent> findRecent(int count) throws SQLException;

    long count() throws SQLException;
}
