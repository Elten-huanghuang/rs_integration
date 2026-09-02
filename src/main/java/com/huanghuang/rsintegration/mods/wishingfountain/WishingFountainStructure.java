package com.huanghuang.rsintegration.mods.wishingfountain;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional, reflection-only access to Wishing Fountain's formed structure. */
public final class WishingFountainStructure {
    public static final String ENTITY_CLASS =
            "io.github.poisonsheep.wishingfountain.tileentity.WFEntity";

    public record Resolved(BlockPos corePos, BlockEntity coreEntity,
                           List<BlockPos> inputPositions,
                           List<BlockEntity> inputEntities) {}

    private WishingFountainStructure() {}

    @Nullable
    public static Resolved resolve(Level level, BlockPos clickedPos) {
        if (level == null || clickedPos == null || !level.hasChunkAt(clickedPos)) return null;
        BlockEntity clicked = level.getBlockEntity(clickedPos);
        if (!isEntity(clicked)) return null;

        List<BlockPos> positions = readStructurePositions(clicked);
        if (positions.isEmpty()) {
            // Older/partially saved fountains can lose PosListData while the
            // formed 3x3 ring remains. Reconstruct and validate that ring.
            BlockPos fallbackCore = findNearbyCore(level, clickedPos);
            if (fallbackCore == null) return null;
            positions = fallbackRing(fallbackCore);
        }

        BlockPos corePos = null;
        BlockEntity core = null;
        Map<BlockPos, BlockEntity> inputs = new LinkedHashMap<>();
        for (BlockPos position : positions) {
            if (position == null || !level.hasChunkAt(position)) return null;
            BlockEntity candidate = level.getBlockEntity(position);
            if (!isEntity(candidate) || candidate.isRemoved()) return null;
            if (invokeBoolean(candidate, "isRender")) {
                if (core != null) return null;
                corePos = position.immutable();
                core = candidate;
            }
            if (invokeBoolean(candidate, "isCanPlaceItem")) {
                inputs.put(position.immutable(), candidate);
            }
        }
        if (core == null || inputs.size() != 8) return null;
        return new Resolved(corePos, core, List.copyOf(inputs.keySet()),
                List.copyOf(inputs.values()));
    }

    private static List<BlockPos> fallbackRing(BlockPos centre) {
        List<BlockPos> positions = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                positions.add(centre.offset(dx, 0, dz).immutable());
            }
        }
        return positions;
    }

    @Nullable
    private static BlockPos findNearbyCore(Level level, BlockPos origin) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos candidatePos = origin.offset(dx, 0, dz);
                if (!level.hasChunkAt(candidatePos)) continue;
                BlockEntity candidate = level.getBlockEntity(candidatePos);
                if (isEntity(candidate) && invokeBoolean(candidate, "isRender")) {
                    return candidatePos.immutable();
                }
            }
        }
        return null;
    }

    @Nullable
    public static BlockPos resolveCorePosition(Level level, BlockPos clickedPos) {
        Resolved resolved = resolve(level, clickedPos);
        return resolved == null ? null : resolved.corePos();
    }

    public static boolean isEntity(@Nullable BlockEntity entity) {
        return entity != null && ENTITY_CLASS.equals(entity.getClass().getName());
    }

    @Nullable
    public static IItemHandlerModifiable itemHandler(BlockEntity entity) {
        if (!isEntity(entity)) return null;
        try {
            Field field = entity.getClass().getField("handler");
            Object value = field.get(entity);
            return value instanceof IItemHandlerModifiable handler ? handler : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-WishingFountain] Could not access material handler at {}",
                    entity.getBlockPos(), failure);
            return null;
        }
    }

    public static void refresh(BlockEntity entity) {
        try {
            entity.getClass().getMethod("refresh").invoke(entity);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            entity.setChanged();
        }
    }

    private static List<BlockPos> readStructurePositions(BlockEntity entity) {
        try {
            Object data = entity.getClass().getMethod("getBlockPosList").invoke(entity);
            if (data == null) return List.of();
            Method getter = data.getClass().getMethod("getData");
            Object raw = getter.invoke(data);
            if (!(raw instanceof List<?> list)) return List.of();
            List<BlockPos> positions = new ArrayList<>(list.size());
            for (Object value : list) {
                if (value instanceof BlockPos pos) positions.add(pos.immutable());
            }
            return positions;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-WishingFountain] Could not read structure positions at {}",
                    entity.getBlockPos(), failure);
            return List.of();
        }
    }

    private static boolean invokeBoolean(BlockEntity entity, String methodName) {
        try {
            Object value = entity.getClass().getMethod(methodName).invoke(entity);
            return value instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
