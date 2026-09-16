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
```

`./gradlew :youer:compileJava` reports **zero errors**, down from 1,212 when
the merge started. `genPatches` round-trips the tree unchanged, and the build
produces the server, universal, joined, installer, userdev and sources jars.

The dedicated server installs itself, generates all three dimensions, reaches
the console prompt and holds 20 TPS. A Bukkit plugin loads, enables, and its
scheduler, event registration and world API all work; a NeoForge mod loads,
receives `ServerStartingEvent`/`ServerStartedEvent`, and can see the Bukkit
plugin list from inside the mod - the two layers reach each other. `/stop`
shuts down cleanly with every dimension saved, and a restart loads the saved
world back.

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

### What running it turned up

Booting is not the same as working. Loading a plugin and a mod, driving the
console and stopping cleanly found five more, and they are a different kind of
bug from the boot failures: each one is a place where something was deferred or
reimplemented during the merge and the substitute was not equivalent.

- **A deadlock.** `Level#getChunkIfLoadedImmediately` had been reimplemented as
  `getChunk(x, z, FULL, false)`, on the reasoning that `load=false` makes it
  non-blocking. It does not: off the main thread `ServerChunkCache#getChunk`
  hands the lookup to the main thread and joins. A worldgen worker spawning a
  strider jockey called it through `gameEvent`, while the main thread was
  blocked waiting for that very chunk. Nether generation hung forever. The same
  substitution was in `ServerLevel#getChunkIfLoaded` and `hasChunksAt`. All three
  are back on `ServerChunkCache#getChunkAtIfLoadedImmediately`, which reads a
  concurrent map and is what Paper used all along.
- **`ServerChunkCache` got a null chunk-status listener**, because Paper passes
  null there - its chunk system never calls `ChunkMap#onFullChunkStatusChange`.
  Vanilla's does, on every chunk demotion, so `/stop` NPE'd while saving.
  Passing `entityManager::updateChunkStatus` restores vanilla's wiring.
- **Three merge deferrals had gone stale**, their blockers merged since:
  `PrimaryLevelData#setWorld` (without it `/difficulty` NPE'd),
  `ServerExplosion#wasCanceled`, and Paper's bedrock/end-portal protection
  during tree generation.
- **`ReobfServer` assumed a single server jar on disk.** Paper remaps one jar
  holding both `net.minecraft` and the Bukkit layer; Youer's server is split
  across FML's module jars, and both live inside SecureJarHandler's union file
  system, where `Path#toFile` throws outright. Every plugin failed to load. It
  now resolves each module back to its real jar and merges the two.
- **`ProxyGenerator` read class files through its own class loader**, which under
  FML is the boot layer and cannot see `PaperReflection` in the game layer.
  `ReflectionRemapper` drives the `ClassReader` overload instead and resolves
  each class through the loader that defined it.

And one build-script bug with an outsized effect: the `youerJar` bundling step
skipped every library whose *name* starts with `asm`, meaning to skip
`org.ow2.asm`'s jars. It also skipped `io.papermc:asm-utils`, which Commodore
loads for every plugin it processes.

## What is left

**1. The moonrise chunk system stays excluded (policy B3).** Its call sites
are reimplemented on vanilla-safe equivalents rather than merged, and each one
carries a `// Youer - B3:` comment saying what it replaces. Two of them are
worth revisiting if the chunk system is ever adopted: `StructureCheck`, which
keeps vanilla's `loadedChunks`/`featureChecks` maps instead of Paper's
`Synchronised*` caches, and `ca.spottedleaf.moonrise.youer.YouerHooks` (below).

Treat "vanilla-safe" as a claim to check, not a property of the label. The
`getChunkIfLoaded*` substitution carried that comment and deadlocked the server;
it was thread-safety, not behaviour, that the vanilla equivalent did not have.

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
mixins live in `com.mohistmc.youer.mixins` and are still blocked on Create
publishing a 1.21.8 artifact (see 5).

**5. `com.mohistmc.youer` is back, minus three compat shims.** The M3 rebase
had dropped 193 files of Youer's own Bukkit/NeoForge bridge; 188 are restored
and wired in. The server boots with the bridge live: `/youer`, `/bans`,
`/infos`, `/shows` and `/youer packetstats` respond, `Youer.versionInfo` and the
i18n are populated, and the ban lists, config gates and modded fallbacks are
all on their call sites again.

The four still missing are `mixins/compat/create/*` (two files),
`LithostitchedCompat` and `SableCompat` - none of those mods publishes a 1.21.8
`compileOnly` artifact yet. `TerraBlenderCompat` is back, compiled against
TerraBlender 6.0.0.3 from `api.modrinth.com/maven`, and called from
`CraftServer#createWorld` behind the `terrablender_compat` config and a
`ServerAPI.hasMod("terrablender")` check, so the class is never loaded on a
server without the mod.

Not every 1.21.1 call site came back, and the ones that did not are worth
listing, because a future rebase will see them in the old tree and wonder.

*Already covered by 1.21.8 upstream, so the Youer hook would be dead weight or
a second copy:* the piston desync fix (`PaperUnsupportedSettings`), the nether
ceiling void damage (`LambdaFix.checkBelowWorld`), per-world lava flow speed
(Purpur's `lavaSpeedNether`/`lavaSpeedNotNether` is a superset of Youer's global
pair), `DiscardedPayload` carrying its payload bytes (Paper's record already
does), the structure-transformer block hook (`StructureTemplateMixinFix`, now
inline in `StructureTemplate`), the async pre-login events (`LoginHandler`, now
inline in `ServerLoginPacketListenerImpl`), the chat and command-suggestion
lambdas (`LambdaFix`), the damage-modifier simulation (`BukkitDamageHooks` -
CraftBukkit's own `handleEntityDamage` is now a superset, invulnerability
reduction included), and the death-drop conversion (`ItemEntityTools` -
CraftBukkit spawns the drops from the Bukkit list itself).

*Deliberately not carried over because the upstream hook looks wrong:* the
`doFireTick` game rule default was being initialised from the "ban fire tick"
config, which turns vanilla fire spread off by default while the config is at
its default of false - the `FireBlock`/`LavaFluid`/`LightningBolt` guards
already implement the ban, so only those were ported; and
`EnchantmentHelper` was filtering candidate enchantments by whether the *stack*
carried a banned enchantment rather than whether the *candidate* was banned,
which both blocks legitimate enchanting and lets banned ones through - the
checks now test the enchantment holder. `ServerPlayer`'s `keepLevel` path and
`FarmBlock`'s trample guard were both moved a line so that banning a thing no
longer also cancels the fall damage or the experience that has nothing to do
with it.

*One behaviour restored on purpose, worth knowing:* `DedicatedServer` calls
`Metrics.MohistMetrics.startMetrics()` again, which is upstream's bStats
integration. It reports anonymous server statistics on startup and is opt-out
through `plugins/bStats/config.yml`, the same as every Paper server.

**6. `src/generated/resources` was synced by hand, not regenerated.** It was
1.21.1-era datagen output: every one of NeoForge's 190 recipe overrides was
rejected at load (the pre-1.21.2 `{"tag": "c:rods/wooden"}` ingredient form),
`c:boats` still named the entity types 1.21.2 split up, and ~200 files added
since were missing. The tree now matches `neoforge-21.8.54-universal.jar`'s own
`data/` byte for byte, and the server loads 1,407 recipes with no parse errors.

The reason it was synced rather than regenerated is that `runData` does not
work. moddevgradle 2.0.107 split the `data` run type into `clientData` and
`serverData`, which is fixed here, and the run then dies before datagen starts:

```
InvalidModuleDescriptorException: Service provider file
  /META-INF/services/io.papermc.paper.registry.RegistryAccess contains service
  that is not in this Jar file: io.papermc.paper.registry.PaperRegistryAccess
    at ModuleDescriptorFactory.parseServiceFile(ModuleDescriptorFactory.java:148)
    at ModuleDescriptorFactory.scanAutomaticModule(ModuleDescriptorFactory.java:114)
    at ModJarMetadata.computeDescriptor(ModJarMetadata.java:43)
```

An earlier note here blamed the split between a dev run's classes and resources
directories. That is not it, and the experiments are worth recording so nobody
repeats them:

* The run does pass both directories as one mod:
  `-Dfml.modFolders=minecraft%%<classes>:minecraft%%<resources>`.
* Copying both into a single directory and pointing `fml.modFolders` at that one
  directory fails identically.
* Moving just `META-INF/services` into the classes directory, so the service
  files sit beside the classes they name, fails identically.
* Cutting the service files down to the single one datagen needs moves the error
  onto that one: `PaperRegistryAccess` "is not in this Jar file" even though it
  is in the same directory. So the scan is not missing one package, it is
  missing all of ours.
* Removing the service files entirely gets past the module scan and into datagen,
  which then dies in `BuiltInRegistries.<clinit>` with "No RegistryAccess
  implementation found" - the service it needs is one of the ones that had to go.

So the mod jar `ModJarMetadata` computes the descriptor for does not contain our
classes at all, and the next person should start by finding out what that jar
actually is rather than by rearranging the source sets. Until then, treat this
directory as synced-from-upstream rather than generated, and re-sync it the same
way when NeoForge moves - the current contents are byte-identical to
`neoforge-21.8.54-universal.jar`, so nothing is silently stale.

**7. Legacy plugin remapping works; no client has connected.** A plugin written
entirely in Spigot names loads and runs: it calls `MinecraftServer.aw()` and
`.L()`, and reaches CraftBukkit through the relocated
`org.bukkit.craftbukkit.v1_21_R5` package. On load the remapper rewrites it and
it reports

```
LEGACY: absoluteMaxWorldSize=29999984
LEGACY: levels=3
LEGACY: SystemUtils -> net.minecraft.Util
LEGACY: EntityPlayer -> net.minecraft.server.level.ServerPlayer
LEGACY: WorldServer -> net.minecraft.server.level.ServerLevel
LEGACY: CraftServer -> org.bukkit.craftbukkit.CraftServer
```

To build such a plugin again: boot once, then compile against
`plugins/.paper-remapped/remap-classpath/<mappings hash>.jar`, which is the
reobf server the remapper produced - Spigot class names, Spigot member names and
the versioned CraftBukkit package, i.e. exactly what a Spigot-mapped plugin sees.
Package it with a `plugin.yml`, no `paper-plugin.yml`, and no
`paperweight-mappings-namespace` manifest attribute, which is what makes
`PluginRemapper` treat it as legacy.

What is still untested is a real client connecting to the server.

**8. Cosmetic residue.** Some files carry comments where the earlier rename
tool substituted a word inside the comment text (`// Paper - Incremental
chunk and p_11277_ saving`). Harmless to the build; worth a sweep before the
port is called done.

## Things worth knowing before touching this

**Deleting jars out of a run directory's `libraries/` is not the same as a
fresh run.** `rm -f libraries/net/neoforged/neoforge/<v>/*.jar` before a boot is
the right move when you want the installer to re-extract the universal jar
instead of silently testing a stale one - but the installer only restores the
universal, not the reobf `neoforge-<v>-server.jar` next to it, and the launch
then dies in `CommonLaunchHandler.runTarget` with a bare `NoSuchElementException:
No value present`. That failure is the run directory, not the build. When in
doubt, boot from an empty directory with just the server jar, `eula.txt`,
`server.properties`, `plugins/` and `mods/`.

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
