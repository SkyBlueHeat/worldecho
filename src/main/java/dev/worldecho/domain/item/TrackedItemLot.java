package dev.worldecho.domain.item;

import dev.worldecho.domain.content.ContentKey;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable persistent record for a WorldEcho-tracked item lot.
 *
 * <p>A lot represents a quantity of fungible, stackable items with shared provenance.
 * Only {@code lastSeenAt} and {@code currentAmount} are mutable through explicit
 * observation operations.
 */
public record TrackedItemLot(
        TrackedItemLotId lotId,
        Instant createdAt,
        Instant firstSeenAt,
        Instant lastSeenAt,
        ContentKey contentKey,
        String providerId,
        String material,
        LotCompatibilityFingerprint fingerprint,
        int initialAmount,
        int currentAmount,
        String trackingReason,
        String createdBySubject
) {

    public TrackedItemLot {
        Objects.requireNonNull(lotId, "lotId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(contentKey, "contentKey");
        Objects.requireNonNull(fingerprint, "fingerprint");
        providerId = providerId == null ? "" : providerId.strip().toLowerCase(java.util.Locale.ROOT);
        material = material == null ? "" : material.strip();
        trackingReason = trackingReason == null ? "" : trackingReason.strip();
        createdBySubject = createdBySubject == null ? "" : createdBySubject.strip();
        initialAmount = Math.max(0, initialAmount);
        currentAmount = Math.max(0, currentAmount);
    }

    public TrackedItemLot withLastSeenAt(Instant newLastSeenAt) {
        return new TrackedItemLot(
                lotId, createdAt, firstSeenAt, newLastSeenAt,
                contentKey, providerId, material, fingerprint,
                initialAmount, currentAmount, trackingReason, createdBySubject
        );
    }

    public TrackedItemLot withCurrentAmount(int newAmount) {
        return new TrackedItemLot(
                lotId, createdAt, firstSeenAt, lastSeenAt,
                contentKey, providerId, material, fingerprint,
                initialAmount, Math.max(0, newAmount), trackingReason, createdBySubject
        );
    }
}
