package dev.worldecho.domain.item;

import java.util.Objects;

/**
 * Stable identifier for a single physical observation cycle.
 *
 * <p>Used for deterministic idempotency keys and stale-observation ordering.
 * The {@code observationSequence} is monotonically increasing within a server
 * session — later observations have higher sequence numbers.
 */
public record PhysicalObservationCycle(
        long observationSequence,
        String serverSessionId
) {

    public PhysicalObservationCycle {
        serverSessionId = serverSessionId == null ? "" : serverSessionId.strip();
    }

    public static PhysicalObservationCycle create(long observationSequence, String serverSessionId) {
        return new PhysicalObservationCycle(observationSequence, serverSessionId);
    }
}
