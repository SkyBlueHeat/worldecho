package dev.worldecho.domain.item;

import java.util.Locale;
import java.util.Objects;

/**
 * Stable machine-readable reason for a physical ownership observation.
 *
 * <p>Distinct from {@link OwnershipTransitionReason} which records the persisted
 * transition reason.  Physical observation reasons describe the event source
 * that triggered the observation.
 */
public enum PhysicalObservationReason {
    DROPPED,
    WORLD_DROP_OBSERVED,
    ENTITY_HELD,
    ENTITY_DEATH_DROP,
    DESPAWNED,
    LOADED_ITEM,
    LOADED_ENTITY_EQUIPMENT;

    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PhysicalObservationReason fromToken(String token) {
        Objects.requireNonNull(token, "token");
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        for (PhysicalObservationReason reason : values()) {
            if (reason.token().equals(normalized)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("Unknown physical observation reason: " + token);
    }

    /**
     * Maps this physical observation reason to the persisted ownership transition reason.
     */
    public OwnershipTransitionReason toTransitionReason() {
        return switch (this) {
            case DROPPED -> OwnershipTransitionReason.DROPPED;
            case WORLD_DROP_OBSERVED -> OwnershipTransitionReason.WORLD_DROP_OBSERVED;
            case ENTITY_HELD -> OwnershipTransitionReason.ENTITY_HELD;
            case ENTITY_DEATH_DROP -> OwnershipTransitionReason.WORLD_DROP_OBSERVED;
            case DESPAWNED -> OwnershipTransitionReason.DESPAWNED;
            case LOADED_ITEM -> OwnershipTransitionReason.WORLD_DROP_OBSERVED;
            case LOADED_ENTITY_EQUIPMENT -> OwnershipTransitionReason.ENTITY_HELD;
        };
    }
}
