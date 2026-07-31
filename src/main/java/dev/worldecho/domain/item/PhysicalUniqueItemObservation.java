package dev.worldecho.domain.item;

import dev.worldecho.domain.content.ContentKey;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable snapshot of a physical observation of a UNIQUE tracked item.
 *
 * <p>Captured on the server thread from Bukkit entity/item state.
 * Safe to hand to asynchronous processing.  Contains no Bukkit objects.
 */
public record PhysicalUniqueItemObservation(
        TrackedItemId trackedItemId,
        ContentKey contentKey,
        String material,
        OwnershipSubject observedSubject,
        PhysicalObservationReason observationReason,
        UUID entityItemUuid,
        UUID worldUuid,
        String worldName,
        int blockX,
        int blockY,
        int blockZ,
        String contentFingerprint,
        PhysicalObservationCycle cycle,
        Instant observedAt
) {

    public PhysicalUniqueItemObservation {
        Objects.requireNonNull(trackedItemId, "trackedItemId");
        Objects.requireNonNull(observedSubject, "observedSubject");
        Objects.requireNonNull(observationReason, "observationReason");
        Objects.requireNonNull(cycle, "cycle");
        Objects.requireNonNull(observedAt, "observedAt");
        contentKey = contentKey == null ? ContentKey.parse("minecraft:unknown") : contentKey;
        material = material == null ? "" : material.strip();
        contentFingerprint = contentFingerprint == null ? "" : contentFingerprint.strip();
        worldName = worldName == null ? "" : worldName.strip();
    }

    public Optional<UUID> optionalEntityItemUuid() {
        return Optional.ofNullable(entityItemUuid);
    }

    public Optional<UUID> optionalWorldUuid() {
        return Optional.ofNullable(worldUuid);
    }

    /**
     * Deterministic idempotency key for this observation.
     *
     * <p>Based on session ID, observation sequence, item ID, subject, and reason.
     * Repeated processing of the same event produces the same key.
     */
    public String idempotencyKey() {
        return "phys:" + cycle.serverSessionId() + ":" + cycle.observationSequence()
                + ":item:" + trackedItemId
                + ":subject:" + observedSubject.describe()
                + ":reason:" + observationReason.token();
    }
}
