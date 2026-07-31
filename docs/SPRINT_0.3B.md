# Sprint 0.3B — Automatic Item Tracking

## Objective

Automatically assign `UNIQUE` or `LOT` identities to items entering active player inventories, reconcile ownership, and preserve Minecraft stacking behavior — without requiring players to manually track items.

## Summary

This sprint introduces a complete automatic item identity and ownership synchronization system:

- **Identity classification**: Items are classified as `UNIQUE` (one-of-a-kind, tracked individually) or `LOT` (stackable, tracked as a lot) using a pure-Java policy engine.
- **Automatic PDC assignment**: UNIQUE items get a WorldEcho UUID written to their Persistent Data Container on first observation.
- **Lot fingerprinting**: LOT items are grouped by a deterministic `LotCompatibilityFingerprint` that normalizes material, damage, enchantments, and provider.
- **Ownership reconciliation**: Every reconciliation cycle records ownership transitions idempotently, using deterministic cycle-scoped keys.
- **Coalescing scheduler**: Multiple inventory events in the same tick coalesce into a single next-tick reconciliation — no every-tick scanner, no unbounded task creation.
- **Event-driven**: Join, respawn, inventory click/drag, pickup, and death events trigger reconciliation.
- **Transformation identity continuity**: Anvil, smithing table, and grindstone transformations preserve the source item's WorldEcho UUID on the result item.
- **Silent operation**: No chat spam for normal players. Metrics are visible via `/worldecho status`.
- **Admin commands**: `/worldecho item reconcile [player]` and `/worldecho item policy` for manual control and diagnostics.

## New domain types

| Type | Package | Purpose |
|------|---------|---------|
| `IdentityMode` | `domain.item` | Enum: `UNIQUE`, `LOT` |
| `ObservedItemDescriptor` | `domain.item` | Immutable snapshot for classification |
| `IdentityClassificationResult` | `domain.item` | Classification result with confidence and reasons |
| `ItemIdentityPolicy` | `domain.item` | Pure-Java classification logic |
| `LotCompatibilityFingerprint` | `domain.item` | Deterministic lot compatibility key |
| `TrackedItemLotId` | `domain.item` | UUID-based lot identifier |
| `TrackedItemLot` | `domain.item` | Persistent lot record |
| `LotRelationType` | `domain.item` | Enum: `SPLIT_FROM`, `MERGED_INTO` |
| `LotLineageEntry` | `domain.item` | Lineage record for lot splits/merges |
| `LotOwnershipLedgerEntry` | `domain.item` | Append-only lot ownership ledger entry |
| `LotOwnershipState` | `domain.item` | Current ownership projection for a lot |
| `LotOwnershipTransitionService` | `domain.item` | Validates and records lot ownership transitions |
| `AutomaticItemIdentityService` | `domain.item` | Coordinates identity assignment + persistence |
| `ReconciliationCycle` | `domain.item` | Stable identifier for a single reconciliation cycle |
| `ObservedInventorySlot` | `domain.item` | Immutable inventory slot snapshot |
| `ObservedInventorySnapshot` | `domain.item` | Immutable full inventory snapshot |
| `SlotProcessResult` | `domain.item` | Result of processing a single slot |
| `ReconciliationMetrics` | `domain.item` | Thread-safe metrics counters |
| `DuplicateObservationRegistry` | `domain.item` | Bounded in-memory duplicate detection |

## New persistence types

| Type | Purpose |
|------|---------|
| `TrackedItemLotRepository` | Interface for lot CRUD + lineage |
| `SqliteTrackedItemLotRepository` | SQLite implementation |
| `LotOwnershipLedgerRepository` | Interface for lot ownership ledger |
| `SqliteLotOwnershipLedgerRepository` | SQLite implementation |

## Schema migration v3

Adds three new tables with indexes:

- `tracked_item_lots` — lot records with fingerprint, amounts, tracking metadata
- `lot_lineage` — split/merge lineage with idempotency keys
- `item_lot_ownership_ledger` — append-only lot ownership transitions with sequence numbers

Indexes: `idx_tracked_lots_fingerprint`, `idx_tracked_lots_content_key`, `idx_lot_lineage_lot_id`, `idx_lot_lineage_related_lot`, `idx_lot_ledger_lot_seq_desc`, `idx_lot_ledger_new_subject`

## New Paper-layer types

| Type | Purpose |
|------|---------|
| `PlayerInventoryReconciler` | Captures immutable snapshots on main thread, processes asynchronously |
| `PlayerInventoryReconciliationScheduler` | Coalesces events per-player into next-tick tasks |
| `PlayerInventoryObservationListener` | Listens for join, respawn, click, drag, pickup, death |

## Configuration

New `items.automatic-tracking` section in `config.yml`:

```yaml
items:
  automatic-tracking:
    enabled: true
    player-inventories: true
    reconcile-on-join: true
    reconcile-on-respawn: true
    reconcile-after-inventory-events: true
    debug-messages: false
```

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/worldecho item reconcile [player]` | `worldecho.item.reconcile` | Triggers manual inventory reconciliation |
| `/worldecho item policy` | `worldecho.item.policy` | Shows identity classification for held item |

## Status metrics

`/worldecho status` now shows:
- `auto-tracking`: enabled/disabled
- `recon.count`: total reconciliations
- `recon.identities`: UNIQUE identities assigned
- `recon.lots`: LOT identities assigned
- `recon.ownership`: ownership transitions recorded
- `recon.warnings`: identity warnings
- `recon.pending`: pending reconciliations

## Threading rules

- Bukkit API calls (inventory reads, PDC writes) happen on the main thread only
- Database persistence happens on the single-threaded query executor
- Immutable snapshots are captured on the main thread and processed asynchronously
- No SQLite reads or writes on the main server thread

## Tests

- 246 tests total, 0 failures
- New tests: `ItemIdentityPolicyTest` (14), `LotCompatibilityFingerprintTest` (7), `ReconciliationCycleTest` (4), `ReconciliationMetricsTest` (4), `DuplicateObservationRegistryTest` (5), `TrackedItemLotRepositoryTest` (8), `SchemaMigratorTest` +1 v3 test

## Known limitations

- Duplicate observation detection is in-memory only and per-session; it does not persist across restarts
- Lot amount tracking is approximate during concurrent inventory modifications
- Transformation identity continuity covers anvil, smithing table, and grindstone; other crafting mechanics (crafting table, stonecutter) are not yet covered
