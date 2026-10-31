package com.huanghuang.rsintegration.feature.goety.brewing;

import net.minecraft.core.*;
import net.minecraft.sounds.*;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.gameevent.GameEvent;
import java.util.List;

/** Never forwards writes, sounds, entities or chunk access to the real server world. */
final class BrewPreviewLevel extends Level {
    private final Level source;
    private final BlockPos pos;
    private BlockState state;
    BrewPreviewLevel(Level source, BlockPos pos, BlockState state) {
        super((WritableLevelData) source.getLevelData(), source.dimension(), source.registryAccess(),
                source.dimensionTypeRegistration(), () -> InactiveProfiler.INSTANCE, false, false, 0L, 0);
        this.source = source;
        this.pos = pos.immutable();
        this.state = state;
    }
    private final java.util.Map<BlockPos, BlockState> surroundings = new java.util.HashMap<>();
    @Override public BlockState getBlockState(BlockPos query) {
        if (query.equals(pos)) return state;
        return surroundings.computeIfAbsent(query.immutable(), p -> source.hasChunkAt(p)
                ? source.getBlockState(p) : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }
    @Override public BlockEntity getBlockEntity(BlockPos query) { return null; }
    @Override public boolean setBlock(BlockPos query, BlockState value, int flags, int depth) { if (query.equals(pos)) state = value; return true; }
    @Override public void sendBlockUpdated(BlockPos p, BlockState before, BlockState after, int flags) {}
    @Override public void playSeededSound(Player p, double x, double y, double z, Holder<SoundEvent> event, SoundSource category, float volume, float pitch, long seed) {}
    @Override public void playSeededSound(Player p, Entity entity, Holder<SoundEvent> event, SoundSource category, float volume, float pitch, long seed) {}
    @Override public String gatherChunkSourceStats() { return "Goety RSI preview"; }
    @Override public Entity getEntity(int id) { return null; }
    @Override public MapItemSavedData getMapData(String id) { return null; }
    @Override public void setMapData(String id, MapItemSavedData data) {}
    @Override public int getFreeMapId() { return 0; }
    @Override public void destroyBlockProgress(int id, BlockPos p, int progress) {}
    @Override public Scoreboard getScoreboard() { return source.getScoreboard(); }
    @Override public RecipeManager getRecipeManager() { return source.getRecipeManager(); }
    @Override protected LevelEntityGetter<Entity> getEntities() { throw new UnsupportedOperationException("Preview entity access"); }
    @Override public List<? extends Player> players() { return List.of(); }
    @Override public ChunkSource getChunkSource() { throw new UnsupportedOperationException("Preview chunk access"); }
    @Override public LevelTickAccess<Block> getBlockTicks() { throw new UnsupportedOperationException("Preview ticks"); }
    @Override public LevelTickAccess<Fluid> getFluidTicks() { throw new UnsupportedOperationException("Preview ticks"); }
    @Override public int getSkyDarken() { return 0; }
    @Override public int getSeaLevel() { return 63; }
    @Override public float getShade(Direction direction, boolean shade) { return 1.0F; }
    @Override public int getBlockTint(BlockPos p, ColorResolver resolver) { return 0xFFFFFF; }
    @Override public long nextSubTickCount() { return 0L; }
    // Goety discounts soul costs by biome; bypass Level's chunk-backed biome lookup.
    @Override public Holder<net.minecraft.world.level.biome.Biome> getBiome(BlockPos query) { return source.getBiome(query); }
    @Override public Holder<net.minecraft.world.level.biome.Biome> getNoiseBiome(int x, int y, int z) { return source.getNoiseBiome(x, y, z); }
    @Override public Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) { return source.getUncachedNoiseBiome(x, y, z); }
    @Override public FeatureFlagSet enabledFeatures() { return source.enabledFeatures(); }
    @Override public void levelEvent(Player player, int event, BlockPos p, int data) {}
    @Override public void gameEvent(GameEvent event, net.minecraft.world.phys.Vec3 p, GameEvent.Context context) {}
}
