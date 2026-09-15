package ca.spottedleaf.moonrise.youer;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.PlatformHooks;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/**
 * Youer's {@link PlatformHooks}, the counterpart of upstream's {@code ca.spottedleaf.moonrise.paper.PaperHooks}.
 *
 * <p>Paper's implementation extends {@code BaseChunkSystemHooks}, which is part of the Moonrise chunk
 * system. That rewrite is excluded from this port (plan decision B3), so the {@link
 * ca.spottedleaf.moonrise.common.util.ChunkSystemHooks} half is implemented against vanilla's own
 * {@code ChunkMap}/{@code ServerChunkCache} instead: the holder queries read vanilla's maps, the
 * chunk-state callbacks are Moonrise bookkeeping and become no-ops, and the per-player view
 * distances fall back to the server-wide ones (the same trade-off {@code FeatureHooks} already
 * makes). Everything outside the chunk system follows Paper's implementation.</p>
 */
public final class YouerHooks implements PlatformHooks {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    @Override
    public String getBrand() {
        return "Youer";
    }

    @Override
    public int getLightEmission(final BlockState blockState, final BlockGetter world, final BlockPos pos) {
        return blockState.getLightEmission();
    }

    @Override
    public Predicate<BlockState> maybeHasLightEmission() {
        return (final BlockState state) -> state.getLightEmission() != 0;
    }

    @Override
    public boolean hasCurrentlyLoadingChunk() {
        return false;
    }

    @Override
    public LevelChunk getCurrentlyLoadingChunk(final GenerationChunkHolder holder) {
        return null;
    }

    @Override
    public void setCurrentlyLoading(final GenerationChunkHolder holder, final LevelChunk levelChunk) {
    }

    @Override
    public void chunkFullStatusComplete(final LevelChunk newChunk, final ProtoChunk original) {
    }

    @Override
    public boolean allowAsyncTicketUpdates() {
        // Youer - B3: vanilla's DistanceManager is not thread safe, ticket updates stay on the main thread.
        return false;
    }

    @Override
    public void onChunkHolderTicketChange(final ServerLevel world, final ChunkHolder holder, final int oldLevel, final int newLevel) {
    }

    @Override
    public void chunkUnloadFromWorld(final LevelChunk chunk) {
    }

    @Override
    public void chunkSyncSave(final ServerLevel world, final ChunkAccess chunk, final SerializableChunkData data) {
    }

    @Override
    public void onChunkWatch(final ServerLevel world, final LevelChunk chunk, final ServerPlayer player) {
    }

    @Override
    public void onChunkUnWatch(final ServerLevel world, final ChunkPos chunk, final ServerPlayer player) {
    }

    @Override
    public void addToGetEntities(final Level world, final Entity entity, final AABB boundingBox,
                                 final Predicate<? super Entity> predicate, final List<Entity> into) {
        final Collection<net.neoforged.neoforge.entity.PartEntity<?>> parts = world.dragonParts(); // Youer - NeoForge generalises dragon parts to PartEntity
        if (parts.isEmpty()) {
            return;
        }

        for (final net.neoforged.neoforge.entity.PartEntity<?> part : parts) {
            if (part != entity && part.getBoundingBox().intersects(boundingBox) && (predicate == null || predicate.test(part))) {
                into.add(part);
            }
        }
    }

    @Override
    public <T extends Entity> void addToGetEntities(final Level world, final EntityTypeTest<Entity, T> entityTypeTest,
                                                    final AABB boundingBox, final Predicate<? super T> predicate,
                                                    final List<? super T> into, final int maxCount) {
        if (into.size() >= maxCount) {
            return;
        }

        final Collection<net.neoforged.neoforge.entity.PartEntity<?>> parts = world.dragonParts(); // Youer - NeoForge generalises dragon parts to PartEntity
        if (parts.isEmpty()) {
            return;
        }
        for (final net.neoforged.neoforge.entity.PartEntity<?> part : parts) {
            if (!part.getBoundingBox().intersects(boundingBox)) {
                continue;
            }
            final T casted = (T)entityTypeTest.tryCast(part);
            if (casted != null && (predicate == null || predicate.test(casted))) {
                into.add(casted);
                if (into.size() >= maxCount) {
                    break;
                }
            }
        }
    }

    @Override
    public void entityMove(final Entity entity, final long oldSection, final long newSection) {
    }

    @Override
    public boolean configFixMC224294() {
        return true;
    }

    @Override
    public boolean configAutoConfigSendDistance() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingAdvanced.autoConfigSendDistance;
    }

    @Override
    public double configPlayerMaxLoadRate() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkLoadRate;
    }

    @Override
    public double configPlayerMaxGenRate() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkGenerateRate;
    }

    @Override
    public double configPlayerMaxSendRate() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkSendRate;
    }

    @Override
    public int configPlayerMaxConcurrentLoads() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingAdvanced.playerMaxConcurrentChunkLoads;
    }

    @Override
    public int configPlayerMaxConcurrentGens() {
        return io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingAdvanced.playerMaxConcurrentChunkGenerates;
    }

    @Override
    public long configAutoSaveInterval(final ServerLevel world) {
        return world.paperConfig().chunks.autoSaveInterval.value();
    }

    @Override
    public int configMaxAutoSavePerTick(final ServerLevel world) {
        return world.paperConfig().chunks.maxAutoSaveChunksPerTick;
    }

    @Override
    public boolean configFixMC159283() {
        return true;
    }

    @Override
    public boolean forceNoSave(final ChunkAccess chunk) {
        return chunk instanceof LevelChunk levelChunk && levelChunk.mustNotSave;
    }

    @Override
    public CompoundTag convertNBT(final DSL.TypeReference type, final DataFixer dataFixer, final CompoundTag nbt,
                                  final int fromVersion, final int toVersion) {
        return (CompoundTag)dataFixer.update(
            type, new Dynamic<>(NbtOps.INSTANCE, nbt), fromVersion, toVersion
        ).getValue();
    }

    @Override
    public boolean hasMainChunkLoadHook() {
        return false;
    }

    @Override
    public void mainChunkLoad(final ChunkAccess chunk, final SerializableChunkData chunkData) {
    }

    @Override
    public List<Entity> modifySavedEntities(final ServerLevel world, final int chunkX, final int chunkZ, final List<Entity> entities) {
        return entities;
    }

    @Override
    public void unloadEntity(final Entity entity) {
        entity.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK, org.bukkit.event.entity.EntityRemoveEvent.Cause.UNLOAD);
    }

    @Override
    public void postLoadProtoChunk(final ServerLevel world, final ProtoChunk chunk) {
        try (final net.minecraft.util.ProblemReporter.ScopedCollector scopedCollector =
                 new net.minecraft.util.ProblemReporter.ScopedCollector(chunk.problemPath(), LOGGER)) {
            net.minecraft.world.level.chunk.status.ChunkStatusTasks.postLoadProtoChunk(
                world,
                net.minecraft.world.level.storage.TagValueInput.create(scopedCollector, world.registryAccess(), chunk.getEntities()),
                chunk.getPos()
            );
        }
    }

    @Override
    public int modifyEntityTrackingRange(final Entity entity, final int currentRange) {
        return org.spigotmc.TrackingRange.getEntityTrackingRange(entity, currentRange);
    }

    @Override
    public boolean addTicketForEnderPearls(final ServerLevel world) {
        return !world.paperConfig().misc.legacyEnderPearlBehavior;
    }

    // Youer - B3 start: ChunkSystemHooks on vanilla's chunk system instead of Moonrise's.

    @Override
    public void scheduleChunkTask(final ServerLevel level, final int chunkX, final int chunkZ, final Runnable run) {
        level.getServer().execute(run);
    }

    @Override
    public void scheduleChunkTask(final ServerLevel level, final int chunkX, final int chunkZ, final Runnable run,
                                  final Priority priority) {
        // Youer - B3: vanilla's main thread executor has no priorities.
        level.getServer().execute(run);
    }

    @Override
    public void scheduleChunkLoad(final ServerLevel level, final int chunkX, final int chunkZ, final boolean gen,
                                  final ChunkStatus toStatus, final boolean addTicket, final Priority priority,
                                  final Consumer<ChunkAccess> onComplete) {
        level.getChunkSource().getChunkFuture(chunkX, chunkZ, toStatus, gen || addTicket)
            .thenAccept(result -> onComplete.accept(result.orElse(null)));
    }

    @Override
    public void scheduleChunkLoad(final ServerLevel level, final int chunkX, final int chunkZ, final ChunkStatus toStatus,
                                  final boolean addTicket, final Priority priority, final Consumer<ChunkAccess> onComplete) {
        this.scheduleChunkLoad(level, chunkX, chunkZ, true, toStatus, addTicket, priority, onComplete);
    }

    @Override
    public void scheduleTickingState(final ServerLevel level, final int chunkX, final int chunkZ,
                                     final FullChunkStatus toStatus, final boolean addTicket,
                                     final Priority priority, final Consumer<LevelChunk> onComplete) {
        // Youer - B3: vanilla only exposes chunk-status futures, so a full chunk is the closest equivalent.
        this.scheduleChunkLoad(level, chunkX, chunkZ, true, ChunkStatus.FULL, addTicket, priority,
            (final ChunkAccess chunk) -> onComplete.accept(chunk instanceof LevelChunk levelChunk ? levelChunk : null));
    }

    @Override
    public List<ChunkHolder> getVisibleChunkHolders(final ServerLevel level) {
        return new ArrayList<>(level.getChunkSource().chunkMap.visibleChunkMap.values());
    }

    @Override
    public List<ChunkHolder> getUpdatingChunkHolders(final ServerLevel level) {
        return new ArrayList<>(level.getChunkSource().chunkMap.updatingChunkMap.values());
    }

    @Override
    public int getVisibleChunkHolderCount(final ServerLevel level) {
        return level.getChunkSource().chunkMap.visibleChunkMap.size();
    }

    @Override
    public int getUpdatingChunkHolderCount(final ServerLevel level) {
        return level.getChunkSource().chunkMap.updatingChunkMap.size();
    }

    @Override
    public boolean hasAnyChunkHolders(final ServerLevel level) {
        return !level.getChunkSource().chunkMap.updatingChunkMap.isEmpty();
    }

    @Override
    public boolean screenEntity(final ServerLevel level, final Entity entity, final boolean fromDisk, final boolean event) {
        return true;
    }

    @Override
    public void onChunkHolderCreate(final ServerLevel level, final ChunkHolder holder) {
    }

    @Override
    public void onChunkHolderDelete(final ServerLevel level, final ChunkHolder holder) {
    }

    @Override
    public void onChunkPreBorder(final LevelChunk chunk, final ChunkHolder holder) {
    }

    @Override
    public void onChunkBorder(final LevelChunk chunk, final ChunkHolder holder) {
    }

    @Override
    public void onChunkNotBorder(final LevelChunk chunk, final ChunkHolder holder) {
    }

    @Override
    public void onChunkPostNotBorder(final LevelChunk chunk, final ChunkHolder holder) {
    }

    @Override
    public void onChunkTicking(final LevelChunk chunk, final ChunkHolder holder) {
    }

    @Override
    public void onChunkNotTicking(final LevelChunk chunk, final ChunkHolder holder) {
    }

    @Override
    public void onChunkEntityTicking(final LevelChunk chunk, final ChunkHolder holder) {
    }

    @Override
    public void onChunkNotEntityTicking(final LevelChunk chunk, final ChunkHolder holder) {
    }

    @Override
    public ChunkHolder getUnloadingChunkHolder(final ServerLevel level, final int chunkX, final int chunkZ) {
        return level.getChunkSource().chunkMap.getUnloadingChunkHolder(chunkX, chunkZ);
    }

    @Override
    public int getSendViewDistance(final ServerPlayer player) {
        return io.papermc.paper.FeatureHooks.getSendViewDistance(player.level());
    }

    @Override
    public int getViewDistance(final ServerPlayer player) {
        return io.papermc.paper.FeatureHooks.getViewDistance(player.level());
    }

    @Override
    public int getTickViewDistance(final ServerPlayer player) {
        return io.papermc.paper.FeatureHooks.getSimulationDistance(player.level());
    }

    @Override
    public void addPlayerToDistanceMaps(final ServerLevel world, final ServerPlayer player) {
    }

    @Override
    public void removePlayerFromDistanceMaps(final ServerLevel world, final ServerPlayer player) {
    }

    @Override
    public void updateMaps(final ServerLevel world, final ServerPlayer player) {
    }

    @Override
    public long[] getCounterTypesUncached(final TicketType type) {
        return new long[0];
    }

    // Youer - B3 end
}
