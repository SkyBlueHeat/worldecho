package dev.worldecho.domain.item;

import dev.worldecho.persistence.TrackedItemRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Processes physical unique item observations on the background thread.
 *
 * <p>Ensures a tracked-item record exists (reconciling from PDC ID where possible),
 * checks the physical observation registry for duplicates and stale observations,
 * and performs ownership transitions through {@link OwnershipTransitionService}.
 *
 * <p>Does not access Bukkit.  All inputs are immutable domain records captured
 * on the main thread.
 */
public final class PhysicalUniqueItemObservationService {

    private static final Logger LOGGER = Logger.getLogger(PhysicalUniqueItemObservationService.class.getName());

    private final TrackedItemRepository trackedItemRepository;
    private final OwnershipTransitionService ownershipTransitionService;
    private final PhysicalObservationRegistry observationRegistry;
    private final ReconciliationMetrics metrics;
    private final Clock clock;

    public PhysicalUniqueItemObservationService(
            TrackedItemRepository trackedItemRepository,
            OwnershipTransitionService ownershipTransitionService,
            PhysicalObservationRegistry observationRegistry,
            ReconciliationMetrics metrics,
            Clock clock
    ) {
        this.trackedItemRepository = Objects.requireNonNull(trackedItemRepository, "trackedItemRepository");
        this.ownershipTransitionService = Objects.requireNonNull(ownershipTransitionService, "ownershipTransitionService");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Processes a single physical observation.
     *
     * @return the result of processing
     */
    public PhysicalObservationResult process(PhysicalUniqueItemObservation observation) {
        Objects.requireNonNull(observation, "observation");

        PhysicalObservationRegistry.RegistrationResult registration =
                observationRegistry.register(observation);

        if (registration.isStale()) {
            metrics.recordStaleObservationRejected();
            return PhysicalObservationResult.skippedStale(observation);
        }

        if (registration.isDuplicate()) {
            return PhysicalObservationResult.skippedDuplicate(observation);
        }

        if (registration.isConflict()) {
            metrics.recordPhysicalObservationWarning();
            return PhysicalObservationResult.skippedDuplicate(observation);
        }

        try {
            TrackedItemId itemId = observation.trackedItemId();

            if (!trackedItemRepository.exists(itemId)) {
                Instant now = Instant.now(clock);
                TrackedItemRecord record = new TrackedItemRecord(
                        itemId, now, now, now,
                        observation.contentKey(),
                        observation.contentKey().providerId(),
                        observation.material(),
                        "",
                        null,
                        "PHYSICAL_OBSERVATION",
                        observation.observedSubject().describe()
                );
                trackedItemRepository.create(record);
            } else {
                trackedItemRepository.observe(itemId, Instant.now(clock).toEpochMilli());
            }

            OwnershipTransitionReason reason = observation.observationReason().toTransitionReason();
            String idempotencyKey = observation.semanticTransitionKey();
            String source = "physical-observation:" + observation.observationReason().token();

            OwnershipResult ownershipResult = ownershipTransitionService.transition(
                    itemId,
                    observation.observedSubject(),
                    reason,
                    idempotencyKey,
                    source,
                    "seq=" + observation.cycle().observationSequence()
            );

            return switch (ownershipResult.status()) {
                case RECORDED -> {
                    metrics.recordPhysicalOwnershipTransition();
                    yield PhysicalObservationResult.processed(observation, ownershipResult);
                }
                case IDEMPOTENT_REPLAY -> PhysicalObservationResult.idempotentReplay(observation, ownershipResult);
                case NO_CHANGE -> PhysicalObservationResult.noChange(observation);
                case ITEM_NOT_TRACKED -> PhysicalObservationResult.itemNotTracked(observation);
                case INVALID_SUBJECT -> {
                    metrics.recordPhysicalObservationWarning();
                    yield PhysicalObservationResult.persistenceFailure(observation,
                            "Invalid subject: " + ownershipResult.optionalDiagnostic().orElse(""));
                }
                case CONFLICT -> {
                    metrics.recordPhysicalObservationWarning();
                    yield PhysicalObservationResult.persistenceFailure(observation,
                            "Conflict: " + ownershipResult.optionalDiagnostic().orElse(""));
                }
                case PERSISTENCE_FAILURE -> {
                    metrics.recordPhysicalObservationWarning();
                    yield PhysicalObservationResult.persistenceFailure(observation,
                            ownershipResult.optionalDiagnostic().orElse("persistence failure"));
                }
            };
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Physical observation processing failed for item "
                    + observation.trackedItemId(), exception);
            metrics.recordPhysicalObservationWarning();
            return PhysicalObservationResult.persistenceFailure(observation, exception.getMessage());
        }
    }
}
