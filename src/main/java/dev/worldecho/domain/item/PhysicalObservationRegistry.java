package dev.worldecho.domain.item;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded in-memory registry for physical unique item observations.
 *
 * <p>Tracks the latest observation per tracked item ID to:
 * <ul>
 *   <li>Detect duplicate observations across conflicting subjects</li>
 *   <li>Reject stale observations that would revert newer ownership</li>
 * </ul>
 *
 * <p>Not a persistence truth source — the ownership ledger remains authoritative.
 * Stale observations expire safely.
 */
public final class PhysicalObservationRegistry {

    private final Map<TrackedItemId, RegisteredObservation> latestObservations = new ConcurrentHashMap<>();
    private final long expiryMillis;

    public PhysicalObservationRegistry(long expiryMillis) {
        this.expiryMillis = Math.max(1000, expiryMillis);
    }

    /**
     * Records an observation and returns a diagnostic if a duplicate or stale
     * conflict is detected.
     */
    public RegistrationResult register(PhysicalUniqueItemObservation observation) {
        Objects.requireNonNull(observation, "observation");
        expireStale();

        RegisteredObservation registered = new RegisteredObservation(
                observation.observedSubject(),
                observation.cycle().observationSequence(),
                observation.cycle().serverSessionId(),
                System.currentTimeMillis()
        );

        RegisteredObservation existing = latestObservations.putIfAbsent(
                observation.trackedItemId(), registered);

        if (existing == null) {
            return RegistrationResult.accepted();
        }

        long existingSeq = existing.observationSequence();
        long newSeq = observation.cycle().observationSequence();
        String existingSession = existing.serverSessionId();
        String newSession = observation.cycle().serverSessionId();

        boolean sameSession = existingSession.equals(newSession);

        if (sameSession && newSeq < existingSeq) {
            return RegistrationResult.stale();
        }

        if (sameSession && newSeq == existingSeq
                && existing.subject().equals(observation.observedSubject())) {
            return RegistrationResult.duplicate();
        }

        boolean subjectConflict = sameSession && newSeq == existingSeq
                && !existing.subject().equals(observation.observedSubject())
                && !isTerminalSubject(existing.subject())
                && !isTerminalSubject(observation.observedSubject());

        latestObservations.put(observation.trackedItemId(), registered);

        if (subjectConflict) {
            return RegistrationResult.conflict();
        }

        return RegistrationResult.accepted();
    }

    public void clear() {
        latestObservations.clear();
    }

    public int size() {
        return latestObservations.size();
    }

    private boolean isTerminalSubject(OwnershipSubject subject) {
        return subject.type() == OwnershipSubjectType.SYSTEM;
    }

    private void expireStale() {
        long now = System.currentTimeMillis();
        latestObservations.entrySet().removeIf(entry ->
                now - entry.getValue().observedTimeMillis() > expiryMillis);
    }

    private record RegisteredObservation(
            OwnershipSubject subject,
            long observationSequence,
            String serverSessionId,
            long observedTimeMillis
    ) {}

    public record RegistrationResult(Outcome outcome) {
        public enum Outcome {
            ACCEPTED,
            DUPLICATE,
            STALE,
            CONFLICT
        }

        public static RegistrationResult accepted() {
            return new RegistrationResult(Outcome.ACCEPTED);
        }

        public static RegistrationResult duplicate() {
            return new RegistrationResult(Outcome.DUPLICATE);
        }

        public static RegistrationResult stale() {
            return new RegistrationResult(Outcome.STALE);
        }

        public static RegistrationResult conflict() {
            return new RegistrationResult(Outcome.CONFLICT);
        }

        public boolean isAccepted() {
            return outcome == Outcome.ACCEPTED;
        }

        public boolean isStale() {
            return outcome == Outcome.STALE;
        }

        public boolean isDuplicate() {
            return outcome == Outcome.DUPLICATE;
        }

        public boolean isConflict() {
            return outcome == Outcome.CONFLICT;
        }
    }
}
