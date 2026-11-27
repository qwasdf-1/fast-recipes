# Documentation layout

The [documentation index](README.md) links the maintained project references.
Current implementation and verification belong in current-state.md.
Architecture and verification contracts belong in architecture.md,
architecture-decisions.md and verification.md. Research evidence belongs in
research/. Keep revisions and hashes in references.lock.json.

Local guidance, original handoff files, third-party clones and raw run data
stay in their ignored locations. The tracked Git hooks and CI use the shared mc-hygiene checker to validate
the documentation boundary. See [Contributing](../CONTRIBUTING.md) for
local hook setup.
