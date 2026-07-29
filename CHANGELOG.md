# Changelog

## 0.1.0-SNAPSHOT

### Added

- Initial Paper 26.2 / Java 25 project scaffold
- Provider-neutral content identity model
- Vanilla entity and item providers
- SQLite event persistence
- Asynchronous single-writer queue
- Player death memory capture
- Item value scoring
- Admin status, recent-memory, and inspection commands
- English and Turkish message files
- Product, architecture, sprint, roadmap, and Devin documentation

### Known limitations

- The scaffold has not yet been manually validated inside a Paper server.
- External content bridges are not implemented.
- The player-death listener only records a memory; it does not transfer ownership.
- Scenario definitions are not yet loaded from YAML.
- Item snapshots are intentionally minimal and must not be treated as a complete item serialization format.
