# WorldEcho

> Working title: **WorldEcho — The World Remembers**

WorldEcho is a Paper gameplay plugin and integration framework that turns a server's existing mobs, items, NPCs, factions, and player actions into persistent stories.

The server owner supplies content through plugins such as MythicMobs, Oraxen, ItemsAdder, Citizens, and ModelEngine. WorldEcho supplies the memory graph, semantic content model, scenario compatibility rules, delayed consequences, rumors, item history, faction command chains, and world chronicle.

## Status

This repository is a **Sprint 1 starter scaffold**, not a public release.

Current implemented foundation:

- Paper 26.2 / Java 25 Gradle project
- SQLite schema and repository
- asynchronous single-writer persistence queue
- provider-neutral content identity API
- vanilla entity and item providers
- integration registry foundation
- player-death memory capture
- valuable-drop candidate detection
- scenario compatibility domain model
- `/worldecho status`, `/worldecho recent`, and `/worldecho inspect`
- English and Turkish configuration/message placeholders
- unit tests for capability matching

## Core product rule

**WorldEcho does not create the server owner's art assets. It makes their content remember and affect one another.**

## Build

Use Java 25 and Gradle 9.6.1.

```bash
gradle wrapper --gradle-version 9.6.1
./gradlew clean build
```

The distributable JAR is produced under:

```text
build/libs/worldecho-0.1.0-SNAPSHOT.jar
```

## Runtime

Place the JAR in a Paper 26.2 server's `plugins` directory.

This first scaffold has no hard dependency on any external content plugin.

## Documentation

- `AGENTS.md` — engineering rules for Devin and other coding agents
- `docs/PRODUCT_SPEC.md` — complete product direction
- `docs/ARCHITECTURE.md` — target architecture
- `docs/SPRINT_1.md` — first implementation slice
- `docs/ROADMAP.md` — staged delivery plan
- `DEVIN_PROMPT.md` — ready-to-paste Devin Agent prompt

## Important

`WorldEcho` is a working name. Perform a complete name, platform, domain, and trademark check before public release.
