package dev.worldecho.persistence;

import dev.worldecho.domain.memory.StoryMemoryEvent;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class StoryWriteQueue implements AutoCloseable {

    private final StoryEventRepository repository;
    private final Consumer<Throwable> errorHandler;
    private final ExecutorService executor;
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public StoryWriteQueue(
            StoryEventRepository repository,
            Consumer<Throwable> errorHandler
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform()
                        .name("worldecho-sqlite-writer")
                        .daemon(true)
                        .factory()
        );
    }

    public void submit(StoryMemoryEvent event) {
        queued.incrementAndGet();
        executor.submit(() -> {
            try {
                repository.insert(event);
                completed.incrementAndGet();
            } catch (SQLException exception) {
                failed.incrementAndGet();
                errorHandler.accept(exception);
            }
        });
    }

    public QueueStatus status() {
        return new QueueStatus(
                queued.get(),
                completed.get(),
                failed.get()
        );
    }

    public boolean shutdown(Duration timeout) {
        executor.shutdown();
        try {
            if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            executor.shutdownNow();
            return executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            return false;
        }
    }

    @Override
    public void close() {
        shutdown(Duration.ofSeconds(10));
    }

    public record QueueStatus(long submitted, long completed, long failed) {
        public long pending() {
            return Math.max(0L, submitted - completed - failed);
        }
    }
}
