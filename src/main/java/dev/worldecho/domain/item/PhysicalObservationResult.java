package dev.worldecho.domain.item;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result of processing a physical unique item observation.
 */
public record PhysicalObservationResult(
        Status status,
        PhysicalUniqueItemObservation observation,
        OwnershipResult ownershipResult,
        String diagnostic
) {

    public enum Status {
        PROCESSED,
        NO_CHANGE,
        IDEMPOTENT_REPLAY,
        SKIPPED_LOT,
        SKIPPED_MALFORMED,
        SKIPPED_DUPLICATE,
        SKIPPED_STALE,
        SKIPPED_DISABLED,
        PERSISTENCE_FAILURE,
        ITEM_NOT_TRACKED
    }

    public PhysicalObservationResult {
        Objects.requireNonNull(status, "status");
        diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public static PhysicalObservationResult processed(PhysicalUniqueItemObservation observation, OwnershipResult result) {
        return new PhysicalObservationResult(Status.PROCESSED, observation, result, "");
    }

    public static PhysicalObservationResult noChange(PhysicalUniqueItemObservation observation) {
        return new PhysicalObservationResult(Status.NO_CHANGE, observation, null, "");
    }

    public static PhysicalObservationResult idempotentReplay(PhysicalUniqueItemObservation observation, OwnershipResult result) {
        return new PhysicalObservationResult(Status.IDEMPOTENT_REPLAY, observation, result, "");
    }

    public static PhysicalObservationResult skippedLot(PhysicalUniqueItemObservation observation) {
        return new PhysicalObservationResult(Status.SKIPPED_LOT, observation, null, "");
    }

    public static PhysicalObservationResult skippedMalformed(PhysicalUniqueItemObservation observation) {
        return new PhysicalObservationResult(Status.SKIPPED_MALFORMED, observation, null, "");
    }

    public static PhysicalObservationResult skippedDuplicate(PhysicalUniqueItemObservation observation) {
        return new PhysicalObservationResult(Status.SKIPPED_DUPLICATE, observation, null, "");
    }

    public static PhysicalObservationResult skippedStale(PhysicalUniqueItemObservation observation) {
        return new PhysicalObservationResult(Status.SKIPPED_STALE, observation, null,
                "Stale observation rejected: newer observation already recorded");
    }

    public static PhysicalObservationResult skippedDisabled(PhysicalUniqueItemObservation observation) {
        return new PhysicalObservationResult(Status.SKIPPED_DISABLED, observation, null, "");
    }

    public static PhysicalObservationResult persistenceFailure(PhysicalUniqueItemObservation observation, String error) {
        return new PhysicalObservationResult(Status.PERSISTENCE_FAILURE, observation, null, error);
    }

    public static PhysicalObservationResult itemNotTracked(PhysicalUniqueItemObservation observation) {
        return new PhysicalObservationResult(Status.ITEM_NOT_TRACKED, observation, null,
                "Item not tracked: " + observation.trackedItemId());
    }

    public Optional<OwnershipResult> optionalOwnershipResult() {
        return Optional.ofNullable(ownershipResult);
    }

    public boolean wasTransitioned() {
        return status == Status.PROCESSED && ownershipResult != null
                && ownershipResult.status() == OwnershipResultStatus.RECORDED;
    }
}
