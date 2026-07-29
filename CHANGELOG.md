# Changelog

## 0.1.0-SNAPSHOT

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

### Changed

- `ItemValueScorer` operates on the immutable `ItemDescriptor` instead of a Bukkit
  `ItemStack`, so scoring is pure and testable
- Bukkit-to-domain mapping is isolated in `integration/bukkit/BukkitItems`
- `/worldecho reload` only reloads configuration and messages; persistence and providers
  keep running

### Known limitations

- Not validated inside a running Paper server: no Minecraft server or client was available
  in the build environment. See `docs/TESTING.md` for the manual checklist.
- External content bridges (MythicMobs, Oraxen, ItemsAdder, Citizens, ModelEngine) are not
  implemented; only the vanilla fallback providers exist.
- The player-death listener only records a memory; it does not transfer ownership.
- Scenario definitions are not yet loaded from YAML.
- Item snapshots are intentionally minimal and must not be treated as a complete item
  serialization format.
- At most 64 drops per death are inspected, so an unusually large inventory may not have
  every item considered.
- Changing `persistence.*` requires a restart; `/worldecho reload` does not rebuild storage.
