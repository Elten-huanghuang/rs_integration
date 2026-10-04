package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.mojang.logging.LogUtils;
import com.huanghuang.rsintegration.crafting.CraftOutputInterceptor;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.wrappers.BucketPickupHandlerWrapper;
import net.minecraftforge.fluids.capability.wrappers.FluidBlockWrapper;
import org.slf4j.Logger;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** 分批采集世界液体；已取出的余量随升级物品保存，避免存储变化时丢失。 */
public final class RSMagnetFluidCollector {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String PENDING = "rsiMagnetPendingFluid";
    static final String UNCERTAIN = "rsiMagnetFluidUncertain";
    static final int SCAN_LIMIT = 512;
    static final int SOURCE_LIMIT = 4;
    private long cursor;

    public void collect(Level level, BlockPos center, int radius, ServerPlayer player,
            StorageSession session, ItemStack upgrade, Runnable save, Predicate<FluidStack> filter) {
        if (!session.hasPermission(player, StoragePermission.INSERT)) return;
        if (!flushPending(session, player, upgrade, save)) return;
        int range = Math.max(0, radius);
        long width = 2L * range + 1;
        long volume = width * width * width;
        int sources = 0;
        for (int checked = 0; checked < Math.min(SCAN_LIMIT, volume); checked++) {
            long index = cursor % volume;
            cursor = (index + 1) % volume;
            BlockPos pos = center.offset(offset(index % width), offset((index / width) % width),
                    offset(index / (width * width)));
            if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos)) continue;
            BlockState state = level.getBlockState(pos);
            IFluidHandler source = sourceHandler(level, pos, state);
            if (source == null || CraftOutputInterceptor.isInActiveZone(level, Vec3.atCenterOf(pos))) continue;
            if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos, Direction.UP, new ItemStack(Items.BUCKET))) continue;
            if (!transfer(source, session, player, upgrade, save, filter,
                    () -> canCollectSource(level, pos, player))) continue;
            if (++sources >= SOURCE_LIMIT || !pending(upgrade).isEmpty()
                    || upgrade.getOrCreateTag().getBoolean(UNCERTAIN)) break;
        }
    }

    private static int offset(long index) {
        return (int) ((index + 1) / 2) * (index % 2 == 0 ? -1 : 1);
    }

    private static boolean canCollectSource(Level level, BlockPos pos, ServerPlayer player) {
        // 采液不是手持工具挖掘；BreakEvent 会误触发连锁挖掘及工具耐久消耗。
        FillBucketEvent event = new FillBucketEvent(player, new ItemStack(Items.BUCKET), level,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        if (MinecraftForge.EVENT_BUS.post(event)) return false;
        // ALLOW 表示其他模组已接管装桶，也不能再自行抽取一次。
        return event.getResult() == Event.Result.DEFAULT;
    }

    static IFluidHandler sourceHandler(Level level, BlockPos pos, BlockState state) {
        // 不读取方块实体能力，也不采集含水方块；只处理世界里的液体源。
        if (state.hasBlockEntity() || state.getFluidState().isEmpty() || !state.getFluidState().isSource()) return null;
        if (state.getBlock() instanceof IFluidBlock fluidBlock) return new FluidBlockWrapper(fluidBlock, level, pos);
        if (state.getBlock() instanceof LiquidBlock && state.getBlock() instanceof BucketPickup pickup) {
            return new BucketPickupHandlerWrapper(pickup, level, pos);
        }
        return null;
    }

    static boolean transfer(IFluidHandler source, StorageSession session, ServerPlayer player,
            ItemStack upgrade, Runnable save) {
        return transfer(source, session, player, upgrade, save, fluid -> true);
    }

    static boolean transfer(IFluidHandler source, StorageSession session, ServerPlayer player,
            ItemStack upgrade, Runnable save, Predicate<FluidStack> filter) {
        return transfer(source, session, player, upgrade, save, filter, () -> true);
    }

    private static boolean transfer(IFluidHandler source, StorageSession session, ServerPlayer player,
            ItemStack upgrade, Runnable save, Predicate<FluidStack> filter, BooleanSupplier canCollect) {
        FluidStack offered = source.drain(1000, IFluidHandler.FluidAction.SIMULATE);
        if (offered.isEmpty() || !filter.test(offered)) return false;
        ItemStack token = InkFluidSupport.token(offered);
        if (!session.insert(player, token, true).complete()) return false;
        if (!canCollect.getAsBoolean()) return false;
        FluidStack collected = source.drain(offered, IFluidHandler.FluidAction.EXECUTE);
        if (collected.isEmpty()) return false;
        storePending(upgrade, collected, save);
        flushPending(session, player, upgrade, save);
        return true;
    }

    static FluidStack pending(ItemStack upgrade) {
        return upgrade.hasTag() ? FluidStack.loadFluidStackFromNBT(upgrade.getTag().getCompound(PENDING)) : FluidStack.EMPTY;
    }

    private static void storePending(ItemStack upgrade, FluidStack fluid, Runnable save) {
        if (fluid.isEmpty()) upgrade.getOrCreateTag().remove(PENDING);
        else upgrade.getOrCreateTag().put(PENDING, fluid.writeToNBT(new CompoundTag()));
        save.run();
    }

    static boolean flushPending(StorageSession session, ServerPlayer player, ItemStack upgrade, Runnable save) {
        if (upgrade.getOrCreateTag().getBoolean(UNCERTAIN)) return false;
        FluidStack fluid = pending(upgrade);
        if (fluid.isEmpty()) return true;
        StorageOperationResult result;
        try {
            result = session.insert(player, InkFluidSupport.token(fluid), false);
        } catch (RuntimeException failure) {
            upgrade.getOrCreateTag().putBoolean(UNCERTAIN, true);
            save.run();
            LOGGER.error("次元磁铁流体存入异常，已暂停采集并保留液体记录", failure);
            return false;
        }
        if (result.remainder().isEmpty()) {
            // 无法确认已存入的数量时保留记录并停机，不能重试导致重复存入。
            upgrade.getOrCreateTag().putBoolean(UNCERTAIN, true);
            save.run();
            LOGGER.error("次元磁铁流体存入结果不确定，已暂停采集并保留液体记录");
            return false;
        }
        FluidStack remainder = InkFluidSupport.fluid(result.remainder().orElseThrow());
        storePending(upgrade, remainder, save);
        return remainder.isEmpty();
    }
}
