# BOP Patches for 1.7.10

## What it does

- clones BOP from `Glitchfiend/BiomesOPlenty` branch `BOP-1.7.10-2.1.x`
- builds it inside the
- lets you edit BOP in `bop-src/`
- saves your changes as `patches/workspace.patch`
- ships a mod jar that patches the official BOP jar at runtime with `bspatch`

## Normal workflow

1. Run `./gradlew runClient` or `./gradlew runServer`
2. Edit files in `bop-src/`
3. Test again
4. Run `./gradlew generateBopPatches`
5. Commit `patches/workspace.patch`

Do not commit `bop-src/`.

## Important tasks

- `./gradlew bootstrapBopWorkspace`
  Creates `.bop/upstream/` and `bop-src/` if needed.

- `./gradlew refreshBopWorkspace`
  Rebuilds `bop-src/` from upstream and reapplies `patches/workspace.patch`.

- `./gradlew generateBopPatches`
  Rebuilds runtime patch artifacts and writes `patches/workspace.patch`.

- `./gradlew generateBopRuntimeArtifacts`
  Rebuilds the dev jar, runtime jar, and bundled whole-jar `bsdiff` patch.

- `./gradlew runClient`
  Runs the dev client with the compiled `bop-src` jar.

- `./gradlew runServer`
  Runs the dev server with the compiled `bop-src` jar.

- `./gradlew runObfClient`
  Runs against the official BOP jar and patches it at startup.

- `./gradlew runObfServer`
  Runs against the official BOP jar and patches it at startup.

## Files

- `bop-src/`
  Editable BOP workspace. Generated and gitignored.

- `.bop/upstream/`
  Cached upstream BOP checkout. Generated and gitignored.

- `patches/workspace.patch`
  Source patch for your BOP changes.

- `src/main/java/org/fentanylsolutions/boppatches/`
  Runtime patch mod and coremod code.

