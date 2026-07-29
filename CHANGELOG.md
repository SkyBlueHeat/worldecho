# Changelog

## 0.2.0-SNAPSHOT

### Added

- Initial Paper 26.2 / Java 25 project scaffold
- Committed Gradle 9.6.1 wrapper and a GitHub Actions build that runs
  `./gradlew clean test shadowJar` on Java 25
- Provider-neutral content identity model
- Generic `ProviderRegistry` with priority ordering, vanilla fallback, and automatic
  suppression of a bridge that throws
- Vanilla entity and item providers
- SQLite event persistence with versioned, idempotent migrations and indexes on
  `occurred_at`, `player_id`, and `event_type`
- Bounded single-writer queue with batched transactions, drop accounting, and a clean
  shutdown drain, plus a separate reader thread for command queries
- Player death capture that reads Bukkit state only on the server thread and hands an
  immutable event to the write path
- Deterministic, configuration-driven item value scoring with an explainable breakdown
- Validating configuration loader that replaces invalid values with documented defaults
- `/worldecho status`, `recent`, `inspect item|entity`, and `reload` with tab completion
- English and Turkish message files with locale fallback and placeholder sanitization
- `docs/CONFIGURATION.md` and `docs/TESTING.md`
- Tests for scoring, event mapping, configuration validation, messages, provider priority
  and failure isolation, migrations, repository round-trips, the write queue, and the
  shipped resources

### Fixed

- The shaded JAR relocated `org.sqlite`, which broke JNI binding for the bundled native
  library and disabled the plugin during `onEnable` with `UnsatisfiedLinkError`. The
  relocation is gone and `shadowJar` is now finalized by a smoke test that opens a real
  SQLite database using only the shaded JAR.

### Changed

- `ItemValueScorer` operates on the immutable `ItemDescriptor` instead of a Bukkit
  `ItemStack`, so scoring is pure and testable
- Bukkit-to-domain mapping is isolated in `integration/bukkit/BukkitItems`
- `/worldecho reload` only reloads configuration and messages; persistence and providers
  keep running

### Added (0.2.0-SNAPSHOT)

- Content bindings: server owners can map provider-specific content IDs to WorldEcho
  semantic roles, capabilities, faction, rank, superior, and tags via `bindings.yml`
- `BindingLoader` validates untrusted YAML, producing diagnostics for invalid entries while
  loading valid ones; a fatal error (missing/unsupported schema version) falls back to an
  empty registry
- `BindingEnricher` merges configured roles and capabilities with provider-supplied ones
  without mutating the original `IdentifiedContent`
- `BindingReloadCoordinator` extracts reload decision logic (fatal on reload keeps
  previous registry; fatal on startup falls back to empty) into testable pure-Java code
- `ContentKey.parse(String)` splits `provider:contentId` strings with validation
- `/worldecho status` now shows entity/item binding counts, warnings, errors, and schema
  version
- `/worldecho inspect item|entity` now displays binding metadata (faction, rank, superior,
  tags) when a binding exists
- `/worldecho reload` now reloads `bindings.yml` in addition to `config.yml` and messages
- `bindings.yml` shipped as a default resource with example entity and item bindings
- Tests: `BindingLoaderTest` (24 cases), `BindingRegistryTest` (9 cases),
  `BindingEnricherTest` (9 cases), `BindingReloadCoordinatorTest` (8 cases),
  and extended `BundledResourcesTest`

### Known limitations

- Validated on Paper 26.2 build 87 with Temurin 25 (enable/disable, commands, a real
  zombie kill captured from a headless bot, persistence across restart, Turkish locale and
  invalid-config fallback). Still unverified: in-game `/worldecho inspect` output,
  `record-without-valuable-item: false` with a high minimum score, and a Spark profile.
  See `docs/TESTING.md`.
- External content bridges (MythicMobs, Oraxen, ItemsAdder, Citizens, ModelEngine) are not
  implemented; only the vanilla fallback providers exist.
- The player-death listener only records a memory; it does not transfer ownership.
- Scenario definitions are not yet loaded from YAML.
- Item snapshots are intentionally minimal and must not be treated as a complete item
  serialization format.
- At most 64 drops per death are inspected, so an unusually large inventory may not have
  every item considered.
- Changing `persistence.*` requires a restart; `/worldecho reload` does not rebuild storage.
