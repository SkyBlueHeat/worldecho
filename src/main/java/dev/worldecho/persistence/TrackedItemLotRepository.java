package dev.worldecho.persistence;

import dev.worldecho.domain.item.LotLineageEntry;
import dev.worldecho.domain.item.LotCompatibilityFingerprint;
import dev.worldecho.domain.item.TrackedItemLot;
import dev.worldecho.domain.item.TrackedItemLotId;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import dev.worldecho.domain.item.OwnershipSubjectType;

/**
 * Repository for {@link TrackedItemLot} persistence.
 */
public interface TrackedItemLotRepository {

    enum CreateResult { CREATED, ALREADY_EXISTS }

    CreateResult create(TrackedItemLot lot) throws SQLException;

    Optional<TrackedItemLot> findById(TrackedItemLotId lotId) throws SQLException;

    Optional<TrackedItemLot> findByFingerprint(LotCompatibilityFingerprint fingerprint) throws SQLException;

    Optional<TrackedItemLot> findByFingerprintAndOwner(
            LotCompatibilityFingerprint fingerprint, String ownerSubject) throws SQLException;

    Optional<TrackedItemLot> findByOwnerAndFingerprint(
            OwnershipSubjectType ownerType, String ownerStableId,
            LotCompatibilityFingerprint fingerprint) throws SQLException;

    List<TrackedItemLot> findAllByOwner(
            OwnershipSubjectType ownerType, String ownerStableId) throws SQLException;

    void updateOwnerDisplaySnapshot(TrackedItemLotId lotId, String displayName) throws SQLException;

    boolean exists(TrackedItemLotId lotId) throws SQLException;

    void updateAmount(TrackedItemLotId lotId, int newAmount) throws SQLException;

    void observe(TrackedItemLotId lotId, java.time.Instant lastSeenAt) throws SQLException;

    int count() throws SQLException;

    void appendLineage(LotLineageEntry entry) throws SQLException;

    Optional<LotLineageEntry> findLineageByIdempotencyKey(TrackedItemLotId lotId, String idempotencyKey) throws SQLException;

    List<LotLineageEntry> findLineage(TrackedItemLotId lotId) throws SQLException;
}
