package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.GemInstance;
import dev.shadowsoffire.apotheosis.adventure.socket.gem.cutting.GemCuttingMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Executes one dynamic Apotheosis gem rarity upgrade through the real menu. */
public final class ApotheosisGemCuttingBatchDelegate extends AbstractBatchDelegate {
    private static final String BLOCK_ID = "apotheosis:gem_cutting_table";

    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private BlockPos pos;
    private ApotheosisGemCuttingRecipe recipe;
    private GemCuttingMenu menu;
    private ItemStack expected = ItemStack.EMPTY;
    private ItemStack result = ItemStack.EMPTY;
    private boolean done;

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (resolved == null || !isTable(resolved, pos)) return false;
        ApotheosisGemCuttingRecipe found = ApotheosisGemCuttingCatalog.byId(recipeId);
        if (found == null) return false;
        this.level = resolved;
        this.dimension = resolved.dimension();
        this.pos = pos.immutable();
        this.recipe = found;
        this.expected = found.getResultItem(resolved.registryAccess());
        this.result = ItemStack.EMPTY;
        this.done = false;
        this.menu = null;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        markCraftStarted();
        return !expected.isEmpty();
    }

    @Override
    public boolean acceptsMachineWithoutBlockEntity(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        return isTable(level, pos);
    }

    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : new ApotheosisGemCuttingRecipeHandler().getIngredients(recipe);
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.delegateResult();
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 3) return false;
        ExtractionLedger privateLedger = new ExtractionLedger();
        this.ledger = privateLedger;
        this.usingSharedLedger = false;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        List<ItemStack> materials = new ArrayList<>(3);
        for (IngredientSpec spec : specs) {
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(
                    player, dimension, pos, spec.ingredient(), spec.count(), privateLedger);
            if (stack.isEmpty()) {
                privateLedger.close();
                return false;
            }
            materials.add(stack.copy());
        }
        if (!privateLedger.commit(network, player)) return false;
        if (start(player, materials)) return true;
        privateLedger.refundCommitted(network, player);
        return false;
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                         @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger sharedLedger) {
        this.ledger = sharedLedger;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        return start(player, materials);
    }

    private boolean start(ServerPlayer player, List<ItemStack> materials) {
        if (materials.size() != 3 || !isTable(level, pos)) return false;
        AbstractContainerMenu created = createMenu(player);
        if (!(created instanceof GemCuttingMenu cutting)) return false;
        this.menu = cutting;
        for (int i = 0; i < 4; i++) if (cutting.getSlot(i).hasItem()) return failStart();

        ItemStack gems = materials.get(0);
        ItemStack dust = materials.get(1);
        ItemStack rarityMaterial = materials.get(2);
        if (gems.getCount() < 2 || dust.getCount() < recipe.dustCost()
                || rarityMaterial.getCount() < recipe.material().getCount()) return failStart();

        cutting.getSlot(0).set(gems.copyWithCount(1));
        cutting.getSlot(1).set(dust.copyWithCount(recipe.dustCost()));
        cutting.getSlot(2).set(gems.copyWithCount(1));
        cutting.getSlot(3).set(rarityMaterial.copyWithCount(recipe.material().getCount()));

        if (!cutting.clickMenuButton(player, 0)) return failStart();
        ItemStack produced = cutting.getSlot(0).getItem().copy();
        if (!matchesTarget(produced, expected)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Apotheosis] Gem cutting output mismatch at {}", pos);
            return failStart();
        }
        result = cutting.getSlot(0).remove(produced.getCount());
        clearMenu();
        done = true;
        return true;
    }

    private boolean failStart() {
        clearMenu();
        return false;
    }

    private AbstractContainerMenu createMenu(ServerPlayer player) {
        MenuProvider provider = level.getBlockState(pos).getMenuProvider(level, pos);
        return provider == null ? null : provider.createMenu(-1, player.getInventory(), player);
    }

    @Override protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) { return done; }
    @Override protected CraftObservation observeMissingMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        return done ? doneObservation() : workingObservation();
    }
    @Override public @Nonnull ItemStack collectResult(@Nonnull ServerPlayer player) {
        ItemStack collected = result.copy();
        result = ItemStack.EMPTY;
        done = false;
        return collected;
    }
    @Override public ExpectedProduction getExpectedProduction() {
        return expected.isEmpty() ? null : new ExpectedProduction(expected, 1);
    }
    @Override protected void clearMachineState(BlockEntity be, ServerPlayer player) { clearMenu(); resetState(); }
    @Override public void onBatchFinished(@Nonnull ServerPlayer player) { clearMenu(); result = ItemStack.EMPTY; done = false; resetState(); }
    @Override public @Nonnull BlockPos getMachinePos() { return pos; }

    private void clearMenu() {
        if (menu == null) return;
        for (int i = 0; i < 4; i++) {
            if (menu.getSlot(i).hasItem()) menu.getSlot(i).remove(menu.getSlot(i).getItem().getCount());
        }
        menu = null;
    }

    private static boolean matchesTarget(ItemStack actual, ItemStack target) {
        GemInstance a = GemInstance.unsocketed(actual);
        GemInstance b = GemInstance.unsocketed(target);
        return a.isValidUnsocketed() && b.isValidUnsocketed()
                && a.gem().getId().equals(b.gem().getId())
                && a.rarity().get().ordinal() == b.rarity().get().ordinal();
    }

    private static boolean isTable(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        return id != null && BLOCK_ID.equals(id.toString());
    }
}
