package dev.worldecho.domain.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhysicalObservationReasonTest {

    @Test
    void tokenIsLowercaseName() {
        assertEquals("dropped", PhysicalObservationReason.DROPPED.token());
        assertEquals("world_drop_observed", PhysicalObservationReason.WORLD_DROP_OBSERVED.token());
        assertEquals("entity_held", PhysicalObservationReason.ENTITY_HELD.token());
        assertEquals("despawned", PhysicalObservationReason.DESPAWNED.token());
    }

    @Test
    void fromTokenRoundTrips() {
        for (PhysicalObservationReason reason : PhysicalObservationReason.values()) {
            assertEquals(reason, PhysicalObservationReason.fromToken(reason.token()));
        }
    }

    @Test
    void fromTokenIsCaseInsensitive() {
        assertEquals(PhysicalObservationReason.DROPPED,
                PhysicalObservationReason.fromToken("DROPPED"));
        assertEquals(PhysicalObservationReason.ENTITY_HELD,
                PhysicalObservationReason.fromToken("Entity_Held"));
    }

    @Test
    void fromTokenThrowsForUnknown() {
        assertThrows(IllegalArgumentException.class,
                () -> PhysicalObservationReason.fromToken("nonexistent"));
    }

    @Test
    void droppedMapsToDroppedTransitionReason() {
        assertEquals(OwnershipTransitionReason.DROPPED,
                PhysicalObservationReason.DROPPED.toTransitionReason());
    }

    @Test
    void worldDropObservedMapsToTransitionReason() {
        assertEquals(OwnershipTransitionReason.WORLD_DROP_OBSERVED,
                PhysicalObservationReason.WORLD_DROP_OBSERVED.toTransitionReason());
    }

    @Test
    void entityHeldMapsToEntityHeldTransitionReason() {
        assertEquals(OwnershipTransitionReason.ENTITY_HELD,
                PhysicalObservationReason.ENTITY_HELD.toTransitionReason());
    }

    @Test
    void despawnedMapsToDespawnedTransitionReason() {
        assertEquals(OwnershipTransitionReason.DESPAWNED,
                PhysicalObservationReason.DESPAWNED.toTransitionReason());
    }

    @Test
    void loadedItemMapsToWorldDropObserved() {
        assertEquals(OwnershipTransitionReason.WORLD_DROP_OBSERVED,
                PhysicalObservationReason.LOADED_ITEM.toTransitionReason());
    }

    @Test
    void loadedEntityEquipmentMapsToEntityHeld() {
        assertEquals(OwnershipTransitionReason.ENTITY_HELD,
                PhysicalObservationReason.LOADED_ENTITY_EQUIPMENT.toTransitionReason());
    }

    @Test
    void entityDeathDropMapsToWorldDropObserved() {
        assertEquals(OwnershipTransitionReason.WORLD_DROP_OBSERVED,
                PhysicalObservationReason.ENTITY_DEATH_DROP.toTransitionReason());
    }
}
