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

## Release publishing

After testing the release JAR, annotate its version tag with a line
`SHA256 <hash of the tested JAR>`. The release workflow compares the
rebuilt JAR to this hash before publishing it to GitHub.

To enable automatic distribution, add the project ID as a GitHub Actions
repository variable and the upload credential as a repository secret.

| Platform | Variable | Secret |
| --- | --- | --- |
| Modrinth | `MODRINTH_PROJECT_ID` | `MODRINTH_TOKEN` with version-create permission |
| CurseForge | `CURSEFORGE_PROJECT_ID` | `CURSEFORGE_TOKEN` |

Set only the IDs for the platforms you want to publish to. The release
workflow dispatches those publishers after GitHub release creation. Each
publisher also accepts an existing release tag through manual dispatch.
They download the same JAR and verify the tag checksum before uploading.
The tag workflow and manual dispatch also accept project IDs stored as
secrets under the same names.

## Marketplace project metadata

Use the **Sync marketplace project metadata** workflow to preview the
project summary, description, license, and resource links. It renders
curated README content from `.github/project-metadata.json` and uploads
a preview with the fields for both marketplaces.

Use **Apply selected changes** from the default branch to update Modrinth.
The token needs `PROJECT_WRITE` in addition to its upload permissions.
The project's source URL must match this repository. The workflow reads
the updated fields back after applying them.

For CurseForge, apply the rendered fields in the author dashboard.
The experimental description-only update requires a separate opt-in
and a pin in `.github/project-metadata.json` matching the configured
`CURSEFORGE_PROJECT_ID`. Verify the outcome in the dashboard.
