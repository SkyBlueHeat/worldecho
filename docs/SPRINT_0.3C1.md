# Sprint 0.3C1 — UNIQUE World Drops & Entity Ownership

## Goal

Implement automatic physical ownership observations for persistent UNIQUE items
across PLAYER → WORLD_DROP → ENTITY → WORLD_DROP → PLAYER transitions without
player or administrator commands during normal gameplay.

## Scope

UNIQUE items have persistent physical identity and ownership history.

LOT records remain owner-scoped commodity aggregates. Sprint 0.3C1 does not
physically track LOT stacks outside player inventories.

This sprint applies **only to persistent UNIQUE items** — items carrying a
`worldecho:item_id` PDC tag or classified as UNIQUE by the existing identity
policy. LOT commodities are not physically tracked in this sprint.

## What was implemented

### Domain models

| Model | Purpose |
| --- | --- |
| `PhysicalObservationReason` | 7-value enum mapping physical events to ownership transition reasons |
| `PhysicalUniqueItemObservation` | Immutable observation record with deterministic idempotency keys |
| `PhysicalObservationCycle` | Session ID + monotonic sequence for ordering and stale rejection |
| `PhysicalObservationResult` | Processing result with 10 status values |
| `PhysicalObservationRegistry` | In-memory registry detecting duplicate, stale, and conflicting observations |
| `PhysicalUniqueItemObservationService` | Async service ensuring tracked records and ownership transitions |

### Event listeners

| Listener | Events | Behavior |
| --- | --- | --- |
| `WorldDropObservationListener` | `PlayerDropItemEvent`, `ItemSpawnEvent` | PLAYER → WORLD_DROP on drop; WORLD_DROP on generic spawn; identity assignment for untracked UNIQUE items |
| `EntityItemOwnershipListener` | `EntityPickupItemEvent`, `EntityDeathEvent` | WORLD_DROP → ENTITY on non-player pickup; bounded warning for equipped UNIQUE items absent from death drops (no terminal transition written) |
| `ItemDespawnListener` | `ItemDespawnEvent` | WORLD_DROP → SYSTEM:item-despawned terminal observation |
| `LoadedEntityReconciliationListener` | `EntitiesLoadEvent` | Reconciles loaded Item entities (WORLD_DROP) and entity equipment (ENTITY) after chunk load or restart |

### Ownership subjects

- **WORLD_DROP**: `WORLD_DROP:<item-entity-uuid>` — Bukkit Item entity UUID as stable ID
- **ENTITY**: `ENTITY:<entity-uuid>` — entity UUID as stable ID
- **SYSTEM**: `SYSTEM:item-despawned` — terminal observation for despawned items. `SYSTEM:item-destroyed` is deferred to Sprint 0.3C2.

### Observation ordering

A shared `PhysicalObservationSequencer` (one `AtomicLong` + server session ID)
is injected into all four listeners, providing globally monotonic sequences.
A later ENTITY observation can never have a lower sequence than an earlier
WORLD_DROP observation. Stale observations (lower sequence than already-processed)
are rejected. Conflicts (same sequence, different non-terminal subjects) are
detected and rejected. Terminal SYSTEM subjects never conflict with any subject.

### Semantic idempotency

`PhysicalUniqueItemObservation.semanticTransitionKey()` groups observations
representing the same physical ownership transition regardless of event source:

```
physical:<subject-type>:<tracked-item-id>:<subject-stable-id>
```

For example, `PlayerDropItemEvent` (DROPPED) and `ItemSpawnEvent`
(WORLD_DROP_OBSERVED) for the same Item entity produce:

```
physical:world-drop:<tracked-item-id>:<item-entity-uuid>
```

The second observation is treated as an idempotent replay, not a duplicate
transition. Observation sequence remains available for ordering but does not
make the same physical transition semantically unique.

For SYSTEM subjects, the observation-level idempotency key is used because
terminal observations should not be deduplicated across different events.

CONFLICT results from the transition service where the current ownership
subject matches the observation's subject are treated as idempotent replays.

### Threading and bounded pending work

All Bukkit/Paper API calls occur on the main thread. Immutable snapshots are
captured on the main thread and submitted to a `BoundedPhysicalObservationQueue`
with configurable capacity (`max-pending-capacity`, default 256). When the
queue is full, observations are rejected and a `rejectedPhysicalObservations`
metric is incremented. Shutdown drains the queue within the configured timeout.
No Bukkit objects are retained asynchronously.

### Configuration

```yaml
items:
  physical-tracking:
    enabled: true
    world-drops: true
    entity-pickup: true
    reconcile-loaded-entities: true
    item-despawn: true
    debug-messages: false
    max-pending-capacity: 256
```

### Status metrics

`/worldecho status` now reports:
- `physical-tracking: enabled`
- `world-drop-observations`
- `entity-item-observations`
- `loaded-entity-reconciliations`
- `physical-ownership-transitions`
- `physical-observation-warnings`
- `stale-observations-rejected`
- `pending-physical-observations`
- `rejected-physical-observations`

### Localization

English and Turkish message keys added for:
- physical tracking disabled
- malformed physical identity
- duplicate physical observation
- stale observation rejected
- persistence failure
- unsupported subject

### Database

No schema migration required. Physical observations use the existing
`tracked_items` and `item_ownership_ledger` tables (schema version 4).

### Tests

| Test class | Tests | Coverage |
| --- | --- | --- |
| `PhysicalObservationReasonTest` | 11 | Token parsing, transition reason mapping, error cases |
| `PhysicalObservationRegistryTest` | 13 | Acceptance, duplicate, stale, conflict (WORLD_DROP vs ENTITY, PLAYER vs WORLD_DROP, PLAYER vs ENTITY, ENTITY vs ENTITY, WORLD_DROP vs WORLD_DROP), terminal, session isolation, clear, stale expiration |
| `PhysicalUniqueItemObservationTest` | 11 | Idempotency key determinism, subject/reason/sequence differences, optional UUID, semantic transition key grouping and SYSTEM fallback |
| `PhysicalUniqueItemObservationServiceTest` | 11 | Process, duplicate, stale, idempotent replay (same subject), despawn, entity held, transitioned flag, conflict warning metric |
| `OwnershipSubjectWorldDropUuidTest` | 4 | worldDrop(UUID) factory, display name, lowercase, vs location-based |
| `PhysicalObservationIntegrationTest` | 14 | Full chain, restart, despawn, stale, idempotent, service neutrality, history preservation, concurrent drop+spawn dedup |
| `PhysicalObservationSequencerTest` | 5 | Global monotonicity, cross-listener ordering, no duplicate sequences, valid cross-listener transitions, concurrent uniqueness |
| `BoundedPhysicalObservationQueueTest` | 4 | Rejection at capacity, pending count, shutdown drain, max capacity |

**Total: 461 tests, 0 failures, 0 skipped.**

## Paper command gate (headless server)

Final JAR deployed to `C:\Users\erkay\Documents\WorldEcho-TestServer\plugins\`.
Fresh database (deleted `worldecho.db` before start). Paper 26.2, Java 25, headless mode.

### `worldecho status` (first run)

```
[WorldEcho] Memory kernel status
[WorldEcho] version: 0.1.0-SNAPSHOT
[WorldEcho] locale: en
[WorldEcho] queue.pending: 0
[WorldEcho] queue.written: 0
[WorldEcho] queue.failed: 0
[WorldEcho] queue.dropped: 0
[WorldEcho] providers: entity:vanilla=AVAILABLE, item:vanilla=AVAILABLE
[WorldEcho] bindings.entities: 3
[WorldEcho] bindings.items: 2
[WorldEcho] bindings.warnings: 0
[WorldEcho] bindings.errors: 0
[WorldEcho] bindings.schema-version: 1
[WorldEcho] eligibility.profiles: 4
[WorldEcho] eligibility.entity-profiles: 3
[WorldEcho] eligibility.item-profiles: 1
[WorldEcho] auto-tracking: enabled
[WorldEcho] physical-tracking: enabled
[WorldEcho] recon.count: 0
[WorldEcho] recon.identities: 0
[WorldEcho] recon.lots: 0
[WorldEcho] recon.ownership: 0
[WorldEcho] recon.warnings: 0
[WorldEcho] recon.duplicates: 0
[WorldEcho] recon.pending: 0
[WorldEcho] phys.world-drops: 0
[WorldEcho] phys.entity-items: 0
[WorldEcho] phys.loaded-reconciles: 0
[WorldEcho] phys.ownership-transitions: 0
[WorldEcho] phys.warnings: 0
[WorldEcho] phys.stale-rejected: 0
[WorldEcho] phys.pending: 0
[WorldEcho] phys.rejected: 0
[WorldEcho] database: ok
[WorldEcho] schema.version: 4
[WorldEcho] events: 0
[WorldEcho] tracked-items: 0
[WorldEcho] ledger-entries: 0
```

### `worldecho reload`

```
[WorldEcho] Bindings: 3 entity, 2 item, 0 warning(s), 0 error(s)
[WorldEcho] Configuration, messages, and bindings reloaded.
[WorldEcho] Bindings: 3 entity, 2 item, 0 warning(s), 0 error(s)
```

### `worldecho status` (after reload)

All metrics identical to first run. Physical tracking still enabled. Database OK. Schema version 4.

### `worldecho item owner 00000000-0000-0000-0000-000000000001`

```
[WorldEcho] Current ownership for 00000000-0000-0000-0000-000000000001
[WorldEcho] No ownership history recorded.
```

### `worldecho item history 00000000-0000-0000-0000-000000000001`

```
[WorldEcho] Ownership history for 00000000-0000-0000-0000-000000000001
[WorldEcho] No ownership history recorded.
```

### `stop` (clean shutdown)

```
[WorldEcho] Disabling WorldEcho v0.1.0-SNAPSHOT
[WorldEcho] Automatic tracking metrics: reconciliations=0 identities-assigned=0 lots-assigned=0 ownership-transitions=0 warnings=0 duplicates=0 world-drop-observations=0 entity-item-observations=0 loaded-entity-reconciliations=0 physical-ownership-transitions=0 physical-observation-warnings=0 stale-observations-rejected=0 pending-physical-observations=0 rejected-physical-observations=0
[WorldEcho] Story write queue drained: 0 event(s) stored
[WorldEcho] Physical observation queue drained: pending=0 submitted=0 rejected=0
[WorldEcho] WorldEcho memory kernel disabled
```

### Verification summary

- Physical tracking enabled: YES
- All physical status metrics present: YES (8 metrics including `phys.rejected`)
- Pending count zero: YES
- Database OK: YES
- Schema version 4: YES
- Reload successful: YES
- Unknown ID handled safely: YES (no errors, graceful "No ownership history recorded")
- Clean shutdown: YES (physical observation queue drained, story write queue drained)

## Known limitations

- Entity death handling logs a bounded warning for equipped UNIQUE items absent
  from death drops. No terminal `SYSTEM:item-destroyed` transition is written.
  Terminal SYSTEM observations are deferred until explicit death-drop correlation
  exists (Sprint 0.3C2). Items that appear in death drops are reconciled via
  `ItemSpawnEvent`. Edge case: if an item is destroyed by game mechanics (e.g.,
  lava, cactus) after being dropped as a death drop, the `ItemDespawnEvent` may
  not fire reliably.
- Duplicate observation detection is in-memory only and per-session.
- Bounded queue capacity defaults to 256; bursts exceeding this capacity result
  in rejected observations (metric incremented, warning logged).
- No container ownership tracking (Sprint 0.3C2).
- No physical LOT stack tracking.
- Real-player physical ownership verification: NOT PERFORMED
  Reason: No licensed Minecraft Java client was available.

## Deferred to Sprint 0.3C2

- Container ownership (chests, hoppers, barrels)
- Shulker box and bundle nested content tracking
- Hopper transfer tracking
- Container crawling
- Entity death drop tracking with explicit death-drop correlation and terminal
  SYSTEM:item-destroyed transitions
