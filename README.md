# BOP Patches for 1.7.10

This project is a GTNH-buildscript Forge mod that lets you edit Biomes O' Plenty like a normal source tree in development, then ship only a whole-jar binary patch against the official BOP jar.

## Goals

- use the GTNH buildscript for the entire dev environment
- bootstrap BOP source automatically from `Glitchfiend/BiomesOPlenty` `BOP-1.7.10-2.1.x`
- make day-to-day editing feel like directly modifying BOP
- keep committed changes as patch-friendly source overrides
- produce a distributable mod jar that patches the official BOP jar at runtime with `bspatch`

## Workflow Summary

### Development runs

`./gradlew runClient`, `./gradlew runServer`, and the modern Java variants compile `bop-src/` as a dedicated BOP workspace jar and drop that jar straight into the dev run directory.

That means the normal edit loop is:

1. Edit files in `bop-src/`
2. Run the game
3. Repeat

No manual patch generation is needed just to test a change.

### Production-style runs

`./gradlew runObfClient` and `./gradlew runObfServer` stage:

- the packaged `boppatches` mod jar
- the official `BiomesOPlenty-1.7.10-2.1.0.2308-universal.jar`

At startup, the coremod:

1. verifies the official jar hash
2. applies the bundled whole-jar `bspatch`
3. replaces the live BOP jar in the run directory
4. lets Forge load the patched official jar

This is the same model intended for real distribution: ship the patch mod, not a rebuilt BOP jar.

## How the Source Workspace Works

On first Gradle sync or task run, the repo automatically:

1. clones the configured upstream BOP branch into `.bop/upstream/`
2. creates `bop-src/` from upstream sources and resources
3. reapplies committed overrides from `patches/source-overrides/`
4. reapplies deletions from `patches/deletions.txt`

`bop-src/` is your editable working tree. You generally should not commit it.

To save your current `bop-src/` edits back into the repo, run:

```bash
./gradlew generateBopPatches
```

That task refreshes the runtime patch artifacts and captures your changed files into:

- `patches/source-overrides/`
- `patches/deletions.txt`

So the committed representation stays source-based and easy to review.

## Runtime Patch Generation

`./gradlew generateBopRuntimeArtifacts` does the heavy lifting:

1. builds a deobfuscated BOP workspace jar for dev runs
2. builds a reobfuscated BOP workspace jar for runtime use
3. compares `bop-src/` against upstream to find changed classes, added files, and deletions
4. overlays only those differences onto the official BOP jar to build the runtime target jar
5. generates a whole-jar `bsdiff` patch from the official jar to that runtime target jar
6. bundles the patch and hash metadata into this mod jar

This avoids assuming the GitHub branch produces a byte-for-byte identical jar for unchanged upstream classes.

## Important Tasks

- `./gradlew bootstrapBopWorkspace`
  Ensures `.bop/upstream/` and `bop-src/` exist.

- `./gradlew refreshBopWorkspace`
  Rebuilds `bop-src/` from upstream plus the committed overrides.

- `./gradlew generateBopRuntimeArtifacts`
  Rebuilds the dev workspace jar, runtime workspace jar, and bundled whole-jar patch payload.

- `./gradlew generateBopPatches`
  Captures the current `bop-src/` edits into `patches/source-overrides/` and `patches/deletions.txt`.

- `./gradlew updateBopUpstream`
  Re-clones the configured BOP upstream branch into `.bop/upstream/`.

- `./gradlew runClient`
  Runs the deobfuscated client with the compiled `bop-src/` jar.

- `./gradlew runServer`
  Runs the deobfuscated server with the compiled `bop-src/` jar.

- `./gradlew runObfClient`
  Runs against the official BOP jar and patches it in-place at startup.

- `./gradlew runObfServer`
  Runs against the official BOP jar and patches it in-place at startup.

## Layout

- `addon.gradle`
  Custom GTNH hooks for BOP workspace bootstrapping, runtime artifact generation, and run-directory prep.

- `bop-src/`
  Generated editable BOP workspace. Gitignored.

- `.bop/upstream/`
  Cached upstream BOP checkout. Gitignored.

- `patches/source-overrides/`
  Full-file overrides that recreate your edited BOP workspace.

- `patches/deletions.txt`
  Files removed from upstream when reconstructing `bop-src/`.

- `src/main/java/org/fentanylsolutions/boppatches/`
  The patch mod and whole-jar runtime patcher coremod.

## Versioning

This repo keeps GTNH's normal Git-tag-based version flow.

There is no hardcoded mod version in the build logic. If the repository has no commits or tags yet, GTNH falls back to `NO-GIT-TAG-SET` for local development. Once the repo has normal tagged history, GTNH versioning takes over automatically.

## Notes

- The official BOP jar URL and upstream branch are configured in `gradle.properties`.
- The custom build logic lives in `addon.gradle` so the GTNH root buildscript can stay update-friendly.
- The first full artifact generation is the expensive one because it has to prepare the full 1.7.10 toolchain.
