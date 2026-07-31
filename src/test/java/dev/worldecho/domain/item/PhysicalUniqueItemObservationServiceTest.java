package dev.worldecho.domain.item;

import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.persistence.OwnershipLedgerRepository;
import dev.worldecho.persistence.TrackedItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalUniqueItemObservationServiceTest {

    private FakeTrackedItemRepository trackedItemRepository;
    private FakeLedgerRepository ledgerRepository;
    private OwnershipTransitionService ownershipTransitionService;
    private PhysicalObservationRegistry observationRegistry;
    private ReconciliationMetrics metrics;
    private PhysicalUniqueItemObservationService service;

    @BeforeEach
    void setUp() {
        trackedItemRepository = new FakeTrackedItemRepository();
        ledgerRepository = new FakeLedgerRepository();
        ownershipTransitionService = new OwnershipTransitionService(
                trackedItemRepository, ledgerRepository,
                Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneId.of("UTC"))
        );
        observationRegistry = new PhysicalObservationRegistry(60_000L);
        metrics = new ReconciliationMetrics();
        service = new PhysicalUniqueItemObservationService(
                trackedItemRepository,
                ownershipTransitionService,
                observationRegistry,
                metrics,
                Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneId.of("UTC"))
        );
    }

    @Test
    void processCreatesTrackedItemAndRecordsTransition() {
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());

        PhysicalUniqueItemObservation obs = buildObservation(itemId, worldDrop,
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 1);

        PhysicalObservationResult result = service.process(obs);

        assertEquals(PhysicalObservationResult.Status.PROCESSED, result.status());
        assertEquals(OwnershipResultStatus.RECORDED, result.ownershipResult().status());
        assertTrue(trackedItemRepository.exists(itemId));
        assertEquals(1, metrics.physicalOwnershipTransitions());
    }

    @Test
    void processWithExistingTrackedItemObservesAndTransitions() {
        TrackedItemId itemId = trackedItemRepository.createTracked();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());

        PhysicalUniqueItemObservation obs = buildObservation(itemId, worldDrop,
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 1);

        PhysicalObservationResult result = service.process(obs);

        assertEquals(PhysicalObservationResult.Status.PROCESSED, result.status());
    }

    @Test
    void duplicateObservationIsSkipped() {
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());

        PhysicalUniqueItemObservation obs = buildObservation(itemId, worldDrop,
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 1);

        service.process(obs);
        PhysicalObservationResult result = service.process(obs);

        assertEquals(PhysicalObservationResult.Status.SKIPPED_DUPLICATE, result.status());
    }

    @Test
    void staleObservationIsRejected() {
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());
        OwnershipSubject entity = OwnershipSubject.entity(UUID.randomUUID());

        service.process(buildObservation(itemId, worldDrop,
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 5));
        PhysicalObservationResult result = service.process(buildObservation(itemId, entity,
                PhysicalObservationReason.ENTITY_HELD, 3));

        assertEquals(PhysicalObservationResult.Status.SKIPPED_STALE, result.status());
        assertEquals(1, metrics.staleObservationsRejected());
    }

    @Test
    void noChangeWhenSameSubjectAlreadyCurrent() {
        TrackedItemId itemId = trackedItemRepository.createTracked();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());

        service.process(buildObservation(itemId, worldDrop,
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 1));
        PhysicalObservationResult result = service.process(buildObservation(itemId, worldDrop,
                PhysicalObservationReason.LOADED_ITEM, 2));

        assertEquals(PhysicalObservationResult.Status.IDEMPOTENT_REPLAY, result.status());
    }

    @Test
    void despawnedTransitionRecordsSystemSubject() {
        TrackedItemId itemId = trackedItemRepository.createTracked();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());

        service.process(buildObservation(itemId, worldDrop,
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 1));

        OwnershipSubject system = OwnershipSubject.system("item-despawned");
        PhysicalObservationResult result = service.process(buildObservation(itemId, system,
                PhysicalObservationReason.DESPAWNED, 2));

        assertEquals(PhysicalObservationResult.Status.PROCESSED, result.status());
        assertEquals(OwnershipResultStatus.RECORDED, result.ownershipResult().status());
    }

    @Test
    void entityHeldTransitionIsRecorded() {
        TrackedItemId itemId = trackedItemRepository.createTracked();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());
        OwnershipSubject entity = OwnershipSubject.entity(UUID.randomUUID());

        service.process(buildObservation(itemId, worldDrop,
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 1));
        PhysicalObservationResult result = service.process(buildObservation(itemId, entity,
                PhysicalObservationReason.ENTITY_HELD, 2));

        assertEquals(PhysicalObservationResult.Status.PROCESSED, result.status());
        assertEquals(OwnershipResultStatus.RECORDED, result.ownershipResult().status());
    }

    @Test
    void idempotentReplayReturnsIdempotentResult() {
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());

        PhysicalObservationCycle cycle = PhysicalObservationCycle.create(1, "session-1");
        PhysicalUniqueItemObservation obs = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                worldDrop, PhysicalObservationReason.WORLD_DROP_OBSERVED,
                UUID.randomUUID(), UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1", cycle, Instant.now());

        service.process(obs);
        PhysicalObservationResult result = service.process(obs);

        assertEquals(PhysicalObservationResult.Status.SKIPPED_DUPLICATE, result.status());
    }

    @Test
    void wasTransitionedTrueForRecordedResult() {
        TrackedItemId itemId = TrackedItemId.random();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());

        PhysicalUniqueItemObservation obs = buildObservation(itemId, worldDrop,
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 1);
        PhysicalObservationResult result = service.process(obs);

        assertTrue(result.wasTransitioned());
    }

    @Test
    void wasTransitionedFalseForNoChange() {
        TrackedItemId itemId = trackedItemRepository.createTracked();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());

        service.process(buildObservation(itemId, worldDrop,
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 1));
        PhysicalObservationResult result = service.process(buildObservation(itemId, worldDrop,
                PhysicalObservationReason.LOADED_ITEM, 2));

        assertTrue(!result.wasTransitioned());
    }

    @Test
    void conflictIncrementsPhysicalObservationWarning() {
        TrackedItemId itemId = trackedItemRepository.createTracked();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(UUID.randomUUID());
        OwnershipSubject entity = OwnershipSubject.entity(UUID.randomUUID());

        PhysicalUniqueItemObservation obs1 = buildObservation(itemId, worldDrop,
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 1);
        service.process(obs1);

        PhysicalUniqueItemObservation obs2 = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                entity, PhysicalObservationReason.ENTITY_HELD,
                UUID.randomUUID(), UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(1, "session-1"), Instant.now());
        PhysicalObservationResult result = service.process(obs2);

        assertEquals(PhysicalObservationResult.Status.SKIPPED_DUPLICATE, result.status());
        assertEquals(1, metrics.physicalObservationWarnings());
    }

    private PhysicalUniqueItemObservation buildObservation(
            TrackedItemId itemId, OwnershipSubject subject,
            PhysicalObservationReason reason, long sequence
    ) {
        return new PhysicalUniqueItemObservation(
                itemId,
                ContentKey.parse("minecraft:diamond_sword"),
                "diamond_sword",
                subject,
                reason,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "world",
                0, 64, 0,
                "diamond_sword:1",
                PhysicalObservationCycle.create(sequence, "session-1"),
                Instant.now()
        );
    }

    private static class FakeTrackedItemRepository implements TrackedItemRepository {
        private final HashSet<TrackedItemId> existing = new HashSet<>();

        @Override
        public CreateResult create(TrackedItemRecord record) {
            return existing.add(record.itemId()) ? CreateResult.CREATED : CreateResult.ALREADY_EXISTS;
        }

        @Override
        public Optional<TrackedItemRecord> findById(TrackedItemId itemId) {
            return Optional.empty();
        }

        @Override
        public boolean exists(TrackedItemId itemId) {
            return existing.contains(itemId);
        }

        @Override
        public void observe(TrackedItemId itemId, long lastSeenEpochMilli) {
        }

        @Override
        public long count() {
            return existing.size();
        }

        TrackedItemId createTracked() {
            TrackedItemId id = TrackedItemId.random();
            existing.add(id);
            return id;
        }
    }

    private static class FakeLedgerRepository implements OwnershipLedgerRepository {
        private final Map<String, OwnershipLedgerEntry> byIdempotencyKey = new HashMap<>();
        private final List<OwnershipLedgerEntry> entries = new ArrayList<>();

        @Override
        public AppendResult append(OwnershipLedgerEntry entry) {
            if (entry.hasIdempotencyKey() && byIdempotencyKey.containsKey(entry.itemId() + ":" + entry.idempotencyKey())) {
                return AppendResult.IDEMPOTENT_REPLAY;
            }
            for (OwnershipLedgerEntry existing : entries) {
                if (existing.itemId().equals(entry.itemId())
                        && existing.sequenceNumber() == entry.sequenceNumber()) {
                    return AppendResult.CONFLICT;
                }
            }
            entries.add(entry);
            if (entry.hasIdempotencyKey()) {
                byIdempotencyKey.put(entry.itemId() + ":" + entry.idempotencyKey(), entry);
            }
            return AppendResult.APPENDED;
        }

        @Override
        public Optional<OwnershipState> findCurrentOwnership(TrackedItemId itemId) {
            OwnershipLedgerEntry latest = null;
            long count = 0;
            for (OwnershipLedgerEntry entry : entries) {
                if (entry.itemId().equals(itemId)) {
                    count++;
                    if (latest == null || entry.sequenceNumber() > latest.sequenceNumber()) {
                        latest = entry;
                    }
                }
            }
            if (latest == null) {
                return Optional.empty();
            }
            return Optional.of(new OwnershipState(
                    itemId, latest.newSubject(), latest.sequenceNumber(),
                    latest.transitionReason(), latest.occurredAt(), count
            ));
        }

        @Override
        public List<OwnershipLedgerEntry> findHistory(TrackedItemId itemId, int limit) {
            return entries.stream()
                    .filter(e -> e.itemId().equals(itemId))
                    .sorted((a, b) -> Integer.compare(b.sequenceNumber(), a.sequenceNumber()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countHistory(TrackedItemId itemId) {
            return entries.stream().filter(e -> e.itemId().equals(itemId)).count();
        }

        @Override
        public Optional<OwnershipLedgerEntry> findByIdempotencyKey(TrackedItemId itemId, String idempotencyKey) {
            if (idempotencyKey == null || idempotencyKey.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(byIdempotencyKey.get(itemId + ":" + idempotencyKey));
        }

        @Override
        public long count() {
            return entries.size();
        }
    }
}
