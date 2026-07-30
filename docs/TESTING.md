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
| `BundledResourcesTest` | Shipped `config.yml` equals the built-in defaults; every English message has a Turkish counterpart; bundled `bindings.yml` is parseable with supported schema and valid example tokens |
| `ScenarioCompatibilityServiceTest` | Capability matching and rejection reasons |
| `BindingLoaderTest` | Schema validation, malformed keys, unknown roles/capabilities, wrong types, duplicate tags, missing version, partial loading |
| `BindingRegistryTest` | Entity/item namespace separation, lookup, counts, immutability, diagnostics counting |
| `BindingEnricherTest` | Role/capability merging, base content not mutated, entity/item isolation, metadata preservation |
| `BindingReloadCoordinatorTest` | Startup vs reload fatal handling, previous registry kept on fatal, idempotent reload, atomic replacement |
| `EligibilityCatalogTest` | All built-in profiles exist, unique IDs, entity/item separation, immutable collections, deterministic ordering, case-insensitive lookup |
| `EligibilityEvaluatorTest` | Eligible/not-eligible, missing role/capability/faction/rank/superior, wrong binding type, no binding, unknown profile, extra values, blank metadata, immutability, deterministic ordering, enriched content, locale regression |
| `EligibilityFormatterTest` | Profile list, eligible result, not-eligible result, summary, unknown profile, wrong binding type |

`shadowJar` is finalized by `shadowJarSmokeTest`, which opens a real SQLite database using
**only** the shaded JAR. Unit tests run against the un-shadowed classpath, so they cannot
see packaging faults; this smoke test exists because relocating `org.sqlite` once produced
a JAR that compiled, passed every test, and then failed with `UnsatisfiedLinkError` on a
real server, since the bundled native library still exports `Java_org_sqlite_core_NativeDB_*`.

Tests are pure JVM tests. Classes that touch Bukkit (listener, command, providers, plugin
lifecycle) are deliberately thin adapters over tested logic, because a Paper server cannot
be started inside this test suite.

## Verified on a real server

The slice was executed on **Paper 26.2 build 87** with **Temurin 25.0.4+7** across four
headless boots driven from the server console, with WorldEcho as the only plugin (except
the death-capture boot, see below):

- enable and clean disable, no exceptions, all three documented startup lines
- `status`, `recent`, `recent abc` (invalid count fallback), `inspect item` from console
  (players-only refusal), an unknown subcommand, and `reload`
- a **real `PlayerDeathEvent`**: a headless bot carrying a diamond sword was killed by a
  naturally spawned zombie, producing exactly one row with
  `actor=vanilla:minecraft:zombie item=vanilla:minecraft:diamond_sword`
- a skeleton kill recorded as `actor=vanilla:minecraft:skeleton`, never `minecraft:arrow`,
  confirming projectile shooter resolution
- a fall death recorded nothing, confirming the listener ignores non-entity causes
- 10 deaths: `queue.written: 10, failed: 0, dropped: 0`, shutdown drained all of them,
  the stored row contained every column and `score=45;scoreFactors=material\=45`
- restart: `0 migration(s) applied` and all rows survived with identical IDs
- `locale: tr` plus a deliberately invalid `minimum-item-score: "abc"`: Turkish output,
  the value fell back to `25` with a warning, and the plugin stayed enabled

The death-capture boot additionally had ViaVersion/ViaBackwards installed, because no bot
library speaks the 26.2 protocol yet; the "starts with no integrations" requirement was
verified on the other three boots.

## Still unverified

- `/worldecho inspect item|entity` output in a real client chat window (console can only
  confirm the players-only refusal)
- `record-without-valuable-item: false` combined with a high `minimum-item-score`
- a Spark profile proving no SQLite frames appear on the server thread
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
