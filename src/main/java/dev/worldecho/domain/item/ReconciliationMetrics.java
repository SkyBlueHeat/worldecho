package dev.worldecho.domain.item;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe metrics for automatic item tracking reconciliation.
 */
public final class ReconciliationMetrics {

    private final AtomicLong inventoryReconciliations = new AtomicLong();
    private final AtomicLong automaticIdentitiesAssigned = new AtomicLong();
    private final AtomicLong automaticLotsAssigned = new AtomicLong();
    private final AtomicLong ownershipTransitionsRecorded = new AtomicLong();
    private final AtomicLong identityWarnings = new AtomicLong();
    private final AtomicLong duplicateIdentityObservations = new AtomicLong();
    private final AtomicLong pendingReconciliations = new AtomicLong();
    private final AtomicLong worldDropObservations = new AtomicLong();
    private final AtomicLong entityItemObservations = new AtomicLong();
    private final AtomicLong loadedEntityReconciliations = new AtomicLong();
    private final AtomicLong physicalOwnershipTransitions = new AtomicLong();
    private final AtomicLong physicalObservationWarnings = new AtomicLong();
    private final AtomicLong staleObservationsRejected = new AtomicLong();
    private final AtomicLong pendingPhysicalObservations = new AtomicLong();

    public void recordReconciliation() {
        inventoryReconciliations.incrementAndGet();
    }

    public void recordIdentityAssigned() {
        automaticIdentitiesAssigned.incrementAndGet();
    }

    public void recordLotAssigned() {
        automaticLotsAssigned.incrementAndGet();
    }

    public void recordOwnershipTransition() {
        ownershipTransitionsRecorded.incrementAndGet();
    }

    public void recordIdentityWarning() {
        identityWarnings.incrementAndGet();
    }

    public void recordDuplicateIdentity() {
        duplicateIdentityObservations.incrementAndGet();
    }

    public void incrementPending() {
        pendingReconciliations.incrementAndGet();
    }

    public void decrementPending() {
        pendingReconciliations.decrementAndGet();
    }

    public void recordWorldDropObservation() {
        worldDropObservations.incrementAndGet();
    }

    public void recordEntityItemObservation() {
        entityItemObservations.incrementAndGet();
    }

    public void recordLoadedEntityReconciliation() {
        loadedEntityReconciliations.incrementAndGet();
    }

    public void recordPhysicalOwnershipTransition() {
        physicalOwnershipTransitions.incrementAndGet();
    }

    public void recordPhysicalObservationWarning() {
        physicalObservationWarnings.incrementAndGet();
    }

    public void recordStaleObservationRejected() {
        staleObservationsRejected.incrementAndGet();
    }

    public void incrementPendingPhysical() {
        pendingPhysicalObservations.incrementAndGet();
    }

    public void decrementPendingPhysical() {
        pendingPhysicalObservations.decrementAndGet();
    }

    public long inventoryReconciliations() {
        return inventoryReconciliations.get();
    }

    public long automaticIdentitiesAssigned() {
        return automaticIdentitiesAssigned.get();
    }

    public long automaticLotsAssigned() {
        return automaticLotsAssigned.get();
    }

    public long ownershipTransitionsRecorded() {
        return ownershipTransitionsRecorded.get();
    }

    public long identityWarnings() {
        return identityWarnings.get();
    }

    public long duplicateIdentityObservations() {
        return duplicateIdentityObservations.get();
    }

    public long pendingReconciliations() {
        return pendingReconciliations.get();
    }

    public long worldDropObservations() {
        return worldDropObservations.get();
    }

    public long entityItemObservations() {
        return entityItemObservations.get();
    }

    public long loadedEntityReconciliations() {
        return loadedEntityReconciliations.get();
    }

    public long physicalOwnershipTransitions() {
        return physicalOwnershipTransitions.get();
    }

    public long physicalObservationWarnings() {
        return physicalObservationWarnings.get();
    }

    public long staleObservationsRejected() {
        return staleObservationsRejected.get();
    }

    public long pendingPhysicalObservations() {
        return pendingPhysicalObservations.get();
    }

    public void reset() {
        inventoryReconciliations.set(0);
        automaticIdentitiesAssigned.set(0);
        automaticLotsAssigned.set(0);
        ownershipTransitionsRecorded.set(0);
        identityWarnings.set(0);
        duplicateIdentityObservations.set(0);
        pendingReconciliations.set(0);
        worldDropObservations.set(0);
        entityItemObservations.set(0);
        loadedEntityReconciliations.set(0);
        physicalOwnershipTransitions.set(0);
        physicalObservationWarnings.set(0);
        staleObservationsRejected.set(0);
        pendingPhysicalObservations.set(0);
    }
}
