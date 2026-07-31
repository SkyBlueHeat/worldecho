package dev.worldecho.domain.item;

import dev.worldecho.persistence.TrackedItemLotRepository;
import dev.worldecho.persistence.TrackedItemRepository;

import java.time.Clock;
import java.time.Instant;
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
     * Processes a single observed slot for a LOT item.
     */
    public SlotProcessResult processLotSlot(
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

        try {
            TrackedItemLotId lotId = slot.existingLotId();
            if (lotId == null) {
                lotId = findOrCreateLot(slot, playerUuid, playerDisplayName);
            } else {
                if (!lotRepository.exists(lotId)) {
                    lotId = findOrCreateLot(slot, playerUuid, playerDisplayName);
                } else {
                    lotRepository.observe(lotId, Instant.now(clock));
                    lotRepository.updateAmount(lotId, slot.amount());
                }
            }

            OwnershipSubject playerSubject = OwnershipSubject.player(playerUuid, playerDisplayName);
            String idempotencyKey = cycle.idempotencyKeyFor(lotId);
            OwnershipResult ownershipResult = lotOwnershipTransitionService.transition(
                    lotId, playerSubject,
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

    private TrackedItemLotId findOrCreateLot(
            ObservedInventorySlot slot,
            UUID playerUuid,
            String playerDisplayName
    ) throws Exception {
        LotCompatibilityFingerprint fingerprint = slot.lotFingerprint();
        if (fingerprint == null) {
            fingerprint = LotCompatibilityFingerprint.builder()
                    .providerId(slot.providerId())
                    .material(slot.material())
                    .build();
        }

        Optional<TrackedItemLot> existing = lotRepository.findByFingerprint(fingerprint);
        if (existing.isPresent()) {
            TrackedItemLot lot = existing.get();
            lotRepository.observe(lot.lotId(), Instant.now(clock));
            lotRepository.updateAmount(lot.lotId(), slot.amount());
            return lot.lotId();
        }

        TrackedItemLotId newLotId = TrackedItemLotId.random();
        Instant now = Instant.now(clock);
        TrackedItemLot lot = new TrackedItemLot(
                newLotId, now, now, now,
                slot.contentKey(),
                slot.providerId(),
                slot.material(),
                fingerprint,
                slot.amount(),
                slot.amount(),
                "AUTOMATIC",
                OwnershipSubject.player(playerUuid, playerDisplayName).describe()
        );
        lotRepository.create(lot);
        return newLotId;
    }
}
