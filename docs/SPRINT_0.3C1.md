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
| `EntityItemOwnershipListener` | `EntityPickupItemEvent`, `EntityDeathEvent` | WORLD_DROP → ENTITY on non-player pickup; terminal SYSTEM observation if equipped UNIQUE item not in death drops |
| `ItemDespawnListener` | `ItemDespawnEvent` | WORLD_DROP → SYSTEM:item-despawned terminal observation |
| `LoadedEntityReconciliationListener` | `EntitiesLoadEvent` | Reconciles loaded Item entities (WORLD_DROP) and entity equipment (ENTITY) after chunk load or restart |

### Ownership subjects

- **WORLD_DROP**: `WORLD_DROP:<item-entity-uuid>` — Bukkit Item entity UUID as stable ID
- **ENTITY**: `ENTITY:<entity-uuid>` — entity UUID as stable ID
- **SYSTEM**: `SYSTEM:item-despawned` or `SYSTEM:item-destroyed` — terminal observations

### Observation ordering

Observations are ordered by a monotonic sequence number within a server session.
Stale observations (lower sequence than already-processed) are rejected.
Conflicts (same sequence, different non-terminal subjects) are detected and rejected.
Terminal SYSTEM subjects never conflict with any subject.

### Idempotency

Deterministic idempotency keys are generated from:
- server session ID
- observation cycle sequence
- tracked item ID
- observed subject type + stable ID
- observation reason

Repeated processing of the same observation produces `SKIPPED_DUPLICATE`.

### Threading

All Bukkit/Paper API calls occur on the main thread. Immutable snapshots are
captured on the main thread and processed asynchronously via the existing
SQLite executor. No Bukkit objects are retained asynchronously.

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
| `PhysicalUniqueItemObservationTest` | 7 | Idempotency key determinism, subject/reason/sequence differences, optional UUID |
| `PhysicalUniqueItemObservationServiceTest` | 11 | Process, duplicate, stale, no-change, despawn, entity held, idempotent replay, transitioned flag, conflict warning metric |
| `OwnershipSubjectWorldDropUuidTest` | 4 | worldDrop(UUID) factory, display name, lowercase, vs location-based |
| `PhysicalObservationIntegrationTest` | 13 | Full chain, restart, despawn, stale, idempotent, service neutrality, history preservation |

## Known limitations

- Entity death handling records terminal SYSTEM observations only for equipped
  UNIQUE items that do not appear in death drops. Items that do appear in drops
  are reconciled via `ItemSpawnEvent`. Edge case: if an item is destroyed by
  game mechanics (e.g., lava, cactus) after being dropped as a death drop, the
  `ItemDespawnEvent` may not fire reliably.
- Duplicate observation detection is in-memory only and per-session.
- No container ownership tracking (Sprint 0.3C2).
- No physical LOT stack tracking.
- Real-player physical ownership verification: NOT PERFORMED
  Reason: No licensed Minecraft Java client was available.

## Deferred to Sprint 0.3C2

- Container ownership (chests, hoppers, barrels)
- Shulker box and bundle nested content tracking
- Hopper transfer tracking
- Container crawling
- Entity death drop tracking with explicit death-drop correlation
