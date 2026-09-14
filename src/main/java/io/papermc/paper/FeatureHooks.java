package io.papermc.paper;

import io.papermc.paper.command.PaperSubcommand;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.bukkit.Chunk;
import org.bukkit.World;

public final class FeatureHooks {

    // Youer: temporarily stubbed for M3 (vendored-package rebase milestone) - the full Moonrise
    // chunk-system rewrite (ca.spottedleaf.moonrise.patches.chunk_system.*) is out of scope per
    // plan decision B3 (see C:\Users\esdev\.claude\plans\drifting-munching-creek.md). Only call
    // site is the /paper entity debug command (EntityCommand.java), not core gameplay.
    // this includes non-accessible entities
    public static Iterable<Entity> getAllEntities(final net.minecraft.server.level.ServerLevel world) {
        return world.getEntities().getAll(); // Paper - rewrite chunk system
    }

    public static void setPlayerChunkUnloadDelay(final long ticks) {
        // Youer: no-op stub, see comment above.
    }

    public static void initChunkTaskScheduler(final boolean useParallelGen) {
        // Youer: no-op stub, see comment above.
    }

    public static void registerPaperCommands(final Map<Set<String>, PaperSubcommand> commands) {
        // Youer: /paper fixlight and /paper debug/chunkinfo/holderinfo dropped, see comment above.
    }

    public static LevelChunkSection createSection(final Registry<Biome> biomeRegistry, final Level level, final ChunkPos chunkPos, final int chunkSection) {
        return new LevelChunkSection(biomeRegistry, level, chunkPos, chunkSection); // Paper - Anti-Xray - Add parameters
    }

    public static void sendChunkRefreshPackets(final List<ServerPlayer> playersInRange, final LevelChunk chunk) {
        // Paper start - Anti-Xray
        final Map<Object, ClientboundLevelChunkWithLightPacket> refreshPackets = new HashMap<>();
        for (final ServerPlayer player : playersInRange) {
            if (player.connection == null) continue;

            final Boolean shouldModify = chunk.getLevel().chunkPacketBlockController.shouldModify(player, chunk);
            player.connection.send(refreshPackets.computeIfAbsent(shouldModify, s -> { // Use connection to prevent creating firing event
                return new ClientboundLevelChunkWithLightPacket(chunk, chunk.level.getLightEngine(), null, null); // Youer - Anti-Xray preset states not merged
            }));
        }
        // Paper end - Anti-Xray
    }

    public static PalettedContainer<BlockState> emptyPalettedBlockContainer() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES); // Youer - Anti-Xray preset states not merged
    }

    public static Set<Long> getSentChunkKeys(final ServerPlayer player) {
        // Youer - moonrise chunk loader excluded; vanilla's chunk tracking view carries the same set
        final LongOpenHashSet keys = new LongOpenHashSet();
        player.getChunkTrackingView().forEach(pos -> keys.add(pos.toLong()));
        return LongSets.unmodifiable(keys);
    }

    public static Set<Chunk> getSentChunks(final ServerPlayer player) {
        // Youer - moonrise chunk loader excluded; walk vanilla's chunk tracking view
        final ObjectSet<org.bukkit.Chunk> chunks = new ObjectOpenHashSet<>();
        final World world = player.level().getWorld();
        player.getChunkTrackingView().forEach(pos -> chunks.add(world.getChunkAt(pos.toLong(), false)));
        return ObjectSets.unmodifiable(chunks);
    }

    public static boolean isChunkSent(final ServerPlayer player, final long chunkKey) {
        // Youer - moonrise chunk loader excluded
        return player.getChunkTrackingView().contains(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
    }

    public static boolean isSpiderCollidingWithWorldBorder(final Spider spider) {
        // Youer - moonrise collision system excluded; vanilla's world border check is equivalent here
        return !spider.level().getWorldBorder().isWithinBounds(spider.getBoundingBox().inflate(1.0E-7));
    }

    public static void dumpAllChunkLoadInfo(net.minecraft.server.MinecraftServer server, boolean isLongTimeout) {
        // Youer - moonrise chunk task scheduler excluded; nothing to dump
    }

    private static void dumpEntity(final Entity entity) {
    }

    public static org.bukkit.entity.Entity[] getChunkEntities(net.minecraft.server.level.ServerLevel world, int chunkX, int chunkZ) {
        // Youer - moonrise chunk system excluded; this is Paper's pre-moonrise implementation
        world.getChunk(chunkX, chunkZ); // ensure fully loaded
        final net.minecraft.world.level.entity.PersistentEntitySectionManager<net.minecraft.world.entity.Entity> entityManager = world.entityManager;
        final long pair = ChunkPos.asLong(chunkX, chunkZ);
        if (!entityManager.areEntitiesLoaded(pair)) {
            entityManager.ensureChunkQueuedForLoad(pair);
        }
        return entityManager.getEntities(new ChunkPos(chunkX, chunkZ)).stream()
            .map(net.minecraft.world.entity.Entity::getBukkitEntity)
            .filter(java.util.Objects::nonNull).toArray(org.bukkit.entity.Entity[]::new);
    }

    public static java.util.Collection<org.bukkit.plugin.Plugin> getPluginChunkTickets(net.minecraft.server.level.ServerLevel world,
                                                                                       int x, int z) {
        // Youer - moonrise chunk holder manager excluded; read vanilla's ticket storage
        final java.util.List<net.minecraft.server.level.Ticket> tickets =
            ((net.minecraft.server.level.DistanceManager) world.getChunkSource().chunkMap.distanceManager).ticketStorage.getTickets(ChunkPos.asLong(x, z));
        final com.google.common.collect.ImmutableList.Builder<org.bukkit.plugin.Plugin> ret = com.google.common.collect.ImmutableList.builder();
        for (final net.minecraft.server.level.Ticket ticket : tickets) {
            if (ticket.getType() == net.minecraft.server.level.TicketType.PLUGIN_TICKET) {
                ret.add((org.bukkit.plugin.Plugin) ticket.getIdentifier());
            }
        }
        return ret.build();
    }

    public static Map<org.bukkit.plugin.Plugin, java.util.Collection<org.bukkit.Chunk>> getPluginChunkTickets(net.minecraft.server.level.ServerLevel world) {
        Map<org.bukkit.plugin.Plugin, com.google.common.collect.ImmutableList.Builder<Chunk>> ret = new HashMap<>();
        net.minecraft.server.level.DistanceManager chunkDistanceManager = (net.minecraft.server.level.DistanceManager) world.getChunkSource().chunkMap.distanceManager;

        for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<java.util.List<net.minecraft.server.level.Ticket>> chunkTickets : chunkDistanceManager.ticketStorage.getTicketsCopy().long2ObjectEntrySet()) { // Youer - moonrise excluded; vanilla ticket storage
            long chunkKey = chunkTickets.getLongKey();
            java.util.List<net.minecraft.server.level.Ticket> tickets = chunkTickets.getValue();

            org.bukkit.Chunk chunk = null;
            for (net.minecraft.server.level.Ticket ticket : tickets) {
                if (ticket.getType() != net.minecraft.server.level.TicketType.PLUGIN_TICKET) {
                    continue;
                }

                if (chunk == null) {
                    chunk = world.getWorld().getChunkAt(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
                }

                ret.computeIfAbsent((org.bukkit.plugin.Plugin) ticket.getIdentifier(), (key) -> com.google.common.collect.ImmutableList.builder()).add(chunk);
            }
        }

        return ret.entrySet().stream().collect(com.google.common.collect.ImmutableMap.toImmutableMap(Map.Entry::getKey, (entry) -> entry.getValue().build()));
    }

    public static int getViewDistance(net.minecraft.server.level.ServerLevel world) {
        return world.getChunkSource().chunkMap.serverViewDistance; // Youer - moonrise player chunk loader excluded
    }

    public static int getSimulationDistance(net.minecraft.server.level.ServerLevel world) {
        return ((net.minecraft.server.level.DistanceManager) world.getChunkSource().chunkMap.distanceManager).simulationDistance; // Youer - moonrise player chunk loader excluded
    }

    public static int getSendViewDistance(net.minecraft.server.level.ServerLevel world) {
        return world.getChunkSource().chunkMap.serverViewDistance; // Youer - no separate send view distance without moonrise
    }

    public static void setViewDistance(net.minecraft.server.level.ServerLevel world, int distance) {
        if (distance < 2 || distance > 32) {
            throw new IllegalArgumentException("View distance " + distance + " is out of range of [2, 32]");
        }
        world.chunkSource.chunkMap.setServerViewDistance(distance);
    }

    public static void setSimulationDistance(net.minecraft.server.level.ServerLevel world, int distance) {
        if (distance < 2 || distance > 32) {
            throw new IllegalArgumentException("Simulation distance " + distance + " is out of range of [2, 32]");
        }
        ((net.minecraft.server.level.DistanceManager) world.chunkSource.chunkMap.distanceManager).updateSimulationDistance(distance);
    }

    public static void setSendViewDistance(net.minecraft.server.level.ServerLevel world, int distance) {
        world.chunkSource.chunkMap.setServerViewDistance(distance); // Youer - no separate send view distance without moonrise
    }

    public static void tickEntityManager(net.minecraft.server.level.ServerLevel world) {
        // Paper - rewrite chunk system
    }

    public static void closeEntityManager(net.minecraft.server.level.ServerLevel world, boolean save) {
        // Paper - rewrite chunk system
    }

    public static java.util.concurrent.Executor getWorldgenExecutor() {
        return Runnable::run; // Paper - rewrite chunk system
    }

    // Youer - per-player view distances come from moonrise, which is excluded. The
    // server-wide values stay in effect; these are no-ops rather than throwing, so a
    // plugin that sets them keeps working against the server-wide distance.
    public static void setViewDistance(ServerPlayer player, int distance) {
    }

    public static void setSimulationDistance(ServerPlayer player, int distance) {
    }

    public static void setSendViewDistance(ServerPlayer player, int distance) {
    }

}