# BOP Patches

Patch layer for Biomes O' Plenty 1.7.10.

This repo clones BOP from `Glitchfiend/BiomesOPlenty` branch `BOP-1.7.10-2.1.x`, gives you an editable `bop-src/` workspace, and builds a mod jar that patches the official BOP jar at runtime with `bspatch`.

## Workflow

1. Run `./gradlew runClient` or `./gradlew runServer`
2. Edit files in `bop-src/`
3. Run `./gradlew generateBopPatches`
4. Commit `patches/workspace.patch`

Do not commit `bop-src/`.

## Useful tasks

- `./gradlew bootstrapBopWorkspace`
- `./gradlew refreshBopWorkspace`
- `./gradlew generateBopPatches`
- `./gradlew generateBopRuntimeArtifacts`
- `./gradlew runClient`
- `./gradlew runServer`
- `./gradlew runObfClient`
- `./gradlew runObfServer`

## Building

`./gradlew build`

## Notes

- `bop-src/` and `.bop/upstream/` are generated and gitignored.
- Versioning follows the normal GTNH git-tag flow.
