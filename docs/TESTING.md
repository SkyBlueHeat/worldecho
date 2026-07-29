# Testing

## Automated tests

```bash
./gradlew clean test shadowJar
```

Requires JDK 25. The wrapper pins Gradle 9.6.1.

| Test | What it protects |
| --- | --- |
| `ItemValueScorerTest` | Deterministic score ordering, configurable weights, custom items are not automatically valuable |
| `DeathMemoryFactoryTest` | Capture → immutable event mapping, snapshot escaping, custom-name redaction |
| `SettingsLoaderTest` | Invalid YAML types and out-of-range values fall back to safe defaults with warnings |
| `MessageCatalogTest` | Locale fallback, visible missing keys, placeholder sanitization |
| `ProviderRegistryTest` | Provider priority, vanilla fallback, a throwing bridge is suppressed instead of breaking the core |
| `SchemaMigratorTest` | Empty-database migration, idempotent re-run, required indexes |
| `SqliteStoryEventRepositoryTest` | Round-trip of every column, recency ordering, history is never overwritten |
| `StoryWriteQueueTest` | Shutdown drains the queue, saturation drops instead of blocking, failures are counted |
| `BundledResourcesTest` | Shipped `config.yml` equals the built-in defaults; every English message has a Turkish counterpart |
| `ScenarioCompatibilityServiceTest` | Capability matching and rejection reasons |

Tests are pure JVM tests. Classes that touch Bukkit (listener, command, providers, plugin
lifecycle) are deliberately thin adapters over tested logic, because a Paper server cannot
be started inside this test suite.

## What automated tests cannot cover

The following was **not** verified on a real Paper 26.2 server; no Minecraft server or
client was available in the build environment:

- plugin enable/disable inside a running server
- actual `PlayerDeathEvent` capture, including killer and projectile-shooter resolution
- `Tag.ITEMS_*` and `EntityType` behavior at runtime
- `/worldecho status`, `recent`, `inspect`, and `reload` output in-game
- MiniMessage rendering in the client chat window
- behavior with MythicMobs, Oraxen, ItemsAdder, Citizens, or ModelEngine installed

## Manual verification on a Paper server

1. Install a Paper 26.2 server on Java 25.
2. Copy `build/libs/worldecho-0.1.0-SNAPSHOT.jar` into `plugins/`.
3. Start the server with **no** other plugins and confirm the log shows:
   - `Storage ready at .../plugins/WorldEcho/worldecho.db (1 migration(s) applied)`
   - `Content providers: entity:vanilla=AVAILABLE, item:vanilla=AVAILABLE`
   - `WorldEcho memory kernel enabled`
4. Run `/worldecho status` and confirm version, locale, queue counters, providers,
   database `ok`, schema version, and event count.
5. Hold a diamond sword and run `/worldecho inspect item`; the score breakdown must list
   `material=45`.
6. Look at a mob and run `/worldecho inspect entity`; roles and capabilities must appear.
7. Let a zombie kill you while carrying a diamond sword, then run `/worldecho recent`.
   Exactly one row must appear with `actor=vanilla:minecraft:zombie` and
   `item=vanilla:minecraft:diamond_sword`.
8. Die by fall damage and confirm **no** new row is created.
9. Set `capture.player-deaths.record-without-valuable-item: false` and
   `minimum-item-score: 1000`, run `/worldecho reload`, and confirm a death produces no row.
10. Set `locale: tr`, run `/worldecho reload`, and confirm Turkish output.
11. Stop the server and confirm the log reports `Story write queue drained: N event(s) stored`.
12. Restart and confirm `/worldecho recent` still lists the previous rows and that the log
    reports `0 migration(s) applied`.

### Checking the thread rules

- With a profiler such as Spark, run `/spark profiler start`, kill players repeatedly, and
  confirm no SQLite frames appear on the server thread. All JDBC work must run on
  `worldecho-sqlite-writer` or `worldecho-sqlite-reader`.
- `/worldecho recent 50` must return without a tick spike; the query runs on the reader
  thread and only the message send happens on the server thread.
