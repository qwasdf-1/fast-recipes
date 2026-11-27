# Changelog

## 0.1.0-alpha.1

First opt-in alpha for dedicated Minecraft Java 26.3 servers. Requires
Java 25 and Fabric Loader 0.20.1 or later. All optimizations default to off.

- Includes adaptive crafting lookup and a configurable recent-grid cache,
  shift-click batching, smithing and stonecutting indexes, and furnace hit
  and miss caches.
- Supports Recrafted custom recipe priority and packet-only batching on mixed
  crafting recipe sets. Fabric API is optional for Fast Recipes itself.
- Declares loader conflicts with FastSuite and Recipe Essentials.
- Performance varies with the recipe set and workload. See the
  [release decision](https://github.com/qwasdf-1/fast-recipes/blob/main/docs/research/R-006-decision.md)
  and [installation flags](https://github.com/qwasdf-1/fast-recipes#install).

### Development highlights

- Established a Minecraft 26.3 Fabric research workspace and pinned reference implementations.
- Added an isolated crafting lookup fixture and an experimental occupied-slot index.
- Added dedicated-server fixtures using mc-testkit and mc-lab, including a deterministic heavy data pack.
- Added an opt-in runtime experiment JAR and recorded the release decision from the baseline and pilot.
- Prototyped selective-ingredient and combined crafting indexes with a same-slot datapack comparison.
- Prototyped repeated-input recipe caching and per-take shift-click result-update batching.
- Prototyped bounded furnace miss caching and an ingredient hash tree with an optional repeated-input cache.
- Added an opt-in adaptive cached lookup with lazy per-slot-count hash trees and cutoff calibration.
- Added Recrafted crafting compatibility through ordered fallback for custom recipe classes and Fabric ingredients.
- Spread adaptive tree setup across dense lookups and added a mixed pickup and shift-click release workload.
- Prototyped a bounded crafting cache, independent smithing and stonecutting indexes, and successful furnace lookup caching.
- Allowed experimental crafting-update batching with Fabric Recipe API when the active crafting recipes have supported classes and vanilla ingredients.
- Added result-slot packet batching for mixed crafting recipe sets, including Recrafted.
