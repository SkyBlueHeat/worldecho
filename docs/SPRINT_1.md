# Sprint 1 — Memory Kernel

## Goal

Prove that WorldEcho can capture a meaningful player action, identify the participating content without provider-specific coupling, persist it safely, and expose the result to an administrator.

## Primary vertical slice

> A player is killed by a living entity while carrying at least one valuable dropped item. WorldEcho records a provider-neutral death event with the killer identity and the best loot candidate.

This sprint does **not** transfer the item to a captain. It creates the reliable memory foundation required for that later scenario.

## Required deliverables

1. Paper 26.2 / Java 25 project builds.
2. Plugin starts with no optional integrations installed.
3. SQLite initializes with versioned migrations.
4. Database work is off the main thread.
5. Vanilla entity and item identification works.
6. Player death event captures:
   - event ID
   - timestamp
   - player UUID
   - world and block location
   - killer provider and content ID
   - selected loot provider and content ID
   - selected loot display snapshot
7. `/worldecho status` reports plugin, DB, queue, and providers.
8. `/worldecho recent` shows recent captured memories.
9. `/worldecho inspect` identifies the held item or targeted entity.
10. Pure domain scenario-capability matching has tests.
11. Build workflow runs tests and uploads the JAR.
12. README documents local setup.

## Acceptance tests

- Server starts without MythicMobs, Oraxen, ItemsAdder, Citizens, or ModelEngine.
- Killing a player with a zombie and dropping a diamond sword creates one event.
- A death without an entity killer is ignored or recorded according to config.
- A death with no qualifying item records the event with no loot candidate.
- Reload/restart does not destroy existing records.
- Rapid repeated events do not write directly on the main thread.
- Invalid configuration produces a clear warning and safe defaults.
- `/worldecho recent` does not block the main thread.
- `./gradlew clean test shadowJar` passes.

## Deferred

- MythicMobs bridge
- actual item ownership transfer
- captain/superior lookup
- factions
- rumors
- player-facing journal
- story instance state machine
- Oraxen and ItemsAdder item creation
- Citizens NPC relationships
