package dev.worldecho.persistence;

import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.domain.item.OwnershipResult;
import dev.worldecho.domain.item.OwnershipResultStatus;
import dev.worldecho.domain.item.OwnershipState;
import dev.worldecho.domain.item.OwnershipSubject;
import dev.worldecho.domain.item.OwnershipSubjectType;
import dev.worldecho.domain.item.OwnershipTransitionReason;
import dev.worldecho.domain.item.OwnershipTransitionService;
import dev.worldecho.domain.item.PhysicalObservationCycle;
import dev.worldecho.domain.item.PhysicalObservationReason;
import dev.worldecho.domain.item.PhysicalObservationRegistry;
import dev.worldecho.domain.item.PhysicalUniqueItemObservation;
import dev.worldecho.domain.item.PhysicalUniqueItemObservationService;
import dev.worldecho.domain.item.PhysicalObservationResult;
import dev.worldecho.domain.item.ReconciliationMetrics;
import dev.worldecho.domain.item.TrackedItemId;
import dev.worldecho.domain.item.TrackedItemRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the full physical observation flow:
 * drop → WORLD_DROP ledger entry → entity pickup → ENTITY ledger entry →
 * despawn → terminal SYSTEM observation → restart preserves ownership.
 */
class PhysicalObservationIntegrationTest {

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private TrackedItemRepository trackedItemRepository;
    private OwnershipLedgerRepository ledgerRepository;
    private OwnershipTransitionService transitionService;
    private PhysicalObservationRegistry observationRegistry;
    private ReconciliationMetrics metrics;
    private PhysicalUniqueItemObservationService observationService;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("physical-test.db"));
        database.initialize();
        trackedItemRepository = new SqliteTrackedItemRepository(database);
        ledgerRepository = new SqliteOwnershipLedgerRepository(database);
        transitionService = new OwnershipTransitionService(
                trackedItemRepository, ledgerRepository,
                Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneId.of("UTC"))
        );
        observationRegistry = new PhysicalObservationRegistry(60_000L);
        metrics = new ReconciliationMetrics();
        observationService = new PhysicalUniqueItemObservationService(
                trackedItemRepository,
                transitionService,
                observationRegistry,
                metrics,
                Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneId.of("UTC"))
        );
    }

    @Test
    void trackedSwordDropCreatesWorldDropLedgerEntry() throws Exception {
        TrackedItemId itemId = createTrackedItem();

        UUID itemEntityUuid = UUID.randomUUID();
        PhysicalUniqueItemObservation dropObs = buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.DROPPED, 1);

        PhysicalObservationResult result = observationService.process(dropObs);

        assertEquals(PhysicalObservationResult.Status.PROCESSED, result.status());
        Optional<OwnershipState> state = ledgerRepository.findCurrentOwnership(itemId);
        assertTrue(state.isPresent());
        assertEquals(OwnershipSubjectType.WORLD_DROP, state.get().currentSubject().type());
        assertEquals(itemEntityUuid.toString(), state.get().currentSubject().stableId());
    }

    @Test
    void worldDropPickupByEntityCreatesEntityLedgerEntry() throws Exception {
        TrackedItemId itemId = createTrackedItem();
        UUID itemEntityUuid = UUID.randomUUID();
        UUID zombieUuid = UUID.randomUUID();

        observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.DROPPED, 1));

        PhysicalObservationResult result = observationService.process(buildObservation(
                itemId, OwnershipSubject.entity(zombieUuid),
                PhysicalObservationReason.ENTITY_HELD, 2));

        assertEquals(PhysicalObservationResult.Status.PROCESSED, result.status());
        Optional<OwnershipState> state = ledgerRepository.findCurrentOwnership(itemId);
        assertTrue(state.isPresent());
        assertEquals(OwnershipSubjectType.ENTITY, state.get().currentSubject().type());
        assertEquals(zombieUuid.toString(), state.get().currentSubject().stableId());
        assertEquals(2, state.get().latestSequence());
    }

    @Test
    void entityDeathDropReturnsToWorldDrop() throws Exception {
        TrackedItemId itemId = createTrackedItem();
        UUID itemEntityUuid1 = UUID.randomUUID();
        UUID zombieUuid = UUID.randomUUID();
        UUID itemEntityUuid2 = UUID.randomUUID();

        observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid1),
                PhysicalObservationReason.DROPPED, 1));
        observationService.process(buildObservation(
                itemId, OwnershipSubject.entity(zombieUuid),
                PhysicalObservationReason.ENTITY_HELD, 2));
        PhysicalObservationResult result = observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid2),
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 3));

        assertEquals(PhysicalObservationResult.Status.PROCESSED, result.status());
        Optional<OwnershipState> state = ledgerRepository.findCurrentOwnership(itemId);
        assertTrue(state.isPresent());
        assertEquals(OwnershipSubjectType.WORLD_DROP, state.get().currentSubject().type());
        assertEquals(itemEntityUuid2.toString(), state.get().currentSubject().stableId());
        assertEquals(3, state.get().latestSequence());
    }

    @Test
    void playerPickupTransitionsToPlayerThroughExistingReconciler() throws Exception {
        TrackedItemId itemId = createTrackedItem();
        UUID itemEntityUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.DROPPED, 1));

        OwnershipResult result = transitionService.transition(
                itemId, OwnershipSubject.player(playerUuid),
                OwnershipTransitionReason.PLAYER_HELD, "player-pickup-k1", "reconciler", "");

        assertEquals(OwnershipResultStatus.RECORDED, result.status());
        Optional<OwnershipState> state = ledgerRepository.findCurrentOwnership(itemId);
        assertTrue(state.isPresent());
        assertEquals(OwnershipSubjectType.PLAYER, state.get().currentSubject().type());
        assertEquals(playerUuid.toString(), state.get().currentSubject().stableId());
    }

    @Test
    void itemDespawnCreatesTerminalSystemObservation() throws Exception {
        TrackedItemId itemId = createTrackedItem();
        UUID itemEntityUuid = UUID.randomUUID();

        observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.DROPPED, 1));

        PhysicalObservationResult result = observationService.process(buildObservation(
                itemId, OwnershipSubject.system("item-despawned"),
                PhysicalObservationReason.DESPAWNED, 2));

        assertEquals(PhysicalObservationResult.Status.PROCESSED, result.status());
        Optional<OwnershipState> state = ledgerRepository.findCurrentOwnership(itemId);
        assertTrue(state.isPresent());
        assertEquals(OwnershipSubjectType.SYSTEM, state.get().currentSubject().type());
        assertEquals("item-despawned", state.get().currentSubject().stableId());
    }

    @Test
    void restartPreservesCurrentPhysicalOwner() throws Exception {
        TrackedItemId itemId = createTrackedItem();
        UUID itemEntityUuid = UUID.randomUUID();

        observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.DROPPED, 1));

        DatabaseManager restarted = new DatabaseManager(database.databasePath());
        restarted.initialize();
        OwnershipLedgerRepository restartedLedger = new SqliteOwnershipLedgerRepository(restarted);

        Optional<OwnershipState> state = restartedLedger.findCurrentOwnership(itemId);
        assertTrue(state.isPresent());
        assertEquals(itemEntityUuid.toString(), state.get().currentSubject().stableId());
        assertEquals(1, state.get().latestSequence());
    }

    @Test
    void sameOwnerObservationProducesNoChange() throws Exception {
        TrackedItemId itemId = createTrackedItem();
        UUID itemEntityUuid = UUID.randomUUID();

        observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.DROPPED, 1));

        PhysicalObservationResult result = observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.LOADED_ITEM, 2));

        assertEquals(PhysicalObservationResult.Status.IDEMPOTENT_REPLAY, result.status());
        assertEquals(1, ledgerRepository.countHistory(itemId));
    }

    @Test
    void staleWorldDropCannotOverwriteEntity() throws Exception {
        TrackedItemId itemId = createTrackedItem();
        UUID itemEntityUuid = UUID.randomUUID();
        UUID zombieUuid = UUID.randomUUID();

        observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.DROPPED, 5));
        observationService.process(buildObservation(
                itemId, OwnershipSubject.entity(zombieUuid),
                PhysicalObservationReason.ENTITY_HELD, 10));

        PhysicalObservationResult result = observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 3));

        assertEquals(PhysicalObservationResult.Status.SKIPPED_STALE, result.status());
        Optional<OwnershipState> state = ledgerRepository.findCurrentOwnership(itemId);
        assertTrue(state.isPresent());
        assertEquals(OwnershipSubjectType.ENTITY, state.get().currentSubject().type());
    }

    @Test
    void missingDbRecordReconciledUsingExistingId() throws Exception {
        TrackedItemId itemId = TrackedItemId.random();
        UUID itemEntityUuid = UUID.randomUUID();

        PhysicalObservationResult result = observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 1));

        assertEquals(PhysicalObservationResult.Status.PROCESSED, result.status());
        assertTrue(trackedItemRepository.exists(itemId));
    }

    @Test
    void fullTransitionChainPlayerToWorldDropToEntityToWorldDropToPlayer() throws Exception {
        TrackedItemId itemId = createTrackedItem();
        UUID playerUuid = UUID.randomUUID();
        UUID itemEntityUuid1 = UUID.randomUUID();
        UUID zombieUuid = UUID.randomUUID();
        UUID itemEntityUuid2 = UUID.randomUUID();

        transitionService.transition(itemId, OwnershipSubject.player(playerUuid),
                OwnershipTransitionReason.TRACKED, "k-init", "test", "");

        observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid1),
                PhysicalObservationReason.DROPPED, 1));
        observationService.process(buildObservation(
                itemId, OwnershipSubject.entity(zombieUuid),
                PhysicalObservationReason.ENTITY_HELD, 2));
        observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid2),
                PhysicalObservationReason.WORLD_DROP_OBSERVED, 3));

        OwnershipResult playerResult = transitionService.transition(
                itemId, OwnershipSubject.player(playerUuid),
                OwnershipTransitionReason.PLAYER_HELD, "k-player-pickup", "reconciler", "");

        assertEquals(OwnershipResultStatus.RECORDED, playerResult.status());
        List<dev.worldecho.domain.item.OwnershipLedgerEntry> history =
                ledgerRepository.findHistory(itemId, 10);
        assertEquals(5, history.size());

        assertEquals(5, history.get(0).sequenceNumber());
        assertEquals(OwnershipSubjectType.PLAYER, history.get(0).newSubject().type());
        assertEquals(4, history.get(1).sequenceNumber());
        assertEquals(OwnershipSubjectType.WORLD_DROP, history.get(1).newSubject().type());
        assertEquals(3, history.get(2).sequenceNumber());
        assertEquals(OwnershipSubjectType.ENTITY, history.get(2).newSubject().type());
        assertEquals(2, history.get(3).sequenceNumber());
        assertEquals(OwnershipSubjectType.WORLD_DROP, history.get(3).newSubject().type());
        assertEquals(1, history.get(4).sequenceNumber());
        assertEquals(OwnershipSubjectType.PLAYER, history.get(4).newSubject().type());
    }

    @Test
    void despawnIsIdempotent() throws Exception {
        TrackedItemId itemId = createTrackedItem();
        UUID itemEntityUuid = UUID.randomUUID();

        observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.DROPPED, 1));

        PhysicalObservationCycle cycle = PhysicalObservationCycle.create(2, "session-1");
        PhysicalUniqueItemObservation despawnObs = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                OwnershipSubject.system("item-despawned"),
                PhysicalObservationReason.DESPAWNED,
                itemEntityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1", cycle, Instant.now());

        PhysicalObservationResult result1 = observationService.process(despawnObs);
        PhysicalObservationResult result2 = observationService.process(despawnObs);

        assertEquals(PhysicalObservationResult.Status.PROCESSED, result1.status());
        assertEquals(PhysicalObservationResult.Status.SKIPPED_DUPLICATE, result2.status());
        assertEquals(2, ledgerRepository.countHistory(itemId));
    }

    @Test
    void serviceProcessesAnyItemRegardlessOfMaterial() throws Exception {
        TrackedItemId itemId = TrackedItemId.random();
        UUID itemEntityUuid = UUID.randomUUID();

        PhysicalUniqueItemObservation obs = new PhysicalUniqueItemObservation(
                itemId,
                ContentKey.parse("minecraft:cobblestone"),
                "cobblestone",
                OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.WORLD_DROP_OBSERVED,
                itemEntityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "cobblestone:64",
                PhysicalObservationCycle.create(1, "session-1"),
                Instant.now()
        );

        PhysicalObservationResult result = observationService.process(obs);

        assertEquals(PhysicalObservationResult.Status.PROCESSED, result.status());
        assertTrue(trackedItemRepository.exists(itemId));
    }

    @Test
    void historyPreservedAfterDespawn() throws Exception {
        TrackedItemId itemId = createTrackedItem();
        UUID playerUuid = UUID.randomUUID();
        UUID itemEntityUuid = UUID.randomUUID();

        transitionService.transition(itemId, OwnershipSubject.player(playerUuid),
                OwnershipTransitionReason.TRACKED, "k1", "test", "");
        observationService.process(buildObservation(
                itemId, OwnershipSubject.worldDrop(itemEntityUuid),
                PhysicalObservationReason.DROPPED, 1));
        observationService.process(buildObservation(
                itemId, OwnershipSubject.system("item-despawned"),
                PhysicalObservationReason.DESPAWNED, 2));

        List<dev.worldecho.domain.item.OwnershipLedgerEntry> history =
                ledgerRepository.findHistory(itemId, 10);
        assertEquals(3, history.size());
        assertTrue(trackedItemRepository.exists(itemId));
    }

    @Test
    void concurrentDropAndSpawnProduceExactlyOneWorldDropTransition() throws Exception {
        TrackedItemId itemId = createTrackedItem();
        UUID itemEntityUuid = UUID.randomUUID();
        OwnershipSubject worldDrop = OwnershipSubject.worldDrop(itemEntityUuid);

        PhysicalUniqueItemObservation dropObs = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                worldDrop, PhysicalObservationReason.DROPPED,
                itemEntityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(1, "session-1"), Instant.now());

        PhysicalUniqueItemObservation spawnObs = new PhysicalUniqueItemObservation(
                itemId, ContentKey.parse("minecraft:diamond_sword"), "diamond_sword",
                worldDrop, PhysicalObservationReason.WORLD_DROP_OBSERVED,
                itemEntityUuid, UUID.randomUUID(), "world",
                0, 64, 0, "diamond_sword:1",
                PhysicalObservationCycle.create(2, "session-1"), Instant.now());

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);
        java.util.List<PhysicalObservationResult> results =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        pool.submit(() -> {
            latch.countDown();
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            results.add(observationService.process(dropObs));
        });
        pool.submit(() -> {
            latch.countDown();
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            results.add(observationService.process(spawnObs));
        });

        pool.shutdown();
        assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(2, results.size());

        long processedCount = results.stream()
                .filter(r -> r.status() == PhysicalObservationResult.Status.PROCESSED
                        || r.status() == PhysicalObservationResult.Status.NO_CHANGE)
                .count();
        assertTrue(processedCount >= 1, "At least one observation must be PROCESSED or NO_CHANGE");

        long ledgerCount = ledgerRepository.countHistory(itemId);
        assertEquals(1, ledgerCount,
                "Exactly one WORLD_DROP ledger entry must exist for the same physical Item entity");

        Optional<OwnershipState> state = ledgerRepository.findCurrentOwnership(itemId);
        assertTrue(state.isPresent());
        assertEquals(OwnershipSubjectType.WORLD_DROP, state.get().currentSubject().type());
        assertEquals(itemEntityUuid.toString(), state.get().currentSubject().stableId());
    }

    private TrackedItemId createTrackedItem() throws Exception {
        TrackedItemId itemId = TrackedItemId.random();
        Instant now = Instant.parse("2026-07-30T12:00:00Z");
        ContentKey key = new ContentKey("minecraft", "diamond_sword");
        trackedItemRepository.create(new TrackedItemRecord(
                itemId, now, now, now, key, "minecraft",
                "minecraft:diamond_sword", "", 45,
                "tracked", "player:" + UUID.randomUUID()));
        return itemId;
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
}
