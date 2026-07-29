# Configuration

Every value in `config.yml` is validated on load. An invalid value never prevents the
plugin from starting: it is replaced by the default documented here and reported as a
warning in the server log and in `/worldecho reload` output.

Files live in `plugins/WorldEcho/`:

| File | Purpose |
| --- | --- |
| `config.yml` | Capture rules, item scoring, persistence, command limits |
| `messages_en.yml` | English messages (also the fallback for missing keys) |
| `messages_tr.yml` | Turkish messages |
| `worldecho.db` | SQLite database (created automatically) |

## Top level

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `locale` | `en` or `tr` | `en` | Which message file is used. Unknown values fall back to `en`. |

## capture.player-deaths

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `enabled` | boolean | `true` | Record "a living entity killed a player" memories. |
| `record-without-valuable-item` | boolean | `true` | Store the memory even when no drop reaches `minimum-item-score`. |
| `minimum-item-score` | 0–10000 | `25` | Lowest score a drop needs before it can become the memory's item. |
| `redact-custom-item-names` | boolean | `false` | Replace custom item names with an empty value in stored snapshots. |
| `ignored-worlds` | list of strings | `[]` | Worlds that never produce memories. Matched case-insensitively. |

A death is ignored when the damage source has no living causing entity, for example a fall
or lava death. Projectiles are attributed to their shooter.

## scoring

Item scoring is deterministic: the same item always produces the same score and the same
explanation, which `/worldecho inspect item` prints factor by factor.

```text
score = material
      + (enchantment-base + enchantment-per-level * level) per enchantment
      + custom-name-bonus       (only when the item has a custom name)
      + unbreakable-bonus       (only when the item is unbreakable)
      + provider-identified-bonus (only when a non-vanilla provider claimed the item)
      + min(amount-bonus-cap, amount - 1)
      - round(wear * wear-penalty-cap)
```

The result is clamped to a minimum of `0`.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `material-scores` | map | see `config.yml` | Exact material paths, for example `elytra: 55`. Wins over `material-tiers`. |
| `material-tiers` | map | see `config.yml` | Material path prefixes, for example `netherite_: 60`. Longest match wins. |
| `default-material-score` | 0–10000 | `1` | Used when no exact score and no tier matches. |
| `enchantment-base` | 0–10000 | `4` | Points for each enchantment. |
| `enchantment-per-level` | 0–10000 | `2` | Extra points per enchantment level. |
| `custom-name-bonus` | 0–10000 | `8` | Applied once for a renamed item. |
| `unbreakable-bonus` | 0–10000 | `12` | Applied once for an unbreakable item. |
| `provider-identified-bonus` | 0–10000 | `10` | Applied when a bridge such as Oraxen claimed the item. |
| `amount-bonus-cap` | 0–10000 | `5` | Maximum bonus from stack size. |
| `wear-penalty-cap` | 0–10000 | `10` | Maximum penalty for a fully damaged item. |

Custom provider items are **not** automatically valuable: they receive one bonus and are
still scored on their material, enchantments, and condition.

## persistence

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sqlite-file` | string | `worldecho.db` | Database file inside the plugin data folder. |
| `shutdown-timeout-seconds` | 1–300 | `10` | How long `onDisable` waits for the writer thread to flush. |
| `write-queue-capacity` | 16–100000 | `2000` | Queued events before new ones are dropped and logged. |
| `write-batch-size` | 1–1000 | `64` | Events written per transaction. |

The queue is bounded on purpose: when the disk cannot keep up, WorldEcho drops the newest
events and reports the count in `/worldecho status` instead of stalling the server thread.

## commands

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `recent-default-count` | 1–1000 | `10` | Rows returned by `/worldecho recent` without an argument. |
| `recent-maximum-count` | 1–1000 | `50` | Hard cap for `/worldecho recent <count>`. |

## Messages

Message files use [MiniMessage](https://docs.advntr.dev/minimessage/format.html) tags and
`{placeholder}` tokens. Placeholder values are sanitized before substitution, so a custom
item name cannot inject formatting into an admin message.

`messages_en.yml` is always loaded as the fallback catalog, so a partially translated
`messages_tr.yml` still produces readable output; missing keys are shown as
`Missing message: <key>` instead of failing silently.

## Reloading

`/worldecho reload` re-reads `config.yml` and the message files only. The database
connection, the writer thread, and the provider registry keep running, so a reload can
never lose queued memories. Changing `persistence.*` therefore requires a server restart.
