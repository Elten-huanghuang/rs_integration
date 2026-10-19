package com.huanghuang.rsintegration.mods.farmingforblockheads;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.batch.VirtualExchangeBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.VirtualExchangeRecipe;
import com.huanghuang.rsintegration.reflection.probes.FarmingForBlockheadsReflection;
import com.huanghuang.rsintegration.util.LogSampler;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FarmingForBlockheads Market 的纯兑换委托。
 *
 * <p>绑定的 Market 只作为访问凭证；执行阶段不操作方块，成本与产物
 * 由公共虚拟兑换基类一次性缩放并分片结算。</p>
 */
public final class MarketBatchDelegate extends VirtualExchangeBatchDelegate {
    private static final LogSampler VALIDATION_LOGS = new LogSampler(60_000L);

    private static volatile boolean probed;
    private static volatile boolean available;
    private static volatile Object marketRegistryInstance;

    private static void probe() {
        if (probed) return;
        probed = true;
        try {
            Field instanceField = FarmingForBlockheadsReflection.marketRegistryClass.getField("INSTANCE");
            marketRegistryInstance = instanceField.get(null);
            available = marketRegistryInstance != null;
        } catch (Exception exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-Market] MarketRegistry not available", exception);
            available = false;
        }
    }

    @Override
    protected boolean validateExchangeAccess(ServerPlayer player, ResourceLocation recipeId,
                                             @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            warnValidation("dimension", "[RSI-Market] validateAndInit: dimension unavailable: {}", dim);
            return false;
        }
        setMachineDim(level.dimension().location());
        setMachineServer(player.server);
        if (pos == null) {
            warnValidation("position", "[RSI-Market] validateAndInit: pos is null");
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            warnValidation("block-entity", "[RSI-Market] validateAndInit: no block entity at {}", pos);
            return false;
        }
        if (!FarmingForBlockheadsReflection.marketBEClass.isInstance(blockEntity)) {
            warnValidation("machine-type",
                    "[RSI-Market] validateAndInit: not a MarketBlockEntity, got {}",
                    blockEntity.getClass().getName());
            return false;
        }
        return true;
    }

    @Override
    @Nullable
    protected VirtualExchangeRecipe resolveExchangeRecipe(ServerPlayer player, ResourceLocation recipeId) {
        probe();
        Recipe<?> recipe = resolveWrapper(recipeId);
        if (!(recipe instanceof MarketRecipeWrapper marketRecipe)) {
            warnValidation("recipe",
                    "[RSI-Market] validateAndInit: recipe {} is not a MarketRecipeWrapper", recipeId);
            return null;
        }
        return marketRecipe;
    }

    private static void warnValidation(String reason, String format, Object... arguments) {
        if (VALIDATION_LOGS.allow(reason)) {
            RSIntegrationMod.LOGGER.warn(format, arguments);
        }
    }

    @Nullable
    private static Recipe<?> resolveWrapper(ResourceLocation recipeId) {
        String path = recipeId.getPath();
        if (!path.startsWith("market/")) return null;
        String uuidString = path.substring("market/".length());
        try {
            return resolveMarketEntry(UUID.fromString(uuidString));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    private static Recipe<?> resolveMarketEntry(UUID entryId) {
        probe();
        if (!available || marketRegistryInstance == null) return null;
        try {
            Class<?> registryClass = FarmingForBlockheadsReflection.marketRegistryClass;
            if (registryClass == null) return null;
            Method getEntryById = Reflect.findMethod(registryClass,
                    "getEntryById", new Class<?>[]{UUID.class});
            if (getEntryById == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-Market] getEntryById method not found");
                return null;
            }
            Object entry = getEntryById.invoke(
                    Modifier.isStatic(getEntryById.getModifiers())
                            ? null : marketRegistryInstance, entryId);
            return entry == null ? null : wrapEntry(entry);
        } catch (Exception exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-Market] Failed to resolve entry {}", entryId, exception);
            return null;
        }
    }

    /**
     * Resolve a Market entry from a recipe ID with format
     * {@code farmingforblockheads:market/<uuid>}.
     */
    @Nullable
    public static Recipe<?> resolveMarketEntry(ResourceLocation recipeId) {
        String path = recipeId.getPath();
        if (!path.startsWith("market/")) return null;
        try {
            return resolveMarketEntry(UUID.fromString(path.substring("market/".length())));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Create a MarketRecipeWrapper from an IMarketEntry via reflection.
     * Also used by GenericCraftPacket when resolving a plan preview.
     */
    @Nullable
    public static Recipe<?> wrapEntry(Object entry) {
        if (entry == null) return null;
        try {
            Method getOutput = Reflect.findMethod(entry.getClass(),
                    "getOutputItem", new Class<?>[0]);
            Method getCost = Reflect.findMethod(entry.getClass(),
                    "getCostItem", new Class<?>[0]);
            Method getEntryId = Reflect.findMethod(entry.getClass(),
                    "getEntryId", new Class<?>[0]);
            if (getOutput == null || getCost == null || getEntryId == null) return null;

            ItemStack output = (ItemStack) getOutput.invoke(entry);
            ItemStack cost = (ItemStack) getCost.invoke(entry);
            UUID entryId = (UUID) getEntryId.invoke(entry);
            if (output.isEmpty() || cost.isEmpty()) return null;
            return new MarketRecipeWrapper(entryId, output, cost);
        } catch (Exception exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-Market] Failed to wrap entry", exception);
            return null;
        }
    }

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                  @Nullable ResourceLocation dim,
                                                  @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        if (!(recipe instanceof MarketRecipeWrapper marketRecipe)) return warnings;

        ItemStack cost = marketRecipe.costItem();
        ItemStack output = marketRecipe.getResultItem(player.serverLevel().registryAccess());
        if (!cost.isEmpty() && !output.isEmpty()) {
            warnings.add(Component.translatable("rsi.market.info.trade",
                    cost.getCount(), cost.getHoverName(),
                    output.getCount(), output.getHoverName()));
        }
        return warnings;
    }
}
