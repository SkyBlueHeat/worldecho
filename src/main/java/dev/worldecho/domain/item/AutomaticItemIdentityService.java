package dev.worldecho.domain.item;

import dev.worldecho.persistence.TrackedItemLotRepository;
import dev.worldecho.persistence.TrackedItemRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates automatic identity assignment and persistence for observed inventory items.
 *
 * <p>Called on the background thread with immutable snapshots captured on the main thread.
 */
public final class AutomaticItemIdentityService {

    private final TrackedItemRepository trackedItemRepository;
    private final TrackedItemLotRepository lotRepository;
    private final OwnershipTransitionService ownershipTransitionService;
    private final LotOwnershipTransitionService lotOwnershipTransitionService;
    private final Clock clock;

    public AutomaticItemIdentityService(
            TrackedItemRepository trackedItemRepository,
            TrackedItemLotRepository lotRepository,
            OwnershipTransitionService ownershipTransitionService,
            LotOwnershipTransitionService lotOwnershipTransitionService,
            Clock clock
    ) {
        this.trackedItemRepository = Objects.requireNonNull(trackedItemRepository, "trackedItemRepository");
        this.lotRepository = Objects.requireNonNull(lotRepository, "lotRepository");
        this.ownershipTransitionService = Objects.requireNonNull(ownershipTransitionService, "ownershipTransitionService");
        this.lotOwnershipTransitionService = Objects.requireNonNull(lotOwnershipTransitionService, "lotOwnershipTransitionService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Processes a single observed slot for a UNIQUE item.
     *
     * @return the result of processing
     */
    public SlotProcessResult processUniqueSlot(
            ObservedInventorySlot slot,
            UUID playerUuid,
            String playerDisplayName,
            ReconciliationCycle cycle
    ) {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(cycle, "cycle");

        if (slot.malformedIdentity()) {
            return SlotProcessResult.malformed(slot);
        }

        TrackedItemId itemId = slot.existingUniqueId();
        if (itemId == null) {
            return SlotProcessResult.assigned(slot);
        }

        try {
            if (!trackedItemRepository.exists(itemId)) {
                Instant now = Instant.now(clock);
                TrackedItemRecord record = new TrackedItemRecord(
                        itemId, now, now, now,
                        slot.contentKey(),
                        slot.providerId(),
                        slot.material(),
                        slot.descriptor() != null && slot.descriptor().hasCustomName()
                                ? slot.descriptor().customName() : "",
                        slot.descriptor() != null ? null : null,
                        "AUTOMATIC",
                        OwnershipSubject.player(playerUuid, playerDisplayName).describe()
                );
                trackedItemRepository.create(record);
            } else {
                trackedItemRepository.observe(itemId, Instant.now(clock).toEpochMilli());
            }

            OwnershipSubject playerSubject = OwnershipSubject.player(playerUuid, playerDisplayName);
            String idempotencyKey = cycle.idempotencyKeyFor(itemId);
            OwnershipResult ownershipResult = ownershipTransitionService.transition(
                    itemId, playerSubject,
                    OwnershipTransitionReason.AUTOMATIC_TRACKING,
                    idempotencyKey,
                    "automatic-reconciliation",
                    "cycle=" + cycle.cycleSequence()
            );

            return SlotProcessResult.processed(slot, ownershipResult);
        } catch (Exception exception) {
            return SlotProcessResult.persistenceFailure(slot, exception.getMessage());
        }
    }

    /**
     * Processes all LOT slots in a snapshot as a single aggregation unit.
     *
     * <p>Groups LOT slots by fingerprint, sums amounts per group, and performs
     * one persistence update per owner+fingerprint per cycle. Existing lots
     * whose fingerprint is absent from the snapshot are zeroed.
     *
     * @return list of results, one per input LOT slot (for metrics)
     */
    public List<SlotProcessResult> processLotSlots(
            ObservedInventorySnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        UUID playerUuid = snapshot.playerUuid();
        String playerDisplayName = snapshot.playerDisplayName();
        ReconciliationCycle cycle = snapshot.cycle();

        List<SlotProcessResult> results = new ArrayList<>();

        // Separate valid LOT slots from malformed ones
        List<ObservedInventorySlot> lotSlots = new ArrayList<>();
        for (ObservedInventorySlot slot : snapshot.slots()) {
            if (!slot.isLot()) {
                continue;
            }
            if (slot.malformedIdentity()) {
                results.add(SlotProcessResult.malformed(slot));
                continue;
            }
            if (slot.isEmpty()) {
                continue;
            }
            lotSlots.add(slot);
        }

        // Group by fingerprint and sum amounts
        Map<LotCompatibilityFingerprint, Integer> aggregatedAmounts = new LinkedHashMap<>();
        Map<LotCompatibilityFingerprint, ObservedInventorySlot> representativeSlots = new LinkedHashMap<>();
        for (ObservedInventorySlot slot : lotSlots) {
            LotCompatibilityFingerprint fingerprint = slot.lotFingerprint();
            if (fingerprint == null) {
                fingerprint = LotCompatibilityFingerprint.builder()
                        .providerId(slot.providerId())
                        .material(slot.material())
                        .build();
            }
            aggregatedAmounts.merge(fingerprint, slot.amount(), Integer::sum);
            representativeSlots.putIfAbsent(fingerprint, slot);
        }

        // Process each fingerprint group: one update per owner+fingerprint
        for (Map.Entry<LotCompatibilityFingerprint, Integer> entry : aggregatedAmounts.entrySet()) {
            LotCompatibilityFingerprint fingerprint = entry.getKey();
            int totalAmount = Math.max(0, entry.getValue());
            ObservedInventorySlot repSlot = representativeSlots.get(fingerprint);

            try {
                TrackedItemLotId lotId = findOrCreateOrUpdateLot(
                        repSlot, fingerprint, totalAmount,
                        playerUuid, playerDisplayName);

                OwnershipSubject playerSubject = OwnershipSubject.player(playerUuid, playerDisplayName);
                String idempotencyKey = cycle.idempotencyKeyFor(lotId);
                OwnershipResult ownershipResult = lotOwnershipTransitionService.transition(
                        lotId, playerSubject,
                        OwnershipTransitionReason.AUTOMATIC_TRACKING,
                        idempotencyKey,
                        "automatic-reconciliation",
                        "cycle=" + cycle.cycleSequence()
                );

                results.add(SlotProcessResult.processed(repSlot, ownershipResult));
            } catch (Exception exception) {
                results.add(SlotProcessResult.persistenceFailure(repSlot, exception.getMessage()));
            }
        }

        // Zero out existing lots whose fingerprint is absent from snapshot
        try {
            zeroAbsentLots(aggregatedAmounts.keySet(), playerUuid, playerDisplayName, cycle);
        } catch (Exception ignored) {
            // Best-effort zeroing; don't fail the cycle for this
        }

        return results;
    }

    private TrackedItemLotId findOrCreateOrUpdateLot(
            ObservedInventorySlot slot,
            LotCompatibilityFingerprint fingerprint,
            int totalAmount,
            UUID playerUuid,
            String playerDisplayName
    ) throws Exception {
        String ownerStableId = playerUuid.toString();
        Optional<TrackedItemLot> existing = lotRepository.findByOwnerAndFingerprint(
                OwnershipSubjectType.PLAYER, ownerStableId, fingerprint);

        if (existing.isPresent()) {
            TrackedItemLot lot = existing.get();
            lotRepository.observe(lot.lotId(), Instant.now(clock));
            lotRepository.updateAmount(lot.lotId(), totalAmount);
            if (!playerDisplayName.isEmpty() && !playerDisplayName.equals(lot.ownerDisplaySnapshot())) {
                lotRepository.updateOwnerDisplaySnapshot(lot.lotId(), playerDisplayName);
            }
            return lot.lotId();
        }

        TrackedItemLotId newLotId = TrackedItemLotId.random();
        Instant now = Instant.now(clock);
        String ownerSubject = OwnershipSubject.player(playerUuid, playerDisplayName).describe();
        TrackedItemLot lot = new TrackedItemLot(
                newLotId, now, now, now,
                slot.contentKey(),
                slot.providerId(),
                slot.material(),
                fingerprint,
                totalAmount,
                totalAmount,
                "AUTOMATIC",
                ownerSubject,
                OwnershipSubjectType.PLAYER.token(),
                ownerStableId,
                playerDisplayName
        );
        lotRepository.create(lot);
        return newLotId;
    }

    private void zeroAbsentLots(
            java.util.Set<LotCompatibilityFingerprint> seenFingerprints,
            UUID playerUuid,
            String playerDisplayName,
            ReconciliationCycle cycle
    ) throws Exception {
        String ownerStableId = playerUuid.toString();
        List<TrackedItemLot> ownerLots = lotRepository.findAllByOwner(
                OwnershipSubjectType.PLAYER, ownerStableId);

        for (TrackedItemLot lot : ownerLots) {
            if (!seenFingerprints.contains(lot.fingerprint()) && lot.currentAmount() > 0) {
                lotRepository.observe(lot.lotId(), Instant.now(clock));
                lotRepository.updateAmount(lot.lotId(), 0);
            }
        }
    }
}
