package dev.worldecho.application;

import dev.worldecho.domain.memory.StoryMemoryEvent;
import dev.worldecho.persistence.StoryWriteQueue;

import java.util.Objects;

public final class MemoryRecorder {

    private final StoryWriteQueue writeQueue;

    public MemoryRecorder(StoryWriteQueue writeQueue) {
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    public void record(StoryMemoryEvent event) {
        writeQueue.submit(event);
    }
}
