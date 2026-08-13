package com.huanghuang.rsintegration.mods.apprenticecodex;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ApprenticeCodexEssenceSmokerBatchDelegate extends AbstractBatchDelegate {
    private static final String BE_CLASS =
            "jp.aquafactory.apprenticecodex.block.essencesmoker.EssenceSmokerBlockEntity";

    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private BlockPos pos;
    private Recipe<?> recipe;
    private ItemStack expected = ItemStack.EMPTY;
    private final List<ItemStack> results = new ArrayList<>();
    private boolean started;

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        Recipe<?> found = resolved == null ? null : resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (resolved == null || found == null
                || !found.getClass().getName().equals(ApprenticeCodexRSModule.ESSENCE_RECIPE)
                || !isMachine(resolved, pos)) return false;
        this.level = resolved;
        this.dimension = resolved.dimension();
        this.pos = pos.immutable();
        this.recipe = found;
        this.expected = found.getResultItem(resolved.registryAccess()).copy();
        this.results.clear();
        this.started = false;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        markCraftStarted();
        return !expected.isEmpty();
    }

    @Override public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : CraftPacketUtils.extractIngredientSpecs(recipe);
    }
    @Override public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.delegateResult();
    }

    @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 2) return false;
        ExtractionLedger privateLedger = new ExtractionLedger();
        this.ledger = privateLedger;
        this.usingSharedLedger = false;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        List<ItemStack> materials = new ArrayList<>(2);
        for (IngredientSpec spec : specs) {
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, dimension, pos,
                    spec.ingredient(), spec.count(), privateLedger);
            if (stack.isEmpty()) { privateLedger.close(); return false; }
            materials.add(stack.copy());
        }
        if (!privateLedger.commit(network, player)) return false;
        if (start(materials)) return true;
        privateLedger.refundCommitted(network, player);
        return false;
    }

    @Override public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                                    @Nonnull List<ItemStack> materials,
                                                    @Nonnull ExtractionLedger sharedLedger) {
        this.ledger = sharedLedger;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        return start(materials);
    }

    private boolean start(List<ItemStack> materials) {
        List<IngredientSpec> specs = getRequiredMaterials();
        BlockEntity be = level.getBlockEntity(pos);
        if (specs == null || specs.size() != 2 || materials.size() != 2 || !isIdle(be)) return false;
        for (int i = 0; i < 2; i++) {
            if (materials.get(i).isEmpty() || materials.get(i).getCount() < specs.get(i).count()
                    || !specs.get(i).ingredient().test(materials.get(i))) return false;
        }
        ItemStack catalyst = materials.get(0).copyWithCount(1);
        ItemStack material = materials.get(1).copyWithCount(1);
        if (!invokeBoolean(be, "setCatalyst", new Class<?>[]{ItemStack.class}, catalyst)) return false;
        if (!invokeBoolean(be, "addMaterial", new Class<?>[]{ItemStack.class}, material)) {
            invoke(be, "popCatalyst", new Class<?>[0]);
            return false;
        }
        if (!invokeBoolean(be, "ignite", new Class<?>[]{long.class}, level.getGameTime())) {
            invoke(be, "popLastMaterial", new Class<?>[0]);
            invoke(be, "popCatalyst", new Class<?>[0]);
            return false;
        }
        started = true;
        return true;
    }

    private static boolean isIdle(@Nullable BlockEntity be) {
        return be != null && be.getClass().getName().equals(BE_CLASS)
                && !invokeBoolean(be, "hasCatalyst", new Class<?>[0])
                && !invokeBoolean(be, "hasMaterials", new Class<?>[0])
                && !invokeBoolean(be, "isProcessing", new Class<?>[0])
                && !invokeBoolean(be, "isCompleted", new Class<?>[0]);
    }

    @Override protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        return started && invokeBoolean(be, "isCompleted", new Class<?>[0]);
    }

    @Override public ItemStack collectResult(@Nonnull ServerPlayer player) {
        List<ItemStack> all = collectAllResults(player);
        return all.isEmpty() ? ItemStack.EMPTY : all.get(0);
    }

    @Override public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !be.getClass().getName().equals(BE_CLASS)
                || !invokeBoolean(be, "isCompleted", new Class<?>[0])) return List.of();
        Object raw = invoke(be, "collectCompletedItems", new Class<?>[0]);
        if (!(raw instanceof List<?> stacks)) return List.of();
        results.clear();
        for (Object value : stacks) if (value instanceof ItemStack stack && !stack.isEmpty()) results.add(stack.copy());
        started = false;
        if (ledger != null && !usingSharedLedger && ledger.isCommitted()) ledger.settleAllCommitted();
        return results.stream().map(ItemStack::copy).toList();
    }

    @Override public boolean collectsPhysicalSecondaryOutputs() { return true; }
    @Override public ExpectedProduction getExpectedProduction() {
        return expected.isEmpty() ? null : new ExpectedProduction(expected, expected.getCount());
    }
    @Override protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        resetContents(be);
        results.clear(); started = false; resetState();
    }
    @Override public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        results.clear(); started = false; expected = ItemStack.EMPTY; resetState();
    }
    @Override public BlockPos getMachinePos() { return pos; }

    private static boolean isMachine(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        BlockEntity be = level.getBlockEntity(pos);
        return id != null && "apprenticecodex:essence_smoker".equals(id.toString())
                && be != null && be.getClass().getName().equals(BE_CLASS);
    }

    private static boolean invokeBoolean(Object target, String name, Class<?>[] types, Object... args) {
        Object value = invoke(target, name, types, args);
        return value instanceof Boolean bool && bool;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = target.getClass().getMethod(name, types);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | LinkageError e) {
            RSIntegrationMod.LOGGER.warn("[RSI-ApprenticeCodex] Cannot invoke {} on {}",
                    name, target.getClass().getName(), e);
            return null;
        }
    }

    private static void resetContents(Object target) {
        try {
            Method method = target.getClass().getDeclaredMethod("resetContents");
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException | LinkageError e) {
            RSIntegrationMod.LOGGER.error("[RSI-ApprenticeCodex] Cannot reset cancelled Essence Smoker at {}",
                    target, e);
        }
    }
}
