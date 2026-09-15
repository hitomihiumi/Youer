# Porting Youer to 1.21.8

State of the 1.21.8 port on the `1.21.8-toolchain` branch, the tooling it
uses, and what is left.

## What the tree is

Youer is NeoForge + Paper + Purpur in one source tree. Three layers, each
maintained differently:

| Layer | Lives in | Maintained as |
|---|---|---|
| `net.minecraft.*`, `com.mojang.*` | `patches/` | unified diffs against the AT-applied decompile; `projects/youer/src/main/java` is the applied result and is **generated, not tracked** |
| Access widening for the Bukkit layer | `src/main/resources/META-INF/accesstransformer.cfg` | NeoForge access transformers, applied **before** patches |
| `org.bukkit.*`, `io.papermc.*`, `org.purpurmc.*`, `co.aikar.*`, `gg.pufferfish.*`, `net.neoforged.*` | `src/main/java` | ordinary tracked source, rebased onto Paper/Purpur `ver/1.21.8` in M3 |

`paper-patches/` and `purpur-patches/` are upstream patch series kept for
reference. They are not applied by the build.

### The loop

```bash
./gradlew setup                 # decompile + AT + apply patches/ -> projects/youer/src
# edit projects/youer/src/main/java (net.minecraft) and/or src/main/java (Bukkit layer)
./gradlew :youer:compileJava
./gradlew :youer:genPatches     # write projects/youer/src back out to patches/
./gradlew setup                 # confirm it round-trips with zero rejects
```

Two things that will bite:

- **`projects/youer/src` is gitignored.** Work there is lost unless
  `genPatches` has run. `rm -rf projects/youer/src && ./gradlew setup` is the
  clean way to discard an experiment.
- **javac stops at 100 errors by default.** Every count in this document was
  measured with `-Xmaxerrs` raised:
  ```bash
  cat > /tmp/maxerrs.gradle <<'G'
  allprojects { tasks.withType(JavaCompile).configureEach {
      options.compilerArgs.addAll(["-Xmaxerrs", "10000"]) } }
  G
  ./gradlew :youer:compileJava --init-script /tmp/maxerrs.gradle
  ```
  Also note that a missing *class* (as opposed to a missing member) can make
  javac abort the round early and report a single error - `1 error` is not
  necessarily good news.

## Why upstream patches do not apply with `patch`

Paper and Purpur ship their `net.minecraft` patches against sources whose
parameters and locals are named descriptively, because their toolchain names
them; NeoForge's decompile still calls the same things `p_296356_` and
`abyte`. Every hunk therefore fails on context. Both projects also write
`@@ -12,7 +_,7 @@`, which GNU patch cannot parse.

`tools/port/smartpatch.py` matches a hunk against the target by tokens,
allowing a difference only where the target holds a decompiler-generated
name, and applies that hunk's inferred mapping to the lines it adds.
Specifics that matter:

- Mappings are **per line**. Mojang's registry blocks give every lambda the
  same name upstream (`builder -> builder.persistent(...)`) against a
  different `p_NNNNN_` per line here, so a hunk-wide mapping rejects them.
  Added lines inherit the mapping of the line they replace.
- A rename never touches a **method name** (token followed by `(`), a
  **qualified-name segment** (token after `.`), or anything inside a **string
  literal or comment**. Those are exactly the three ways the earlier rename
  tooling corrupted files: see `store("p_270186_", ...)` in Display.java,
  where an NBT key had been renamed, and `// Paper start - fix p_131481_
  tracker desync`.
- A second pass matches the hunk's old side against the file's token stream
  ignoring line breaks, for hunks where the two decompilers wrapped a long
  statement differently. It requires the match to be unique in the file.
- Applying is **idempotent**, and this is subtle: a hunk that only adds lines
  still matches its own old side after it has been applied, because every
  line of that old side is context. The matcher checks for the hunk's result,
  and for its most distinctive added line, before it checks the old side.

```bash
python3 tools/port/apply_upstream.py <paper>/paper-server/patches/sources
python3 tools/port/apply_upstream.py <purpur>/purpur-server/minecraft-patches/sources
python3 tools/port/apply_upstream.py <purpur>/purpur-server/paper-patches/files --bukkit
python3 tools/port/apply_upstream.py <purpur>/purpur-api/paper-patches/files --bukkit
```

Add `--dry-run` to measure without writing.

When a hunk adds a line referring to a local the hunk never shows, the
matcher has no mapping and leaves the upstream name. `tools/port/resolve.py`
takes the resulting compile errors, finds the enclosing method, and picks the
parameter or local whose type matches the name upstream used - only where
exactly one candidate fits.

```bash
./gradlew :youer:compileJava --init-script /tmp/maxerrs.gradle > build.log 2>&1
python3 tools/port/resolve.py build.log write     # run to convergence
```

## Where it stands

The port builds and the server runs.

```
./gradlew setup        # zero access-transformer warnings, zero patch rejects
./gradlew youerJar     # what CI runs - green from a clean tree
./gradlew build        # everything, tests included - green
java -jar youer-1.21.8-<id>-server.jar --nogui
                       # installs, generates a world and reaches "Done (...)!"
```

`./gradlew :youer:compileJava` reports **zero errors**, down from 1,212 when
the merge started. `genPatches` round-trips the tree unchanged, and the build
produces the server, universal, joined, installer, userdev and sources jars.
The dedicated server boots to the console prompt with all three dimensions
generated.

### What the boot cost

A green build said nothing about whether the thing ran. Getting from the first
`java -jar` to `Done (...)!` turned up nine separate defects, and they fall
into three groups worth knowing about.

**FML's module layers do not tolerate a package in two jars.** Paper's own
build is a flat classpath, so it freely ships a patched copy of a library class
that shadows the library's. Under BootstrapLauncher every classpath entry
becomes a module, and a split package is a hard `ResolutionException` before
any game code runs. Three of these:

- `net.neoforged.art.internal` - Paper patches ART's `RenamerImpl` (public,
  plus a `run(in, out, remappingSelf)` overload). Fixed by going back to the
  `com.mohistmc:AutoRenamingTool` fork the 1.21.1 tree used, which carries the
  same patch under a relocated package. `net.neoforged:AutoRenamingTool` stays
  installer-only.
- `com.mojang.brigadier` - vendored here because Paper patches `CommandNode`
  and `CommandDispatcher`, but the vanilla server jar still names the real
  library in its own `classpath-joined`. `CreateArgsFile` strips it again, the
  way the 1.21.1 tree did before the toolchain rebase dropped it.
- `me.lucko.spark.api` - shipped both standalone and inside the `spark-paper`
  fat jar.

**Which layer a jar lands in decides what it can see.** `spark-paper`
implements Bukkit listeners, so it has to read `org.bukkit`. A `libraries`
entry lands on the legacy classpath, i.e. the boot layer, which cannot see the
game layer. Jar-in-jar is not enough either: FML puts a JIJ'd jar in the
*plugin* layer unless its own manifest says `FMLModType: GAMELIBRARY`, and
spark-paper's does not. It is merged into the universal jar instead, so it
shares the `neoforge` module with `org.bukkit`.

**M3 carried over sources but not everything beside them.** Eight
`META-INF/services` registrations, two resource trees, the `rhino-engine` and
`rhino-runtime` dependencies Purpur needs for its configurable-attribute
equations, and an implementation of `PlatformHooks` were all missing. Mixin
configs also still listed classes the rebase had dropped. None of this shows up
at compile time.

One more, not a merge artifact: `Biome#climateSettings` was AT'd public, and
NeoForge's `ReplaceFieldWithGetterAccess` coremod refuses to run unless the
field it redirects is private. An AT can be wrong even when everything using it
compiles.

## What is left

**1. The moonrise chunk system stays excluded (policy B3).** Its call sites
are reimplemented on vanilla-safe equivalents rather than merged, and each
one carries a `// Youer - B3:` comment saying what it replaces. The two
places worth revisiting if the chunk system is ever adopted are
`ServerLevel#getChunkIfLoaded` (now a non-blocking vanilla `getChunk`) and
`StructureCheck`, which keeps vanilla's `loadedChunks`/`featureChecks` maps
instead of Paper's `Synchronised*` caches.

**2. Two upstream hunks deliberately skipped**, both rewriting lines added by
Paper feature patches that are not in this tree: Purpur's `NearestBedSensor`
search-radius option (rewrites Paper's "optimise POI access") and Purpur's
`RegionFileStorage` rebrand (inside Paper's oversized-chunk handling).

**3. `PlatformHooks` is a B3 reimplementation.** Upstream's
`ca.spottedleaf.moonrise.paper.PaperHooks` extends `BaseChunkSystemHooks`,
which is part of the excluded chunk system. `ca.spottedleaf.moonrise.youer.YouerHooks`
follows Paper for everything outside the chunk system and implements the
`ChunkSystemHooks` half against vanilla's `ChunkMap`/`ServerChunkCache`: holder
queries read vanilla's maps, the chunk-state callbacks are Moonrise bookkeeping
and become no-ops, and per-player view distances fall back to the server-wide
ones - the same trade-off `FeatureHooks` already makes.

**4. Two mixins were dropped, not ported.**
`neoforge.mixins.json` no longer lists `ServerLoginPacketListenerImplMixin` and
`youer.mixins.json` is empty. Both referred to classes the M3 rebase did not
carry over: the login mixin routes Velocity and Fabric-API login payloads
through `youer$handleCustomQueryPacket`, which does not exist in this patch set
(Paper 1.21.8 handles plain Velocity natively), and the two Create-compat
mixins live in `com.mohistmc.youer.mixins`. They come back with the
`com.mohistmc.youer` glue.

**5. `com.mohistmc.youer` is still missing.** The M3 rebase dropped 193 files
of Youer's own Bukkit/NeoForge bridge, leaving only the four ASM classes the
boot's `ILaunchPluginService` registers. `gg.pufferfish.pufferfish.I18n` is a
placeholder for `com.mohistmc.youer.util.I18n` until then.

**6. NeoForge's recipe overrides do not parse.** Every
`data/minecraft/recipe/*.json` the neoforge datapack ships is rejected at load
("Input does not contain a key [neoforge:ingredient_type]"), so the server falls
back to 1,217 vanilla recipes. The files are in the old map form
(`{"tag": "c:rods/wooden"}`) that 1.21.8's `Ingredient` codec no longer accepts.
It does not stop the server, and it is the next thing to fix.

**7. Nothing beyond a vanilla boot has been exercised.** No Bukkit plugin and
no NeoForge mod has been loaded, and no client has connected.

**8. Cosmetic residue.** Some files carry comments where the earlier rename
tool substituted a word inside the comment text (`// Paper - Incremental
chunk and p_11277_ saving`). Harmless to the build; worth a sweep before the
port is called done.

## Things worth knowing before touching this

- **An access transformer cannot target a patch-added member.** NeoForge
  applies ATs to the vanilla decompile, before `patches/`. Paper-added or
  Purpur-added fields have to be widened in the patch itself
  (`AbstractFurnaceBlockEntity#recipeType`, `LivingEntity#shouldBurnInDay`).
  Equally, a NeoForge patch that rewrites a declaration line **undoes** an AT
  on it - `RecipeMap#byType` is AT'd public and then patched back to package
  private.
- **AT'ing a package-private class public makes the AT tool emit an explicit
  package-private constructor**, which then blocks `new Foo()` from outside
  the package. Widen the class in the patch instead, the way Paper does
  (`GossipContainer$EntityGossips`).
- **Widening a method on a base class cascades.** `LivingEntity#getHurtSound`,
  `Mob#getAmbientSound` and friends are public here because the Bukkit layer
  calls them; every vanilla subclass override then has to be public too. That
  is 273 declarations across 100 files, and it is what Paper's
  `build-data/paper.at` does for them.
- **Changing an AT shifts the baseline the patches apply to**, so a modifier
  that appears as context in a patch hunk will break it. `genPatches`
  normalises this away; run `setup` afterwards to confirm.
- **`applyAccessTransformer` does not treat the .cfg as an input.** Adding a
  line to `accesstransformer.cfg` leaves the task `UP-TO-DATE`, so the AT
  silently does not apply and the member stays narrow. Force it with
  `./gradlew :youer:applyAccessTransformer --rerun-tasks`.
- **A new AT has to be added in the right order, or `genPatches` bakes in a
  revert.** `genPatches` diffs the working tree against the AT'd base, so if
  the tree still holds the narrow declaration the patch records
  `-public ... / +private ...` and the next `setup` narrows the member right
  back. The order is: `genPatches` first to capture your source edits, add
  the AT, strip any narrowing hunk the patch already carries, `setup
  --rerun-tasks`, and only then regenerate. Four rounds of ATs in this port
  hit this (`FuelValues#values`, `ServerChunkCache$MainThreadExecutor`,
  `FallingBlockEntity`'s constructor, `ItemStack#components`,
  `EndDragonFight#spawnNewGateway`, `AbstractContainerMenu`'s slot
  listeners).
- **`projects/youer/src` is generated and gitignored.** Everything you edit
  there is lost the moment `setup` re-runs, and `rm -rf projects/youer/src`
  is how you discard an experiment. Run `genPatches` before any `setup`, or
  the work is gone - this cost a full re-apply of ~30 files once.
- **javac's default 100-error cap hides the real count.** All the numbers
  here were measured with an init script setting `-Xmaxerrs 10000`. A sudden
  drop to a tiny error count is a red flag, not progress: a missing *class*
  (not member) makes javac abort a round early.
