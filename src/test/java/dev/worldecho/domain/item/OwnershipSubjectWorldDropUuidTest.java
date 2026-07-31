package dev.worldecho.domain.item;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnershipSubjectWorldDropUuidTest {

    @Test
    void worldDropUuidCreatesValidWorldDropSubject() {
        UUID itemEntityUuid = UUID.randomUUID();
        OwnershipSubject subject = OwnershipSubject.worldDrop(itemEntityUuid);

        assertEquals(OwnershipSubjectType.WORLD_DROP, subject.type());
        assertEquals(itemEntityUuid.toString(), subject.stableId());
        assertTrue(subject.isValid());
    }

    @Test
    void worldDropUuidWithDisplayNamePreservesDisplayName() {
        UUID itemEntityUuid = UUID.randomUUID();
        OwnershipSubject subject = OwnershipSubject.worldDrop(itemEntityUuid, "Dropped Item");

        assertEquals(OwnershipSubjectType.WORLD_DROP, subject.type());
        assertEquals("Dropped Item", subject.displayName());
        assertEquals(itemEntityUuid.toString(), subject.stableId());
    }

    @Test
    void worldDropUuidStableIdIsLowercase() {
        UUID itemEntityUuid = UUID.fromString("AABBCCDD-1122-3344-5566-77889900AABB");
        OwnershipSubject subject = OwnershipSubject.worldDrop(itemEntityUuid);

        assertEquals(subject.stableId(), subject.stableId().toLowerCase());
    }

    @Test
    void worldDropUuidAndLocationBasedAreDifferentSubjects() {
        UUID itemEntityUuid = UUID.randomUUID();
        OwnershipSubject byUuid = OwnershipSubject.worldDrop(itemEntityUuid);
        OwnershipSubject byLocation = OwnershipSubject.worldDrop("world", 0, 64, 0);

        assertTrue(!byUuid.equals(byLocation));
        assertEquals(OwnershipSubjectType.WORLD_DROP, byUuid.type());
        assertEquals(OwnershipSubjectType.WORLD_DROP, byLocation.type());
    }
}
