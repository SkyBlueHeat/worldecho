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

    /**
     * Semantic transition key that groups observations representing the same
     * physical ownership transition, regardless of which event triggered them.
     *
     * <p>Keys are occurrence-specific so that the same entity acquiring the same
     * item from a later, different Item entity produces a different key:
     *
     * <ul>
     *   <li>WORLD_DROP (DROPPED, WORLD_DROP_OBSERVED, LOADED_ITEM, ENTITY_DEATH_DROP):
     *       {@code physical:world-drop:<tracked-item-id>:<item-entity-uuid>}
     *   <li>ENTITY pickup (ENTITY_HELD):
     *       {@code physical:entity-pickup:<tracked-item-id>:<source-item-entity-uuid>:<entity-uuid>}
     *   <li>ENTITY load recovery (LOADED_ENTITY_EQUIPMENT):
     *       {@code physical:entity-loaded:<tracked-item-id>:<entity-uuid>}
     *   <li>DESPAWN:
     *       {@code physical:despawn:<tracked-item-id>:<item-entity-uuid>}
     * </ul>
     *
     * <p>For example, {@code PlayerDropItemEvent} (reason DROPPED) and
     * {@code ItemSpawnEvent} (reason WORLD_DROP_OBSERVED) for the same Item
     * entity produce the same key, so the second observation is treated as an
     * idempotent replay rather than a duplicate transition.
     *
     * <p>Observation sequence remains available for ordering but does not make
     * the same physical transition semantically unique.
     */
    public String semanticTransitionKey() {
        return switch (observationReason) {
            case DROPPED, WORLD_DROP_OBSERVED, LOADED_ITEM, ENTITY_DEATH_DROP ->
                    "physical:world-drop:" + trackedItemId + ":" + observedSubject.stableId();
            case ENTITY_HELD ->
                    entityItemUuid != null
                            ? "physical:entity-pickup:" + trackedItemId + ":" + entityItemUuid + ":" + observedSubject.stableId()
                            : "physical:entity-loaded:" + trackedItemId + ":" + observedSubject.stableId();
            case LOADED_ENTITY_EQUIPMENT ->
                    "physical:entity-loaded:" + trackedItemId + ":" + observedSubject.stableId();
            case DESPAWNED ->
                    "physical:despawn:" + trackedItemId + ":" + (entityItemUuid != null ? entityItemUuid : observedSubject.stableId());
        };
    }
}
