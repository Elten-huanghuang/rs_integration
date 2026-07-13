package com.huanghuang.rsintegration.mods.touhoulittlemaid;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.reflection.probes.TLMReflection;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Resolves any TLM altar structure block to the unique render/main altar BE. */
public final class TlmAltarStructure {
    private TlmAltarStructure() {}

    public record Resolved(BlockPos mainPos, BlockEntity mainEntity,
                           List<BlockPos> allPositions, AABB bounds) {}

    @Nullable
    @SuppressWarnings("unchecked")
    public static Resolved resolve(ServerLevel level, BlockEntity seed) {
        if (seed == null || TLMReflection.altarBEClass == null
                || !TLMReflection.altarBEClass.isInstance(seed)) return null;
        try {
            Object data = Reflect.getMethodOrThrow(TLMReflection.altarBEClass,
                    "getBlockPosList", "getBlockPosList").invoke(seed);
            List<BlockPos> positions = Reflect.invokeExact(data, "getData", new Class<?>[0])
                    .map(v -> (List<BlockPos>) v).orElse(null);
            if (positions == null || positions.isEmpty()) return null;
            BlockEntity main = null;
            BlockPos mainPos = null;
            int renders = 0;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : positions) {
                minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
                if (!level.isLoaded(pos)) level.getChunk(pos);
                BlockEntity candidate = level.getBlockEntity(pos);
                if (candidate == null || !TLMReflection.altarBEClass.isInstance(candidate)) continue;
                Object value = Reflect.getMethodOrThrow(TLMReflection.altarBEClass,
                        "isRender", "isRender").invoke(candidate);
                if (Boolean.TRUE.equals(value)) {
                    renders++;
                    main = candidate;
                    mainPos = pos.immutable();
                }
            }
            if (renders != 1 || main == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-TLM] Altar structure has {} render/main candidates (positions={})",
                        renders, positions.size());
                return null;
            }
            AABB bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 4, maxZ + 1).inflate(1.0);
            return new Resolved(mainPos, main, List.copyOf(new ArrayList<>(positions)), bounds);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-TLM] Failed to resolve main altar BE", e);
            return null;
        }
    }
}
