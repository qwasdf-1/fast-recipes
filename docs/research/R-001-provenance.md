# R-001 Reference acquisition

The [lockfile](../references.lock.json) pins the source revisions actually read,
their declared targets, license evidence and artifact hashes. The original
handoff and its acquisition script are preserved in the extracted local
archive. Run the script from the Fast Recipes root. The first attempt
encountered sandbox DNS restrictions. The retry cloned every listed repository.

The GitHub account listing for Al-Capone11 also identified the missing
[FastSuite port](https://github.com/Al-Capone11/FastSuite-Fabric-Unofficial-Port).
Clone it alongside the other repositories. Inspect Recipe Essentials on
`origin/fabric26.3` at the revision in the lockfile. Its remote default branch
targets a different game and loader.

Modrinth lists the three older 1Influence ports with no source URL. The tested
GitHub account URL returned HTTP 404. Their pinned release jars were downloaded
and checked against the API hashes. Metadata and mixin declarations were read
directly from the archives. No third-party build scripts or jars were executed.
The repository includes original research code and no copied upstream code.
