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

`:youer:runServer` works as well, and a player can join it; getting there
meant working around the way FML splits a dev source set into two modules,
which is written up under item 6 below. `:youer:runData` is deliberately
refused - same item.

The dedicated server installs itself, generates all three dimensions, reaches
the console prompt and holds 20 TPS. A Bukkit plugin loads, enables, and its
scheduler, event registration and world API all work; a NeoForge mod loads,
receives `ServerStartingEvent`/`ServerStartedEvent`, and can see the Bukkit
plugin list from inside the mod - the two layers reach each other. A
Spigot-mapped plugin loads through the remapper, and **a player can join**:
login, configuration, play, a command issued as a player, a clean quit, and
their data, stats and advancements written. `/stop` shuts down cleanly with
every dimension saved, and a restart loads the saved world back.

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

Treat the `B3:` label itself the same way. Auditing the markers one by one took
them from 41 to 32: nine were not about the chunk system at all, and every one
of those nine was wrong about what this tree already has.

* **Six weather sites** said `PrimaryLevelData`'s `Cause`-tagged
  `setRaining`/`setThundering` overloads "are not merged". They are, and
  `ServerLevel.serverLevelData` is typed `PrimaryLevelData` exactly as upstream,
  so they were reachable all along. Every `WeatherChangeEvent` and
  `ThunderChangeEvent` this server fired reported `Cause.UNKNOWN` instead of
  `NATURAL`, `COMMAND` or `SLEEP`. Fixed; the Paper name audit stopped reporting
  those two events as a result.
* **Both `LevelChunkSection` sites** said Paper's Anti-Xray `PalettedContainer`
  overloads "are not merged yet". They are - preset-values constructor, the
  three-argument `write`, `Level.chunkPacketBlockController`, and both call
  sites already passing the arguments through. Only the two method bodies had
  been stubbed out, so Anti-Xray was configurable and inert. Restored.
* **`ServerChunkCache#close(boolean)`** said the same, and it too exists;
  `save(..., skipSave, close)` now passes `!skipSave` as upstream does, instead
  of always saving on the way out.

Of the 32 that remain, 31 really are the chunk system: the `ChunkSystem*`
interfaces, `moonrise$getEntityLookup` versus the vanilla `entityManager`,
`moonrise$midTickTasks`, `loadChunksAsync`, the ticket spiral, the collision
rewrite, and `StructureCheck`'s `Synchronised*` caches. All 32 of upstream's
`ca/spottedleaf/moonrise` source files are present; it is the call sites into
them that are not.

The odd one out is `ServerLevel#getTypeKey`, which derives the `LevelStem` key
from the dimension location because `LevelStorageAccess#dimensionType` has not
been merged (`LevelStorageSource.java` is NeoForge-only here). It has nothing
to do with moonrise and is mislabelled; it is also the only remaining marker
that could be cleared without adopting the chunk system.

**2. One upstream hunk deliberately skipped**: Purpur's `RegionFileStorage`
rebrand, which rewrites a line Paper's oversized-chunk handling adds and this
tree does not have. Purpur's `NearestBedSensor` search-radius option was listed
here for the same reason and that was wrong -- the Paper "optimise POI access"
line it rewrites *is* in this tree, so the hunk applies and has now been taken.

**3. `PlatformHooks` is a B3 reimplementation.** Upstream's
`ca.spottedleaf.moonrise.paper.PaperHooks` extends `BaseChunkSystemHooks`,
which is part of the excluded chunk system. `ca.spottedleaf.moonrise.youer.YouerHooks`
follows Paper for everything outside the chunk system and implements the
`ChunkSystemHooks` half against vanilla's `ChunkMap`/`ServerChunkCache`: holder
queries read vanilla's maps, the chunk-state callbacks are Moonrise bookkeeping
and become no-ops, and per-player view distances fall back to the server-wide
ones - the same trade-off `FeatureHooks` already makes.

**4. The login mixin is back as a patch; the Create mixins are still out.**
`youer.mixins.json` is empty and `neoforge.mixins.json` no longer lists
`ServerLoginPacketListenerImplMixin`, and for the login one that is now the
right shape rather than a gap. In 1.21.1 the behaviour lived in two places: a
`ServerLoginPacketListenerImpl` patch defining `youer$handleCustomQueryPacket`
and `fixFabricNetworkingIssue`, and a mixin that `@Shadow`ed the first of those.
The merge dropped the patch, which left the mixin shadowing a method that did
not exist. Paper 1.21.8 handles plain Velocity natively and inline, so the two
pieces the merge really lost are folded back into that same method:

* `youer$velocityAnswerBuffer` reads the answer off either payload shape. Paper
  casts straight to `ServerboundCustomQueryAnswerPacket.QueryAnswerPayload`;
  with Fabric's networking API loaded (Sinytra Connector) the payload is
  `PacketByteBufLoginQueryResponse` instead and that cast throws a
  `ClassCastException` in the middle of a login. Anything else still gets a
  clean `velocity.requires` disconnect.
* `youer$releaseFabricLoginChannel` drops our transaction id from Fabric's
  `ServerLoginNetworkAddon.channels`, which otherwise refuses to finish a login
  while it thinks a query of its own is outstanding. Adapted from NeoVelocity,
  and only reached when `fabric_networking_api_v1` is loaded.

The two Create-compat mixins in `com.mohistmc.youer.mixins` stay out, blocked
on Create publishing a 1.21.8 artifact (see 5).

**5. `com.mohistmc.youer` is back, minus three compat shims.** The M3 rebase
had dropped 193 files of Youer's own Bukkit/NeoForge bridge; 188 are restored
and wired in. The server boots with the bridge live: `/youer`, `/bans`,
`/infos`, `/shows` and `/youer packetstats` respond, `Youer.versionInfo` and the
i18n are populated, and the ban lists, config gates and modded fallbacks are
all on their call sites again.

The four still missing are `mixins/compat/create/*` (two files),
`LithostitchedCompat` and `SableCompat`. Re-checked against the registries
rather than assumed:

* Create and Ponder publish 1.21.1 and nothing newer on `maven.createmod.net`;
  Sable has no 1.21.8 build on `maven.ryanhcode.dev` or Modrinth either.
* Lithostitched is the interesting one, because it *does* have 1.21.8 NeoForge
  builds - and they are still no use. Its 1.21.8 line stops at `1.5.0+beta5`,
  which predates the biome-injector API entirely: the jar has no
  `dev.worldgen.lithostitched.api` or `.impl` packages and no `BiomeInjector`.
  The line that has them, `1.7.x`/`1.8.x`, went from 1.21.1 straight to 26.x
  and skipped 1.21.8. So the blocker is not "no artifact", it is "no artifact
  with the API the shim is written against", and waiting will not fix it. `TerraBlenderCompat` is back, compiled against
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

The reason it was synced rather than regenerated is that **`runData` cannot
regenerate it in a server-only tree, and this is upstream's design rather than
a defect here.** In NeoForge 21.8 the `neoforge` mod registers every one of its
data providers - the server-side ones included - from
`ClientNeoForgeMod.onGatherData(GatherDataEvent.Client)`, a class annotated
`@Mod(value = "neoforge", dist = Dist.CLIENT)`. Upstream says why in a comment
on that method: "We perform client and server datagen in a single clientData
run to avoid having to juggle two generated resources folders and two runs for
no additional benefit." Youer has no client source set and its run is declared
`serverData()`, so `GatherDataEvent.Server` is fired, nothing listens, and
`DataGenerator` reports `All providers took: 0 ms`.

That last part is a live footgun, so it is worth stating on its own: a datagen
run with no providers does not no-op. It treats the whole output directory as
stale. Running it deletes all 917 files
(`HashCache: total files: 917, old count: 0, new count: 1, removed stale: 917,
written: 0`) and writes nothing back. `git checkout src/generated/resources`
is the undo.

So keep treating this directory as synced-from-upstream, and re-sync it the
same way when NeoForge moves - the current contents are byte-identical to
`neoforge-21.8.54-universal.jar`, so nothing is silently stale. Regenerating
locally could at best reproduce those same bytes.

### The dev-run module split, which also breaks `runServer`

Getting far enough to learn the above meant getting past a different failure,
and that one is not cosmetic: **`./gradlew :youer:runServer` is broken by it
too**, with the same stack.

```
InvalidModuleDescriptorException: Service provider file
  /META-INF/services/net.kyori.adventure.text.event.ClickCallback$Provider
  contains service that is not in this Jar file:
  io.papermc.paper.adventure.providers.ClickCallbackProviderImpl
    at ModuleDescriptorFactory.parseServiceFile(ModuleDescriptorFactory.java:148)
    at ModJarMetadata.computeDescriptor(ModJarMetadata.java:43)
```

An earlier note here blamed the split between a dev run's classes and resources
directories and, when that was disproved, said the next person should find out
what jar `ModJarMetadata` is computing over. It is the **`neoforge` mod file**,
and the reason is in `NeoForgeDevProvider.findCandidates`: FML splits one dev
source set into two mod files with a pair of complementary `UnionPathFilter`s,
keyed on three prefixes - `net/neoforged/neoforge/`, `META-INF/services/` and
`META-INF/neoforge.mods.toml`.

* the **minecraft** mod file takes, from the dev directories, *only* files
  ending in `.class` that do not start with one of those prefixes - plus
  everything in the Minecraft resources jar, unfiltered.
* the **neoforge** mod file takes *every non-class file* plus the
  `net/neoforged/neoforge/**` classes.

Upstream that partition is exact, because its dev source set holds only
NeoForge's own classes and service files. Youer's holds Minecraft, NeoForge,
Bukkit, Paper and Purpur together, so all thirty of Paper's `META-INF/services`
files land in a module that contains none of the classes they name, and the
automatic-module scan rejects it. The earlier experiments were all consistent
with this and none of them could have worked: the filter routes by path, so no
amount of moving files between the two dev directories changes which mod file a
service file lands in.

The one lever that does work is `path.equals(minecraftJar)`, which waves
anything in the Minecraft resources jar straight into the minecraft module -
the module that has our classes. `projects/youer/build.gradle` pulls that lever
for dev runs only, in four `devRun*` tasks; **`./gradlew :youer:runServer` now
boots to `Done` and a player can join it.** Nothing there touches
`processResources` or any jar task, so the shipped artifacts keep all thirty
service files exactly where they were.

What the four tasks do, and why each is needed:

1. `devRunResources` is the mod folder FML actually reads: `build/resources/main`
   minus the service files whose providers are not `net.neoforged.neoforge.*`.
   Exactly one file is (`net.neoforged.fml.IBindingsProvider`) and it stays, or
   the minecraft module gains a service file whose provider lives in the
   neoforge module and it fails the same way in reverse.
2. `devRunServices` stages those other twenty-nine, and
   `devRunMinecraftResources` folds them - plus a copy of every other resource -
   into Minecraft's resources jar. The whole-resources copy is not optional:
   Paper's classes sit in the minecraft module while all of Paper's resources
   were going to the other one, so a plain `getResourceAsStream` inside Paper
   code returned null and `PaperConfigurations` died on
   `/config-data/packet-limiter-upgrade-data.json` before startup finished.
   `META-INF`, the mixin configs and the root `pack.mcmeta` are excluded -
   those identify the neoforge mod file to FML and to the resource-pack loader.
3. `devRunSpark` unpacks spark-paper as another `minecraft` mod folder. In
   production `universalJar` merges spark's classes in so spark shares a module
   layer with `org.bukkit`; a dev run has no universal jar, and without this it
   dies on `ClassNotFoundException: me.lucko.spark.paper.api.PaperClassLookup`.

Two things about the wiring are worth knowing before changing it. The jar is
built with `Zip`, not `Jar`, because `Jar` generates a fresh manifest and the
original carries `Minecraft-Dists` plus a per-entry `Minecraft-Dist` section
for every client-only file; drop it and FML rejects the whole mod file, which
in the log looks exactly like the minecraft mod silently not existing. And the
classpath swap happens at configuration time, because `RunGameTask.exec()`
assigns `classpath` from its own `classpathProvider` after every `doFirst` has
run - assigning `classpath` in a `doFirst` is silently discarded, and the
property is already final by then.

Verified: `runServer` reaches `Done (19.5s)` with the same 1,407 recipes and
1,520 advancements the production jar loads, and `tools/test/mcclient.py login
127.0.0.1 25566 DevRunTester` joins it, is teleported, answers keep-alives,
runs `/youer version` and quits cleanly.

`runData` launches now too, and that is a trap rather than a feature, so the
build refuses to run it without `-PallowRunData` and says why.

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

A client connects too, and finding that out turned up a defect that a
headless boot could never have shown: **nobody could join.** `PlayerList`
sets `ServerPlayer.supressTrackerForLogin` around the join so that the player
is tracked after the player-list packets rather than before, and then calls
`ChunkMap#addEntity` itself - but the flag was set and never read, because the
only thing that reads it is a guard in `ChunkMap#addEntity` that the M2 merge
did not carry over. The player got tracked on the way in, the explicit call
then hit `IllegalStateException: Entity is already tracked!`, and the server
logged "Couldn't place player in world" and kicked with "Invalid player data" -
one line after "joined the game". That guard is restored, along with the rest
of the hunk it belongs to (the async catcher, Paper's illegal-call warning, and
Spigot's per-entity tracking range).

`tools/test/mcclient.py` is what found it: a minimal protocol client, about 200
lines and no dependencies. It does a server list ping, or an offline-mode login
carried through the configuration phase into play, where it accepts the
teleport, answers keep-alives, runs a command and disconnects. Packet ids are
read off `LoginProtocols`, `ConfigurationProtocols` and `GameProtocols` - the
registration order is the id - rather than guessed, which is worth remembering
when a protocol bump moves them.

Two things about that id derivation cost a round of debugging each, and both
are easy to get wrong again. First, count *every* `addPacket` call in a
template, not just the `GamePacketTypes.` ones: `CommonPacketTypes` and
`CookiePacketTypes` entries sit in the same numbering. Second, this is not a
vanilla server - **NeoForge registers one extra clientbound play packet ahead
of the vanilla ones, so every clientbound play id is one higher than the
decompiled order**. Serverbound is unshifted. A wrong id is not a quiet
failure: the client echoes some other packet's body back as a keep-alive and
the server drops the connection on `was larger than I expected`. The cheap
check is to join with `-v` and line the first few packets up against the
template - `CLIENTBOUND_CUSTOM_PAYLOAD` then `CLIENTBOUND_LOGIN` - before
trusting any of them.

```bash
# server.properties: online-mode=false, network-compression-threshold=-1
python3 tools/test/mcclient.py status
python3 tools/test/mcclient.py login 127.0.0.1 25565 YouerTester -v
```

Host and port are positional and optional. They did not used to be parsed at
all, so `login 127.0.0.1 25565 Tester` read the host as the username and talked
to the default port anyway - which is why early logs report a player called
`127.0.0.1`.

A full session now works end to end: login, configuration, play, the teleport
accepted, keep-alives answered, `/youer version` issued as a player, a clean
quit, and the player's data, stats and advancements all written (one
advancement criterion even fires on the way in, so the trigger machinery runs
for a live player). Reconnecting puts the player back at the position the
previous session saved.

What is still untested is a real Minecraft client, with a real world render and
a real mod handshake.

**8. Cosmetic residue - swept.** Some files carried comments where the earlier
rename tool had substituted a word inside the comment text (`// Paper -
Incremental chunk and p_11277_ saving`). Harmless to the build, and gone now.

**9. The merge silently dropped API hooks, and a name audit is how to find
them.** A green compile and a clean boot say nothing about whether a Paper
event still fires or a Purpur config still does anything: a dropped hunk
usually leaves behind code that compiles and runs, just vanilla. Two tools in
`tools/test/` look for these, and only one of them is worth your time.

`audit_paper_hunks.py` compares line signatures between an upstream patch and
this tree. It is honest about its own noise - roughly three real findings in
eighteen - because a decompiler renames locals and reflows lines, so most
"missing" lines are the same code wearing different names.

`audit_api_hooks.py` is the one that works. It only looks for names a
decompiler cannot rename: `io.papermc.paper.*` / `com.destroystokyo.paper.*` /
`org.purpurmc.purpur.*` types, `CraftEventFactory.*` calls, anything ending in
`Event`, and `paperConfig()` / `spigotConfig` / `purpurConfig` field reads. If
upstream's patch mentions one and this tree does not, that hook is gone.
Precision is close to perfect - every report it produced was either a real gap
or a deliberate, documented exclusion.

```bash
python3 tools/test/audit_api_hooks.py <upstream>/paper-server/patches/sources
python3 tools/test/audit_api_hooks.py <upstream>/purpur-server/minecraft-patches/sources
```

It found 16 Paper hooks and 30 Purpur ones that the M2 merge had dropped, all
since restored. Both sides now report only known exclusions: on the Paper side
three hooks that live in code this tree deliberately does not carry, and on the
Purpur side nothing at all.

Restoring these is rarely a straight copy, because NeoForge has usually already
rewritten the same line for its own extensibility. The pattern that works is to
keep NeoForge's hook and let the config decide, rather than picking a side:
`ConduitBlockEntity` consults Purpur's configurable frame list first and falls
through to NeoForge's `BlockState#isConduitFrame` for anything the config does
not name; `ShovelItem` and `HoeItem` do the same over `ItemAbilities`;
`PhantomSpawner` lets NeoForge's `PlayerSpawnPhantomsEvent` decide outright
when a listener set ALLOW or DENY and applies Purpur's toggles only where the
event defers; `ServerLevel` filters the spawner list the server handed it
instead of rebuilding it, so a spawner some other party contributed survives
the Purpur toggles. Where the config's default reproduces vanilla - as with
Purpur's phantom min/max per attempt - prefer the event's value until the
config is actually moved off its default, so a mod that set its own value still
wins on an unconfigured server.

## Things worth knowing before touching this

**A stale `projects/youer/src` is the most likely reason a checkout will not
compile.** That directory is generated by `./gradlew setup` from `patches/`, and
it is gitignored - so `git pull`, `git checkout` and branch switches never touch
it. Pull a change to `patches/` without re-running `setup` and you are compiling
yesterday's sources against today's `src/main/java`, which surfaces as a wall of
errors that look like the port is broken:

```
error: cannot find symbol   symbol: variable fallDamageMultiplier   location: variable block of type Block
error: cannot find symbol   symbol: variable purpurClient
error: method getExpDrop in interface IBlockExtension cannot be applied to given types
error: package org.bukkit.event.serverplayer.PlayerKickEvent does not exist
```

Every one of those members *is* added by a patch in this tree; the generated
copy simply predates it. The last line is the giveaway, because
`org.bukkit.event.serverplayer` is rename-tool residue that no tracked file has
carried for some time - if you see it, your generated tree is old. `./gradlew
setup` fixes all of it. Re-run it after every pull that touched `patches/`, and
when in doubt `rm -rf projects/youer/src` first.

Two things follow from this. Do not edit `projects/youer/src` and expect it to
survive: run `./gradlew :youer:genPatches` before the next `setup`, or the work
is gone. And do not diagnose a compile failure from that directory without first
confirming it was regenerated - CI builds from a clean clone precisely so there
is an authoritative answer to "is it the branch or is it me".

**CI.** `.github/workflows/build.yml` runs `setup`, `build` and `youerJar` on
pushes and pull requests and uploads the jars; `release.yml` builds a `v*` tag
and attaches them to a GitHub Release. Both go through
`.github/scripts/gradle.sh`, which retries **only** on transient artifact
resolution: `net.neoforged.moddev.repositories` injects `maven.neoforged.net`
ahead of the repositories declared in `settings.gradle`, and that host proxies
Maven Central, so every dependency is asked of it first - the very first CI run
died on a 502 from it while fetching `com.mysql:mysql-connector-j`, an artifact
Central serves perfectly well. A compile error is never retried. Both cache `~/.gradle/caches`, which is
where the NeoForm runtime keeps the decompiled Minecraft sources - a cold run
pays for the decompile, a warm one does not. `build.yml` also carries a
`patch-drift` job that runs `setup` then `genPatches` and fails if `patches/`
changed, which catches a patch that does not regenerate from its own output.

The workflow that was here before triggered on a branch named `1.21.8`, which
does not exist in this repository, so it had never run once.

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
