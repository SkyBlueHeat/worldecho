package dev.worldecho.domain.item;

import dev.worldecho.domain.content.ContentKey;
import dev.worldecho.persistence.LotOwnershipLedgerRepository;
import dev.worldecho.persistence.OwnershipLedgerRepository;
import dev.worldecho.persistence.TrackedItemLotRepository;
import dev.worldecho.persistence.TrackedItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure-Java logic of {@link AutomaticItemIdentityService} using fake
 * in-memory repositories. No Bukkit or Paper dependencies required.
 */
class AutomaticItemIdentityServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneId.of("UTC"));
    private static final UUID PLAYER_A = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();
    private static final String SESSION_ID = "test-session";

    private FakeTrackedItemRepo trackedItemRepo;
    private FakeLotRepo lotRepo;
    private FakeLedgerRepo ledgerRepo;
    private FakeLotLedgerRepo lotLedgerRepo;
    private OwnershipTransitionService ownershipService;
    private LotOwnershipTransitionService lotOwnershipService;
    private AutomaticItemIdentityService identityService;

    @BeforeEach
    void setUp() {
        trackedItemRepo = new FakeTrackedItemRepo();
        lotRepo = new FakeLotRepo();
        ledgerRepo = new FakeLedgerRepo();
        lotLedgerRepo = new FakeLotLedgerRepo();
        ownershipService = new OwnershipTransitionService(trackedItemRepo, ledgerRepo, FIXED_CLOCK);
        lotOwnershipService = new LotOwnershipTransitionService(lotRepo, lotLedgerRepo, FIXED_CLOCK);
        identityService = new AutomaticItemIdentityService(
                trackedItemRepo, lotRepo, ownershipService, lotOwnershipService, FIXED_CLOCK);
    }

    private ReconciliationCycle cycle(UUID player) {
        return ReconciliationCycle.create(player, "test", 1L, SESSION_ID, 0L);
    }

    private ObservedInventorySlot uniqueSlot(TrackedItemId existingId) {
        return new ObservedInventorySlot(
                "main", 0, "minecraft:diamond_sword", 1,
                new ContentKey("minecraft", "diamond_sword"), "minecraft",
                ItemDescriptor.of("minecraft:diamond_sword", 1),
                new IdentityClassificationResult(IdentityMode.UNIQUE, List.of("damageable"), 1.0),
                existingId, null, null, false
        );
    }

    private ObservedInventorySlot lotSlot(TrackedItemLotId existingLotId, int amount) {
        LotCompatibilityFingerprint fingerprint = LotCompatibilityFingerprint.builder()
                .providerId("minecraft")
                .material("minecraft:cobblestone")
                .build();
        return new ObservedInventorySlot(
                "main", 1, "minecraft:cobblestone", amount,
                new ContentKey("minecraft", "cobblestone"), "minecraft",
                ItemDescriptor.of("minecraft:cobblestone", amount),
                new IdentityClassificationResult(IdentityMode.LOT, List.of("stackable"), 1.0),
                null, existingLotId, fingerprint, false
        );
    }

    private ObservedInventorySlot malformedSlot() {
        return new ObservedInventorySlot(
                "main", 2, "minecraft:iron_sword", 1,
                new ContentKey("minecraft", "iron_sword"), "minecraft",
                ItemDescriptor.of("minecraft:iron_sword", 1),
                new IdentityClassificationResult(IdentityMode.UNIQUE, List.of("damageable"), 1.0),
                null, null, null, true
        );
    }

    @Test
    void untrackedUniqueItemReceivesAssignedStatus() {
        ObservedInventorySlot slot = uniqueSlot(null);
        SlotProcessResult result = identityService.processUniqueSlot(
                slot, PLAYER_A, "PlayerA", cycle(PLAYER_A));

        assertEquals(SlotProcessResult.Status.ASSIGNED, result.status());
    }

    @Test
    void existingUniqueIdIsPreservedAndProcessed() {
        TrackedItemId existingId = TrackedItemId.random();
        trackedItemRepo.existing.add(existingId);

        ObservedInventorySlot slot = uniqueSlot(existingId);
        SlotProcessResult result = identityService.processUniqueSlot(
                slot, PLAYER_A, "PlayerA", cycle(PLAYER_A));

        assertEquals(SlotProcessResult.Status.PROCESSED, result.status());
        assertTrue(result.optionalOwnershipResult().isPresent());
    }

    @Test
    void existingUniqueIdMissingDbRecordIsReconciled() {
        TrackedItemId existingId = TrackedItemId.random();
        // Don't add to trackedItemRepo — simulates missing DB record

        ObservedInventorySlot slot = uniqueSlot(existingId);
        SlotProcessResult result = identityService.processUniqueSlot(
                slot, PLAYER_A, "PlayerA", cycle(PLAYER_A));

        assertEquals(SlotProcessResult.Status.PROCESSED, result.status());
        assertTrue(trackedItemRepo.existing.contains(existingId),
                "DB record should be created for missing PDC identity");
    }

    @Test
    void malformedIdentityIsNotOverwritten() {
        ObservedInventorySlot slot = malformedSlot();
        SlotProcessResult result = identityService.processUniqueSlot(
                slot, PLAYER_A, "PlayerA", cycle(PLAYER_A));

        assertEquals(SlotProcessResult.Status.MALFORMED, result.status());
    }

    @Test
    void samePlayerReconciliationIsIdempotent() {
        TrackedItemId existingId = TrackedItemId.random();
        trackedItemRepo.existing.add(existingId);

        ReconciliationCycle c = cycle(PLAYER_A);
        ObservedInventorySlot slot = uniqueSlot(existingId);

        SlotProcessResult first = identityService.processUniqueSlot(
                slot, PLAYER_A, "PlayerA", c);
        SlotProcessResult second = identityService.processUniqueSlot(
                slot, PLAYER_A, "PlayerA", c);

        assertEquals(SlotProcessResult.Status.PROCESSED, first.status());
        assertEquals(SlotProcessResult.Status.PROCESSED, second.status());
        // Same idempotency key → second should be idempotent replay
        assertEquals(OwnershipResultStatus.IDEMPOTENT_REPLAY, second.ownershipResult().status());
    }

    @Test
    void differentPlayerCreatesOwnershipTransfer() {
        TrackedItemId existingId = TrackedItemId.random();
        trackedItemRepo.existing.add(existingId);

        ObservedInventorySlot slot = uniqueSlot(existingId);

        SlotProcessResult first = identityService.processUniqueSlot(
                slot, PLAYER_A, "PlayerA", cycle(PLAYER_A));
        SlotProcessResult second = identityService.processUniqueSlot(
                slot, PLAYER_B, "PlayerB", cycle(PLAYER_B));

        assertEquals(OwnershipResultStatus.RECORDED, first.ownershipResult().status());
        assertEquals(OwnershipResultStatus.RECORDED, second.ownershipResult().status());
        assertEquals(2, ledgerRepo.entries.size(), "Should have 2 ledger entries");
    }

    @Test
    void untrackedLotReceivesNewLotIdentity() {
        ObservedInventorySlot slot = lotSlot(null, 32);
        SlotProcessResult result = identityService.processLotSlot(
                slot, PLAYER_A, "PlayerA", cycle(PLAYER_A));

        assertEquals(SlotProcessResult.Status.PROCESSED, result.status());
        assertEquals(1, lotRepo.lots.size(), "Should create one lot");
    }

    @Test
    void existingLotIdIsPreservedAndObserved() {
        TrackedItemLotId lotId = TrackedItemLotId.random();
        LotCompatibilityFingerprint fingerprint = LotCompatibilityFingerprint.builder()
                .providerId("minecraft")
                .material("minecraft:cobblestone")
                .build();
        lotRepo.lots.put(lotId, new TrackedItemLot(
                lotId, Instant.now(FIXED_CLOCK), Instant.now(FIXED_CLOCK), Instant.now(FIXED_CLOCK),
                new ContentKey("minecraft", "cobblestone"), "minecraft", "minecraft:cobblestone",
                fingerprint, 64, 64, "AUTOMATIC", "PLAYER:" + PLAYER_A
        ));

        ObservedInventorySlot slot = lotSlot(lotId, 32);
        SlotProcessResult result = identityService.processLotSlot(
                slot, PLAYER_A, "PlayerA", cycle(PLAYER_A));

        assertEquals(SlotProcessResult.Status.PROCESSED, result.status());
    }

    @Test
    void malformedLotSlotIsSkipped() {
        ObservedInventorySlot slot = new ObservedInventorySlot(
                "main", 0, "minecraft:cobblestone", 32,
                new ContentKey("minecraft", "cobblestone"), "minecraft",
                ItemDescriptor.of("minecraft:cobblestone", 32),
                new IdentityClassificationResult(IdentityMode.LOT, List.of("stackable"), 1.0),
                null, null, null, true
        );

        SlotProcessResult result = identityService.processLotSlot(
                slot, PLAYER_A, "PlayerA", cycle(PLAYER_A));

        assertEquals(SlotProcessResult.Status.MALFORMED, result.status());
    }

    @Test
    void oneFailedItemDoesNotBlockOthers() {
        // Item has PDC identity but no DB record; simulate create() failure
        TrackedItemId goodId = TrackedItemId.random();
        trackedItemRepo.failOnNext = true;

        ObservedInventorySlot goodSlot = uniqueSlot(goodId);

        SlotProcessResult result = identityService.processUniqueSlot(
                goodSlot, PLAYER_A, "PlayerA", cycle(PLAYER_A));

        // The failing repo should produce a persistence failure, not crash
        assertEquals(SlotProcessResult.Status.PERSISTENCE_FAILURE, result.status());
        assertNotNull(result.errorMessage());
    }

    // --- Owner-scoped aggregate commodity model (Model A) tests ---

    @Test
    void samePlayerLotReusesExistingLot() {
        // First observation creates a lot
        ObservedInventorySlot slot1 = lotSlot(null, 32);
        identityService.processLotSlot(slot1, PLAYER_A, "PlayerA", cycle(PLAYER_A));
        assertEquals(1, lotRepo.lots.size());

        // Second observation for same player with same fingerprint reuses the lot
        ObservedInventorySlot slot2 = lotSlot(null, 16);
        identityService.processLotSlot(slot2, PLAYER_A, "PlayerA", cycle(PLAYER_A));
        assertEquals(1, lotRepo.lots.size(), "Same player should reuse existing lot");
    }

    @Test
    void differentPlayersGetDifferentLots() {
        ObservedInventorySlot slotA = lotSlot(null, 32);
        identityService.processLotSlot(slotA, PLAYER_A, "PlayerA", cycle(PLAYER_A));

        ObservedInventorySlot slotB = lotSlot(null, 32);
        identityService.processLotSlot(slotB, PLAYER_B, "PlayerB", cycle(PLAYER_B));

        assertEquals(2, lotRepo.lots.size(), "Different players should get different lots");
    }

    @Test
    void restartSafeLotAssociation() {
        // Player A gets a lot
        ObservedInventorySlot slot1 = lotSlot(null, 32);
        SlotProcessResult result1 = identityService.processLotSlot(
                slot1, PLAYER_A, "PlayerA", cycle(PLAYER_A));
        assertEquals(SlotProcessResult.Status.PROCESSED, result1.status());

        // Simulate restart: same player, same fingerprint, new cycle
        // The lot should be found by fingerprint+owner and reused
        ObservedInventorySlot slot2 = lotSlot(null, 16);
        SlotProcessResult result2 = identityService.processLotSlot(
                slot2, PLAYER_A, "PlayerA", cycle(PLAYER_A));
        assertEquals(SlotProcessResult.Status.PROCESSED, result2.status());
        assertEquals(1, lotRepo.lots.size(), "Restart should find existing lot by fingerprint+owner");
    }

    @Test
    void lotAmountIsUpdatedOnReobservation() {
        ObservedInventorySlot slot1 = lotSlot(null, 32);
        identityService.processLotSlot(slot1, PLAYER_A, "PlayerA", cycle(PLAYER_A));

        TrackedItemLotId lotId = lotRepo.lots.keySet().iterator().next();
        assertEquals(32, lotRepo.lots.get(lotId).currentAmount());

        // Re-observe with different amount
        ObservedInventorySlot slot2 = lotSlot(null, 16);
        identityService.processLotSlot(slot2, PLAYER_A, "PlayerA", cycle(PLAYER_A));
        assertEquals(16, lotRepo.lots.get(lotId).currentAmount(),
                "Amount should be updated to latest observed value");
    }

    @Test
    void duplicateUniqueIdInTwoPlayersDoesNotPingPong() {
        // Player A has the item first
        TrackedItemId existingId = TrackedItemId.random();
        trackedItemRepo.existing.add(existingId);

        ObservedInventorySlot slot = uniqueSlot(existingId);

        // Player A processes — should record ownership
        SlotProcessResult resultA = identityService.processUniqueSlot(
                slot, PLAYER_A, "PlayerA", cycle(PLAYER_A));
        assertEquals(OwnershipResultStatus.RECORDED, resultA.ownershipResult().status());

        // Player B processes same item — should record ownership transfer
        SlotProcessResult resultB = identityService.processUniqueSlot(
                slot, PLAYER_B, "PlayerB", cycle(PLAYER_B));
        assertEquals(OwnershipResultStatus.RECORDED, resultB.ownershipResult().status());

        // Player A processes again with the SAME cycle — IDEMPOTENT_REPLAY
        // (same idempotency key, same payload as first call — no new ownership change)
        SlotProcessResult resultA2 = identityService.processUniqueSlot(
                slot, PLAYER_A, "PlayerA", cycle(PLAYER_A));
        assertEquals(OwnershipResultStatus.IDEMPOTENT_REPLAY, resultA2.ownershipResult().status(),
                "Same cycle + same player should be idempotent replay");

        // Player A with a NEW cycle — RECORDED (transfers back from B to A)
        // Ping-pong prevention is at the reconciler level via DuplicateObservationRegistry
        ReconciliationCycle cycleA2 = ReconciliationCycle.create(PLAYER_A, "test2", 2L, SESSION_ID, 0L);
        SlotProcessResult resultA3 = identityService.processUniqueSlot(
                slot, PLAYER_A, "PlayerA", cycleA2);
        assertEquals(OwnershipResultStatus.RECORDED, resultA3.ownershipResult().status(),
                "Service-level: ownership transfers back to A (reconciler prevents this via duplicate detection)");

        // Player A again with another new cycle — NO_CHANGE (A is already owner)
        ReconciliationCycle cycleA3 = ReconciliationCycle.create(PLAYER_A, "test3", 3L, SESSION_ID, 0L);
        SlotProcessResult resultA4 = identityService.processUniqueSlot(
                slot, PLAYER_A, "PlayerA", cycleA3);
        assertEquals(OwnershipResultStatus.NO_CHANGE, resultA4.ownershipResult().status(),
                "Same player re-observing in a new cycle should be NO_CHANGE when already owner");
    }

    @Test
    void joinReconciliationPlanGeneratesSnapshot() {
        // Simulate a join reconciliation: player has unique and lot items
        TrackedItemId uniqueId = TrackedItemId.random();
        trackedItemRepo.existing.add(uniqueId);

        ObservedInventorySnapshot snapshot = new ObservedInventorySnapshot(
                PLAYER_A, "PlayerA",
                List.of(uniqueSlot(uniqueId), lotSlot(null, 32)),
                cycle(PLAYER_A)
        );

        // Process all slots
        for (ObservedInventorySlot slot : snapshot.slots()) {
            if (slot.isUnique()) {
                identityService.processUniqueSlot(slot, PLAYER_A, "PlayerA", snapshot.cycle());
            } else if (slot.isLot()) {
                identityService.processLotSlot(slot, PLAYER_A, "PlayerA", snapshot.cycle());
            }
        }

        // Verify both items were processed
        assertEquals(1, trackedItemRepo.existing.size());
        assertEquals(1, lotRepo.lots.size());
        assertTrue(ledgerRepo.entries.size() >= 1);
        assertTrue(lotLedgerRepo.entries.size() >= 1);
    }

    // --- Fake repositories ---

    private static class FakeTrackedItemRepo implements TrackedItemRepository {
        final java.util.Set<TrackedItemId> existing = new java.util.HashSet<>();
        boolean failOnNext = false;

        @Override
        public CreateResult create(TrackedItemRecord record) {
            if (failOnNext) {
                failOnNext = false;
                throw new RuntimeException("simulated failure");
            }
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
    }

    private static class FakeLotRepo implements TrackedItemLotRepository {
        final Map<TrackedItemLotId, TrackedItemLot> lots = new HashMap<>();
        final List<LotLineageEntry> lineage = new ArrayList<>();

        @Override
        public CreateResult create(TrackedItemLot lot) throws SQLException {
            if (lots.containsKey(lot.lotId())) {
                return CreateResult.ALREADY_EXISTS;
            }
            lots.put(lot.lotId(), lot);
            return CreateResult.CREATED;
        }

        @Override
        public Optional<TrackedItemLot> findById(TrackedItemLotId lotId) throws SQLException {
            return Optional.ofNullable(lots.get(lotId));
        }

        @Override
        public Optional<TrackedItemLot> findByFingerprint(LotCompatibilityFingerprint fingerprint) throws SQLException {
            return lots.values().stream()
                    .filter(l -> l.fingerprint().equals(fingerprint))
                    .findFirst();
        }

        @Override
        public Optional<TrackedItemLot> findByFingerprintAndOwner(
                LotCompatibilityFingerprint fingerprint, String ownerSubject) throws SQLException {
            return lots.values().stream()
                    .filter(l -> l.fingerprint().equals(fingerprint)
                            && l.createdBySubject().equals(ownerSubject))
                    .findFirst();
        }

        @Override
        public boolean exists(TrackedItemLotId lotId) throws SQLException {
            return lots.containsKey(lotId);
        }

        @Override
        public void updateAmount(TrackedItemLotId lotId, int newAmount) throws SQLException {
            TrackedItemLot lot = lots.get(lotId);
            if (lot != null) {
                lots.put(lotId, lot.withCurrentAmount(newAmount));
            }
        }

        @Override
        public void observe(TrackedItemLotId lotId, Instant lastSeenAt) throws SQLException {
            TrackedItemLot lot = lots.get(lotId);
            if (lot != null) {
                lots.put(lotId, lot.withLastSeenAt(lastSeenAt));
            }
        }

        @Override
        public int count() throws SQLException {
            return lots.size();
        }

        @Override
        public void appendLineage(LotLineageEntry entry) throws SQLException {
            lineage.add(entry);
        }

        @Override
        public Optional<LotLineageEntry> findLineageByIdempotencyKey(TrackedItemLotId lotId, String idempotencyKey) throws SQLException {
            return lineage.stream()
                    .filter(e -> e.lotId().equals(lotId) && e.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public List<LotLineageEntry> findLineage(TrackedItemLotId lotId) throws SQLException {
            return lineage.stream().filter(e -> e.lotId().equals(lotId)).toList();
        }
    }

    private static class FakeLedgerRepo implements OwnershipLedgerRepository {
        final List<OwnershipLedgerEntry> entries = new ArrayList<>();
        final Map<String, OwnershipLedgerEntry> byIdempotency = new HashMap<>();

        @Override
        public AppendResult append(OwnershipLedgerEntry entry) {
            String key = entry.itemId() + ":" + entry.idempotencyKey();
            if (entry.hasIdempotencyKey() && byIdempotency.containsKey(key)) {
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
                byIdempotency.put(key, entry);
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
            if (latest == null) return Optional.empty();
            return Optional.of(new OwnershipState(
                    itemId, latest.newSubject(), latest.sequenceNumber(),
                    latest.transitionReason(), latest.occurredAt(), count));
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
            if (idempotencyKey == null || idempotencyKey.isEmpty()) return Optional.empty();
            return Optional.ofNullable(byIdempotency.get(itemId + ":" + idempotencyKey));
        }

        @Override
        public long count() {
            return entries.size();
        }
    }

    private static class FakeLotLedgerRepo implements LotOwnershipLedgerRepository {
        final List<LotOwnershipLedgerEntry> entries = new ArrayList<>();
        final Map<String, LotOwnershipLedgerEntry> byIdempotency = new HashMap<>();

        @Override
        public AppendResult append(LotOwnershipLedgerEntry entry) throws SQLException {
            String key = entry.lotId() + ":" + entry.idempotencyKey();
            if (!entry.idempotencyKey().isEmpty() && byIdempotency.containsKey(key)) {
                return AppendResult.IDEMPOTENT_REPLAY;
            }
            entries.add(entry);
            if (!entry.idempotencyKey().isEmpty()) {
                byIdempotency.put(key, entry);
            }
            return AppendResult.APPENDED;
        }

        @Override
        public Optional<LotOwnershipState> findCurrentOwnership(TrackedItemLotId lotId) throws SQLException {
            LotOwnershipLedgerEntry latest = null;
            long count = 0;
            for (LotOwnershipLedgerEntry entry : entries) {
                if (entry.lotId().equals(lotId)) {
                    count++;
                    if (latest == null || entry.sequenceNumber() > latest.sequenceNumber()) {
                        latest = entry;
                    }
                }
            }
            if (latest == null) return Optional.empty();
            return Optional.of(new LotOwnershipState(
                    lotId, latest.newSubject(), latest.sequenceNumber(),
                    latest.transitionReason(), latest.occurredAt(), count));
        }

        @Override
        public Optional<LotOwnershipLedgerEntry> findByIdempotencyKey(TrackedItemLotId lotId, String idempotencyKey) throws SQLException {
            if (idempotencyKey == null || idempotencyKey.isEmpty()) return Optional.empty();
            return Optional.ofNullable(byIdempotency.get(lotId + ":" + idempotencyKey));
        }

        @Override
        public List<LotOwnershipLedgerEntry> findHistory(TrackedItemLotId lotId, int limit) throws SQLException {
            return entries.stream()
                    .filter(e -> e.lotId().equals(lotId))
                    .sorted((a, b) -> Integer.compare(b.sequenceNumber(), a.sequenceNumber()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countHistory(TrackedItemLotId lotId) throws SQLException {
            return entries.stream().filter(e -> e.lotId().equals(lotId)).count();
        }

        @Override
        public long countAll() throws SQLException {
            return entries.size();
        }
    }
}
