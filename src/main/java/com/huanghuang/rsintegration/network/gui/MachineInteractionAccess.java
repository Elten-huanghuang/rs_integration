package com.huanghuang.rsintegration.network.gui;

import com.huanghuang.rsintegration.network.ProtectionChecker;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;

/** 远程机器操作共享的目标、所有权和交互保护校验。 */
public final class MachineInteractionAccess {
    private MachineInteractionAccess() {}

    public enum Error {
        NOT_BOUND,
        DIMENSION_UNAVAILABLE,
        CHUNK_UNAVAILABLE,
        PROTECTED
    }

    public record Target(
            ServerLevel level,
            @Nullable BlockEntity blockEntity,
            @Nullable BindingStorage.BindingEntry bindingEntry) {}

    public record Result(
            @Nullable Target target, @Nullable Error error) {
        public boolean success() {
            return target != null && error == null;
        }

        static Result success(Target target) {
            return new Result(target, null);
        }

        static Result failure(Error error) {
            return new Result(null, error);
        }
    }

    public static Result resolve(
            ServerPlayer player, ResourceLocation dimension, BlockPos position,
            boolean requireBinding, boolean fireInteractionEvent) {
        BindingStorage.BindingEntry bindingEntry =
                AltarBindingRegistry.findBindingEntry(
                        player, dimension, position);
        ResourceKey<Level> dimensionKey = ResourceKey.create(
                Registries.DIMENSION, dimension);
        if (requireBinding
                && (bindingEntry == null
                || !AltarBindingRegistry.isBound(
                dimensionKey, position, player))) {
            return Result.failure(Error.NOT_BOUND);
        }

        if (fireInteractionEvent) {
            PlayerInteractEvent.RightClickBlock event =
                    new PlayerInteractEvent.RightClickBlock(
                            player, InteractionHand.MAIN_HAND, position,
                            new BlockHitResult(
                                    new Vec3(
                                            position.getX() + 0.5,
                                            position.getY() + 1.0,
                                            position.getZ() + 0.5),
                                    Direction.UP, position, false));
            MinecraftForge.EVENT_BUS.post(event);
            if (event.isCanceled()) return Result.failure(Error.PROTECTED);
        }

        ServerLevel level = player.getServer() == null
                ? null : player.getServer().getLevel(dimensionKey);
        if (level == null) {
            return Result.failure(Error.DIMENSION_UNAVAILABLE);
        }
        if (!level.hasChunkAt(position)) {
            return Result.failure(Error.CHUNK_UNAVAILABLE);
        }
        if (!ProtectionChecker.canInteract(player, level, position)) {
            return Result.failure(Error.PROTECTED);
        }
        return Result.success(new Target(
                level, level.getBlockEntity(position), bindingEntry));
    }

    public static void sendError(ServerPlayer player, @Nullable Error error) {
        if (error == null) return;
        String key = switch (error) {
            case NOT_BOUND -> "rsi.machine.error.not_bound";
            case DIMENSION_UNAVAILABLE -> "rsi.error.dim_not_loaded";
            case CHUNK_UNAVAILABLE -> "rsi.error.chunk_unloaded";
            case PROTECTED -> "rsi.error.protected_block";
        };
        player.sendSystemMessage(Component.translatable(key));
    }
}
