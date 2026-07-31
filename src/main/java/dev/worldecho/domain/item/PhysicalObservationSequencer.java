package dev.worldecho.domain.item;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared plugin-lifetime sequencer for physical observations.
 *
 * <p>Provides globally monotonic observation sequences across all listeners.
 * A single instance is injected into every physical-observation listener so
 * that a later observation from one listener can never have a lower sequence
 * than an earlier observation from another listener.
 */
public final class PhysicalObservationSequencer {

    private final String serverSessionId;
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    public PhysicalObservationSequencer(String serverSessionId) {
        this.serverSessionId = Objects.requireNonNull(serverSessionId, "serverSessionId");
        if (serverSessionId.isBlank()) {
            throw new IllegalArgumentException("serverSessionId cannot be blank");
        }
    }

    /**
     * Returns the next observation cycle with a globally unique sequence.
     */
    public PhysicalObservationCycle nextCycle() {
        return PhysicalObservationCycle.create(
                sequenceCounter.incrementAndGet(),
                serverSessionId
        );
    }

    public String serverSessionId() {
        return serverSessionId;
    }

    public long currentSequence() {
        return sequenceCounter.get();
    }
}
