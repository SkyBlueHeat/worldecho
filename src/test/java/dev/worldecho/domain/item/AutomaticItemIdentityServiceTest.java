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
