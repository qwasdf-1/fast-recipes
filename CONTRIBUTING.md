# Contributing

Use Java 25 and the checked-in Gradle wrapper. Run `./gradlew build` and
the relevant [verification checks](docs/verification.md) before proposing a
change.

Enable the tracked hooks after cloning.

```sh
git config core.hooksPath .githooks
python3 scripts/check_doc_boundary.py
```

Keep project management material, extracted handoffs, third-party checkouts,
and raw run data in their ignored locations. Use the
[documentation layout](docs/documentation-layout.md) for maintained project
references. Record the Minecraft version, upstream revisions, workload
inputs, and result locations for measurements. Preserve recipe order,
inventory behavior, and datapack reload semantics.
