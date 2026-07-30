# WorldEcho

> Working title: **WorldEcho — The World Remembers**

WorldEcho is a Paper gameplay plugin and integration framework that turns a server's existing mobs, items, NPCs, factions, and player actions into persistent stories.

The server owner supplies content through plugins such as MythicMobs, Oraxen, ItemsAdder, Citizens, and ModelEngine. WorldEcho supplies the memory graph, semantic content model, scenario compatibility rules, delayed consequences, rumors, item history, faction command chains, and world chronicle.

## Status

This repository implements **Sprint 0.2B — Scenario Eligibility Diagnostics**, extending
the Sprint 0.2A content bindings with a pure-Java eligibility diagnostic system.

The implemented vertical slice is:

> When a living entity kills a player, WorldEcho identifies the killer through the provider
> registry, selects the most valuable eligible dropped item, creates an immutable
> provider-neutral memory event, persists it asynchronously, and exposes it through admin
> commands.

Current foundation:

- Paper 26.2 / Java 25 Gradle project with a committed Gradle 9.6.1 wrapper
- SQLite storage with versioned, idempotent migrations and indexed lookups
- bounded single-writer persistence queue and a separate reader thread
- provider-neutral content identity API with priority ordering and vanilla fallback
- a failing bridge suppresses itself instead of disabling the core
- player-death capture that reads Bukkit state only on the server thread
- deterministic, configuration-driven item value scoring with an explainable breakdown
- content bindings: `bindings.yml` maps provider content IDs to semantic roles,
  capabilities, faction, rank, superior, and tags; bindings enrich but never replace
  provider-supplied metadata
- scenario eligibility diagnostics: built-in profiles evaluate whether bound content is
  semantically ready for future story roles; structured diagnostics with stable codes
- `/worldecho status`, `recent`, `inspect item|entity`, `reload`, and
  `eligibility profiles|check|all`
- English and Turkish message files with sanitized placeholders
- unit tests for scoring, mapping, configuration, messages, providers, migrations,
  persistence, content bindings, and the shipped resources

Not implemented yet (Sprint 0.3 and later): item ownership transfer, captains,
factions, rumors, story generation, and external content bridges.

## Core product rule

**WorldEcho does not create the server owner's art assets. It makes their content remember and affect one another.**

## Build

Requires JDK 25. The Gradle wrapper is committed, so no local Gradle installation is
needed.

```bash
./gradlew clean test shadowJar
```

The distributable JAR is produced under:

```text
build/libs/worldecho-0.1.0-SNAPSHOT.jar
```

SQLite is shaded into the JAR (deliberately **not** relocated, so its native library keeps
binding) and the Paper API is not packaged. `shadowJar` is finalized by a smoke test that
opens a SQLite database using only the shaded JAR.

## Runtime

Place the JAR in a Paper 26.2 server's `plugins` directory.

WorldEcho has no hard dependency on any external content plugin and starts with none
installed.

### Commands

All subcommands require the `worldecho.admin` permission (default: op).

| Command | Description |
| --- | --- |
| `/worldecho status` | Version, locale, queue counters, providers, database health, schema version, event count |
| `/worldecho recent [count]` | Most recent memories, read off the server thread |
| `/worldecho inspect item` | Provider, content ID, roles, capabilities, binding metadata, and the score breakdown of the held item |
| `/worldecho inspect entity` | Provider, content ID, roles, capabilities, and binding metadata of the entity you are looking at |
| `/worldecho reload` | Re-reads `config.yml`, `bindings.yml`, and the message files |

## Documentation

- `AGENTS.md` — engineering rules for Devin and other coding agents
- `docs/PRODUCT_SPEC.md` — complete product direction
- `docs/ARCHITECTURE.md` — target architecture
- `docs/SPRINT_1.md` — first implementation slice
- `docs/ROADMAP.md` — staged delivery plan
- `docs/CONFIGURATION.md` — every configuration key and the scoring formula
- `docs/TESTING.md` — automated coverage, what was verified on a real Paper 26.2 server,
  and the manual checklist
- `DEVIN_PROMPT.md` — ready-to-paste Devin Agent prompt

## Important

`WorldEcho` is a working name. Perform a complete name, platform, domain, and trademark check before public release.
