package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.reflection.probes.FAReflection;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Batch delegate for Forbidden & Arcanus Hephaestus Forge rituals. */
public final class FaBatchDelegate extends AbstractBatchDelegate {

    // ── Shared class refs (delegated to FaRitualHelper) ──────────

    private static ResourceKey<?> getFARegistryKey() {
        return FaRitualHelper.getFARegistryKey();
    }

    private static Object getRitualById(ResourceLocation id, ServerLevel level) {
        return FaRitualHelper.getRitualById(id, level);
    }

    // ── Instance state ───────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Object forge;                // HephaestusForgeBlockEntity
    private Object ritual;               // Ritual
    private Object ritualManager;
    private Object essencesStorage;
    private List<Object> filledPedestals;
    private List<Object> emptyPedestals;
    private boolean ritualEverSeenActive; // guards against premature completion

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {

        // Reset all instance state to prevent pollution from a previous
        // validateAndInit() call that failed partway through.
        this.forge = null;
        this.ritual = null;
        this.ritualManager = null;
        this.essencesStorage = null;
        this.filledPedestals = null;
        this.emptyPedestals = null;
        this.ritualEverSeenActive = false;

        if (FAReflection.hephaestusForgeBEClass == null || FAReflection.ritualManagerClass == null) {
            player.sendSystemMessage(Component.translatable("rsi.batch.error.mod_missing", "Forbidden Arcanus"));
            return false;
        }

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        Object foundRitual = getRitualById(recipeId, level);
        if (foundRitual == null) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.ritual_not_found", recipeId.toString()));
            return false;
        }
        this.ritual = foundRitual;

        ChunkUtils.loadChunk(level, pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !FAReflection.hephaestusForgeBEClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.forge_not_found"));
            return false;
        }
        this.forge = be;

        // Check forge tier
        BlockState state = level.getBlockState(pos);
        int forgeTier = FaRitualHelper.getForgeTier(state, be);
        int requiredTier = FaRitualHelper.getRitualRequiredTier(ritual);
        if (forgeTier < requiredTier) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.tier_insufficient", requiredTier, forgeTier));
            return false;
        }

        // UpgradeTierResult: FA's checkConditions requires EXACT tier match
        try {
            Object result = FaRitualHelper.invoke(ritual, "result");
            if (result != null && FAReflection.upgradeTierResultClass.isInstance(result)) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] UpgradeTierResult ritual — output depends on main ingredient");
                int upgradeReqTier = FaRitualHelper.readUpgradeRequiredTier(result);
                if (upgradeReqTier >= 0 && forgeTier != upgradeReqTier) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.fa.error.tier_exact_required", upgradeReqTier, forgeTier));
                    return false;
                }
            }
        } catch (Exception e) { RSIntegrationMod.debug("[RSI-Batch-FA] Reflection probe failed", e); }

        // Resolve ritual manager — must come BEFORE enhancer check
        try {
            ritualManager = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getRitualManager", "getRitualManager").invoke(forge);
        } catch (Exception e) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.ritual_manager"));
            return false;
        }
        if (ritualManager == null) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.ritual_manager"));
            return false;
        }

        // Check required enhancers
        try {
            Object requirements = FaRitualHelper.invoke(ritual, "requirements");
            if (requirements != null) {
                List<?> requiredEnhancers = FaRitualHelper.invokeList(requirements, "enhancers");
                if (requiredEnhancers != null && !requiredEnhancers.isEmpty()) {
                    java.lang.reflect.Field accessorField = Reflect.findField(
                            FAReflection.ritualManagerClass, "enhancerAccessor").orElse(null);
                    List<?> installedEnhancers = null;
                    if (accessorField != null) {
                        accessorField.setAccessible(true);
                        Object ea = accessorField.get(ritualManager);
                        if (ea != null) {
                            installedEnhancers = (List<?>) Reflect.getMethodOrThrow(FAReflection.enhancerAccessorClass, "getEnhancers", "getEnhancers").invoke(ea);
                        }
                    }
                    for (Object holder : requiredEnhancers) {
                        Object reqDef = Reflect.extractHolderValue(holder);
                        if (reqDef == null) continue;
                        boolean found = false;
                        if (installedEnhancers != null) {
                            for (Object e : installedEnhancers) {
                                if (e == reqDef || e.equals(reqDef)) { found = true; break; }
                            }
                        }
                        if (!found) {
                            player.sendSystemMessage(Component.translatable(
                                    "rsi.fa.error.missing_enhancer", reqDef.toString()));
                            return false;
                        }
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Enhancer check failed", e);
            player.sendSystemMessage(Component.translatable(
                    "rsi.fa.error.enhancer_check_failed"));
            return false;
        }

        // Validate idle
        try {
            Boolean active = (Boolean) Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "isRitualActive", "isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) {
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] Cannot check isRitualActive — assuming forge is busy", e);
            player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
            return false;
        }

        // Check forge main slot is empty
        try {
            int mainSlot = FaRitualHelper.getMainSlot();
            ItemStack mainStack = FaRitualHelper.getForgeSlot(forge, mainSlot);
            if (!mainStack.isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] Cannot read forge main slot — assuming busy", e);
            player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
            return false;
        }

        // Check pedestals are empty
        try {
            List<Object> foundPedestals = findPedestals(level);
            if (foundPedestals != null) {
                for (Object ped : foundPedestals) {
                    ItemStack ps = (ItemStack) Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "getStack", "getStack").invoke(ped);
                    if (!ps.isEmpty()) {
                        player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] Cannot scan pedestals — assuming busy", e);
            player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
            return false;
        }

        this.filledPedestals = new ArrayList<>();
        this.emptyPedestals = new ArrayList<>();

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] validateAndInit OK: ritual={}", recipeId);
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);

        if (myPos != null && player.serverLevel().isLoaded(myPos)) {
            BlockEntity current = player.serverLevel().getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                if (ledger != null && ledger.isCommitted()) {
                    ledger.refundCommitted(network, player);
                }
                return false;
            }
        }

        if (!checkEssences()) return false;

        // Re-validate idle
        try {
            Boolean active = (Boolean) Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "isRitualActive", "isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] tryStartSingleCraft: altar busy, aborting");
                return false;
            }
        } catch (Exception e) { RSIntegrationMod.debug("[RSI-Batch-FA] Reflection probe failed", e); }

        this.filledPedestals = new ArrayList<>();

        List<Object> foundPedestals;
        try {
            foundPedestals = findPedestals(player.serverLevel());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartSingleCraft: pedestals not found", e);
            return false;
        }
        if (foundPedestals == null) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] tryStartSingleCraft: findPedestals returned null");
            return false;
        }

        List<Object> availablePedestals = new ArrayList<>();
        for (Object ped : foundPedestals) {
            try {
                ItemStack stack = (ItemStack) Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "getStack", "getStack").invoke(ped);
                if (stack.isEmpty()) {
                    availablePedestals.add(ped);
                }
            } catch (Exception e) { RSIntegrationMod.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        }

        // Collect recipe requirements
        List<?> inputs = FaRitualHelper.invokeList(ritual, "inputs");
        Ingredient mainIng = (Ingredient) FaRitualHelper.invoke(ritual, "mainIngredient");
        int inputEntryCount = inputs != null ? inputs.size() : 0;

        int totalSlotsNeeded = 0;
        int[] inputAmounts = new int[inputEntryCount];
        for (int i = 0; i < inputEntryCount; i++) {
            int amt = (int) FaRitualHelper.invoke(inputs.get(i), "amount");
            inputAmounts[i] = amt;
            totalSlotsNeeded += amt;
        }
        if (totalSlotsNeeded > availablePedestals.size()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] tryStartSingleCraft: insufficient pedestals (need {}, have {})",
                    totalSlotsNeeded, availablePedestals.size());
            return false;
        }

        // Phase 1: reserve all items via ledger
        ItemStack mainTemplate = ItemStack.EMPTY;
        if (mainIng != null && !mainIng.isEmpty()) {
            mainTemplate = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, mainIng, 1, ledger);
            if (mainTemplate.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] tryStartSingleCraft: main ingredient extraction failed");
                return false;
            }
        }

        List<ItemStack> inputTemplates = new ArrayList<>();
        for (int i = 0; i < inputEntryCount; i++) {
            Object ri = inputs.get(i);
            Ingredient ing = (Ingredient) FaRitualHelper.invoke(ri, "ingredient");
            int amt = inputAmounts[i];
            if (ing == null) continue;

            ItemStack t = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, amt, ledger);
            if (t.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] tryStartSingleCraft: input[{}] extraction failed", i);
                return false;
            }
            inputTemplates.add(t);
        }

        // Phase 2: place items BEFORE committing ledger
        try {
            if (!mainTemplate.isEmpty()) {
                int mainSlot = FaRitualHelper.getMainSlot();
                FaRitualHelper.setForgeSlot(forge, mainSlot, mainTemplate);
            }

            int pedIdx = 0;
            for (int i = 0; i < inputEntryCount && i < inputTemplates.size(); i++) {
                ItemStack template = inputTemplates.get(i);
                int amt = inputAmounts[i];
                for (int p = 0; p < amt && p < template.getCount(); p++) {
                    ItemStack single = template.copy();
                    single.setCount(1);
                    Object ped = availablePedestals.get(pedIdx++);
                    Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "setStackAndSync", "setStackAndSync", ItemStack.class)
                            .invoke(ped, single);
                    filledPedestals.add(ped);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartSingleCraft: placement failed:", e);
            rollbackAll();
            ledger.rollback(player);
            return false;
        }

        this.emptyPedestals = availablePedestals;

        // Populate cachedIngredients
        try {
            Object curEssences = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getEssences", "getEssences").invoke(forge);
            Method updateIngredient = Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "updateIngredient", "updateIngredient",
                    FAReflection.pedestalBEClass, ItemStack.class, FAReflection.essencesDefinitionClass);
            for (Object ped : foundPedestals) {
                if (!filledPedestals.contains(ped)) {
                    updateIngredient.invoke(ritualManager, ped, ItemStack.EMPTY, curEssences);
                }
            }
            for (Object ped : filledPedestals) {
                ItemStack stack = (ItemStack) Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "getStack", "getStack").invoke(ped);
                updateIngredient.invoke(ritualManager, ped, stack, curEssences);
            }
            Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "updateValidRitual", "updateValidRitual", FAReflection.essencesDefinitionClass)
                    .invoke(ritualManager, curEssences);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] cachedIngredients/updateValidRitual failed: {}");
        }

        // Phase 3: require a RitualStarterItem
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] (single) Searching for RitualStarterItem...");
        final ItemStack starterStack = findRitualStarterItem(player, network);
        if (starterStack.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] tryStartSingleCraft: no usable RitualStarterItem in inventory or RS");
            player.sendSystemMessage(Component.translatable("rsi.fa.error.missing_starter_item"));
            rollbackAll();
            ledger.rollback(player);
            return false;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] (single) Found starter '{}' from {}",
                starterStack.getHoverName().getString(), starterFromRS != null ? "RS" : "inventory");

        // Phase 4: start the ritual BEFORE committing ledger
        try {
            Object essenceMgr = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getEssenceManager", "getEssenceManager").invoke(forge);
            this.essencesStorage = Reflect.getMethodOrThrow(FAReflection.essenceManagerClass, "getStorage", "getStorage").invoke(essenceMgr);
            DelegateBooleanConsumer callback = new DelegateBooleanConsumer(this, starterStack, player);
            Object proxy = Proxy.newProxyInstance(
                    FAReflection.booleanConsumerClass.getClassLoader(),
                    new Class<?>[]{FAReflection.booleanConsumerClass},
                    callback);

            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] (single) Invoking tryStartRitual with starter='{}'",
                    starterStack.getHoverName().getString());
            Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "tryStartRitual", "tryStartRitual", FAReflection.essencesStorageClass, FAReflection.booleanConsumerClass)
                    .invoke(ritualManager, essencesStorage, proxy);
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] (single) tryStartRitual returned: wasCalled={} accepted={}",
                    callback.wasCalled, callback.accepted);

            if (callback.wasCalled && !callback.accepted) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] tryStartSingleCraft: ritual rejected by forge");
                rollbackAll();
                ledger.rollback(player);
                returnStarterToSource(starterStack);
                return false;
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable root = e.getCause() != null ? e.getCause() : e;
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartRitual failed — forge rejected", root);
            rollbackAll();
            ledger.rollback(player);
            returnStarterToSource(starterStack);
            return false;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartSingleCraft: start ritual failed:", e);
            rollbackAll();
            ledger.rollback(player);
            returnStarterToSource(starterStack);
            return false;
        }

        // Phase 5: commit
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Ledger commit failed after ritual start");
            rollbackAll();
            ledger.rollback(player);
            returnStarterToSource(starterStack);
            return false;
        }

        consumeRitualStarterUse(starterStack, player);
        return true;
    }

    // ── Chain support: pre-reserved materials ────────────────────

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (ritual == null) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        Ingredient mainIng = (Ingredient) FaRitualHelper.invoke(ritual, "mainIngredient");
        if (mainIng != null && !mainIng.isEmpty()) {
            specs.add(new IngredientSpec(mainIng, 1));
        }
        List<?> inputs = FaRitualHelper.invokeList(ritual, "inputs");
        if (inputs != null) {
            for (Object ri : inputs) {
                Ingredient ing = (Ingredient) FaRitualHelper.invoke(ri, "ingredient");
                int amt = (int) FaRitualHelper.invoke(ri, "amount");
                if (ing != null && !ing.isEmpty()) {
                    specs.add(new IngredientSpec(ing, amt));
                }
            }
        }
        return specs.isEmpty() ? null : specs;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        this.usingSharedLedger = true;

        if (myPos != null && player.serverLevel().isLoaded(myPos)) {
            BlockEntity current = player.serverLevel().getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                if (ledger != null && ledger.isCommitted()) {
                    ledger.refundCommitted(network, player);
                }
                return false;
            }
        }

        if (!checkEssences()) return false;

        // Re-validate idle
        try {
            Boolean active = (Boolean) Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "isRitualActive", "isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) {
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
                return false;
            }
        } catch (Exception e) { RSIntegrationMod.debug("[RSI-Batch-FA] Reflection probe failed", e); }

        this.filledPedestals = new ArrayList<>();

        List<Object> foundPedestals;
        try {
            foundPedestals = findPedestals(player.serverLevel());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartWithMaterials: pedestals not found", e);
            player.sendSystemMessage(Component.translatable("rsi.fa.error.pedestal_not_found"));
            return false;
        }
        if (foundPedestals == null) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] tryStartWithMaterials: findPedestals returned null");
            player.sendSystemMessage(Component.translatable("rsi.fa.error.pedestal_not_found"));
            return false;
        }

        List<Object> availablePedestals = new ArrayList<>();
        for (Object ped : foundPedestals) {
            try {
                ItemStack stack = (ItemStack) Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "getStack", "getStack").invoke(ped);
                if (stack.isEmpty()) availablePedestals.add(ped);
            } catch (Exception e) { RSIntegrationMod.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        }

        boolean hasMain = ((Ingredient) FaRitualHelper.invoke(ritual, "mainIngredient")) != null;
        List<?> inputs = FaRitualHelper.invokeList(ritual, "inputs");
        int inputEntryCount = inputs != null ? inputs.size() : 0;

        int totalSlotsNeeded = 0;
        int[] inputAmounts = new int[inputEntryCount];
        for (int i = 0; i < inputEntryCount; i++) {
            int amt = (int) FaRitualHelper.invoke(inputs.get(i), "amount");
            inputAmounts[i] = amt;
            totalSlotsNeeded += amt;
        }
        if (totalSlotsNeeded > availablePedestals.size()) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.fa.error.pedestals_insufficient", totalSlotsNeeded, availablePedestals.size()));
            return false;
        }

        try {
            int matIdx = 0;
            if (hasMain && matIdx < materials.size()) {
                ItemStack mainStack = materials.get(matIdx++);
                if (!mainStack.isEmpty()) {
                    int mainSlot = FaRitualHelper.getMainSlot();
                    FaRitualHelper.setForgeSlot(forge, mainSlot, mainStack);
                }
            }

            int pedIdx = 0;
            for (int i = 0; i < inputEntryCount && matIdx < materials.size(); i++) {
                ItemStack compacted = materials.get(matIdx++);
                if (compacted.isEmpty()) continue;
                int amt = inputAmounts[i];
                for (int p = 0; p < amt && p < compacted.getCount(); p++) {
                    ItemStack single = compacted.copy();
                    single.setCount(1);
                    Object ped = availablePedestals.get(pedIdx++);
                    Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "setStackAndSync", "setStackAndSync", ItemStack.class)
                            .invoke(ped, single);
                    filledPedestals.add(ped);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartWithMaterials: placement failed:", e);
            rollbackAll();
            player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed"));
            return false;
        }

        this.emptyPedestals = availablePedestals;

        // Populate cachedIngredients
        try {
            Object curEssences = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getEssences", "getEssences").invoke(forge);
            Method updateIngredient = Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "updateIngredient", "updateIngredient",
                    FAReflection.pedestalBEClass, ItemStack.class, FAReflection.essencesDefinitionClass);
            for (Object ped : foundPedestals) {
                if (!filledPedestals.contains(ped)) {
                    updateIngredient.invoke(ritualManager, ped, ItemStack.EMPTY, curEssences);
                }
            }
            for (Object ped : filledPedestals) {
                ItemStack stack = (ItemStack) Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "getStack", "getStack").invoke(ped);
                updateIngredient.invoke(ritualManager, ped, stack, curEssences);
            }
            Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "updateValidRitual", "updateValidRitual", FAReflection.essencesDefinitionClass)
                    .invoke(ritualManager, curEssences);

            // Diagnostic: verify updateValidRitual found our ritual
            try {
                Object vR = FaRitualHelper.getValidRitualSafe(ritualManager);
                if (vR == null) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] updateValidRitual: validRitual is NULL — no ritual matched cached ingredients!");
                } else if (vR != this.ritual && !vR.equals(this.ritual)) {
                    Object vRId = FaRitualHelper.invoke(vR, "resourceLocation");
                    Object expectedId = FaRitualHelper.invoke(ritual, "resourceLocation");
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] updateValidRitual: validRitual={} differs from expected={}", vRId, expectedId);
                } else {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] updateValidRitual: matched expected ritual");
                }
            } catch (Exception vEx) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] validRitual check failed (harmless)", vEx);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] cachedIngredients/updateValidRitual failed: {}");
        }

        // Require a RitualStarterItem
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Searching for RitualStarterItem...");
        final ItemStack starterStack = findRitualStarterItem(player, network);
        if (starterStack.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] tryStartWithMaterials: no usable RitualStarterItem in inventory or RS");
            player.sendSystemMessage(Component.translatable("rsi.fa.error.missing_starter_item"));
            rollbackAll();
            return false;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Found starter '{}' from {}",
                starterStack.getHoverName().getString(), starterFromRS != null ? "RS" : "inventory");

        // Start the ritual
        try {
            Object essenceMgr = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getEssenceManager", "getEssenceManager").invoke(forge);
            this.essencesStorage = Reflect.getMethodOrThrow(FAReflection.essenceManagerClass, "getStorage", "getStorage").invoke(essenceMgr);
            DelegateBooleanConsumer callback = new DelegateBooleanConsumer(this, starterStack, player);
            Object proxy = Proxy.newProxyInstance(
                    FAReflection.booleanConsumerClass.getClassLoader(),
                    new Class<?>[]{FAReflection.booleanConsumerClass},
                    callback);

            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Invoking tryStartRitual (withMaterials) with starter='{}'",
                    starterStack.getHoverName().getString());
            Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "tryStartRitual", "tryStartRitual", FAReflection.essencesStorageClass, FAReflection.booleanConsumerClass)
                    .invoke(ritualManager, essencesStorage, proxy);
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] tryStartRitual (withMaterials) returned: wasCalled={} accepted={}",
                    callback.wasCalled, callback.accepted);

            if (callback.wasCalled && !callback.accepted) {
                // Diagnostic dump
                try {
                    Object vR = FaRitualHelper.getValidRitualSafe(ritualManager);
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   validRitual={}", vR);
                    if (vR != null) {
                        Object vRId = FaRitualHelper.invoke(vR, "resourceLocation");
                        RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   validRitual.id={}", vRId);
                        Object matched = vR == this.ritual || vR.equals(this.ritual);
                        RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   matches expected={}", matched);
                    }
                    int mainSlot = FaRitualHelper.getMainSlot();
                    ItemStack forgeStack = FaRitualHelper.getForgeSlot(forge, mainSlot);
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   forge main slot={}: {}", mainSlot, forgeStack);
                    Object curEss = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getEssences", "getEssences").invoke(forge);
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   essences: a={} s={} b={} e={}",
                            Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "aureal", "aureal").invoke(curEss),
                            Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "souls", "souls").invoke(curEss),
                            Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "blood", "blood").invoke(curEss),
                            Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "experience", "experience").invoke(curEss));
                    Object req = FaRitualHelper.invoke(ritual, "essences");
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   required: a={} s={} b={} e={}",
                            FaRitualHelper.invoke(req, "aureal"), FaRitualHelper.invoke(req, "souls"),
                            FaRitualHelper.invoke(req, "blood"), FaRitualHelper.invoke(req, "experience"));
                    for (int i = 0; i < materials.size(); i++) {
                        RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   material[{}]={} x{}",
                                i, materials.get(i), materials.get(i).getCount());
                    }
                } catch (Exception diagEx) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   diagnostic dump failed", diagEx);
                }
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_rejected"));
                clearFilledPedestals();
                try {
                    int mainSlot = FaRitualHelper.getMainSlot();
                    FaRitualHelper.setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
                } catch (Exception e) { RSIntegrationMod.debug("[RSI-Batch-FA] Reflection probe failed", e); }
                returnStarterToSource(starterStack);
                return false;
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable root = e.getCause() != null ? e.getCause() : e;
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartRitual failed (withMaterials) — forge rejected", root);
            rollbackAll();
            player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed"));
            returnStarterToSource(starterStack);
            return false;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartWithMaterials: start ritual failed:", e);
            rollbackAll();
            player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed"));
            returnStarterToSource(starterStack);
            return false;
        }

        consumeRitualStarterUse(starterStack, player);
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        try {
            if (ritualManager != null) {
                Boolean active = (Boolean) Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "isRitualActive", "isRitualActive").invoke(ritualManager);
                if (Boolean.TRUE.equals(active)) {
                    ritualEverSeenActive = true;
                    return false;
                }
            }
        } catch (Exception e) { RSIntegrationMod.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        return ritualEverSeenActive;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        try {
            Object result = FaRitualHelper.invoke(ritual, "result");
            if (result != null && FAReflection.createItemResultClass.isInstance(result)) {
                ItemStack itemResult = (ItemStack) Reflect.getMethodOrThrow(FAReflection.createItemResultClass, "getResult", "getResult").invoke(result);
                if (itemResult != null && !itemResult.isEmpty()) {
                    try {
                        int mainSlot = FaRitualHelper.getMainSlot();
                        FaRitualHelper.setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Clear slot failed", e); }
                    return itemResult.copy();
                }
            }
            if (result != null && FAReflection.upgradeTierResultClass.isInstance(result)) {
                try {
                    int mainSlot = FaRitualHelper.getMainSlot();
                    ItemStack upgraded = FaRitualHelper.getForgeSlot(forge, mainSlot);
                    if (!upgraded.isEmpty()) {
                        FaRitualHelper.setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
                        return upgraded.copy();
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] UpgradeTierResult collect failed", e);
                }
                return ItemStack.EMPTY;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] GetResult failed", e);
        }
        try {
            int mainSlot = FaRitualHelper.getMainSlot();
            ItemStack result = FaRitualHelper.getForgeSlot(forge, mainSlot);
            if (!result.isEmpty()) {
                FaRitualHelper.setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
            }
            return result;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        if (starterFromRS != null && network != null) {
            ItemStack leftover = network.insertItem(starterFromRS, starterFromRS.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!leftover.isEmpty()) {
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
            starterFromRS = null;
        }
        rollbackAll();
        resetState();
        starterFromRS = null;
        ritualEverSeenActive = false;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        clearFilledPedestals();
        try {
            int mainSlot = FaRitualHelper.getMainSlot();
            FaRitualHelper.setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
        } catch (Exception e) { RSIntegrationMod.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        resetState();
        starterFromRS = null;
        ritualEverSeenActive = false;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    // ── Rollback / cleanup ───────────────────────────────────────

    void rollbackAll() {
        if (usingSharedLedger) {
            clearFilledPedestalsNoRefund();
            clearMainSlotNoRefund();
            return;
        }
        clearFilledPedestals();
        try {
            int mainSlot = FaRitualHelper.getMainSlot();
            ItemStack stack = FaRitualHelper.getForgeSlot(forge, mainSlot);
            if (!stack.isEmpty()) {
                if (network != null) {
                    ItemStack leftover = network.insertItem(stack, stack.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    if (!leftover.isEmpty()) {
                        ItemHandlerHelper.giveItemToPlayer(player, leftover);
                    }
                } else if (player != null) {
                    ItemHandlerHelper.giveItemToPlayer(player, stack);
                }
            }
            FaRitualHelper.setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
        } catch (Exception e) { RSIntegrationMod.debug("[RSI-Batch-FA] Reflection probe failed", e); }
    }

    private void clearFilledPedestals() {
        if (filledPedestals == null) return;
        for (Object ped : filledPedestals) {
            try {
                ItemStack stack = (ItemStack) Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "getStack", "getStack").invoke(ped);
                if (stack != null && !stack.isEmpty()) {
                    if (network != null) {
                        ItemStack leftover = network.insertItem(stack, stack.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                        if (!leftover.isEmpty()) {
                            ItemHandlerHelper.giveItemToPlayer(player, leftover);
                        }
                    } else if (player != null) {
                        ItemHandlerHelper.giveItemToPlayer(player, stack);
                    }
                }
                Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "clearStack", "clearStack", Level.class, boolean.class)
                        .invoke(ped, null, false);
            } catch (Exception e) { RSIntegrationMod.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        }
    }

    private void clearFilledPedestalsNoRefund() {
        if (filledPedestals == null) return;
        for (Object ped : filledPedestals) {
            try {
                Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "clearStack", "clearStack", Level.class, boolean.class)
                        .invoke(ped, null, false);
            } catch (Exception e) { RSIntegrationMod.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        }
    }

    private void clearMainSlotNoRefund() {
        try {
            int mainSlot = FaRitualHelper.getMainSlot();
            FaRitualHelper.setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] clearMainSlotNoRefund failed", e); }
    }

    // ── RitualStarterItem helpers ─────────────────────────────────

    @javax.annotation.Nullable
    private transient ItemStack starterFromRS;

    private void returnStarterToSource(ItemStack stack) {
        if (stack.isEmpty()) return;
        INetwork srcNetwork = starterFromRS != null ? network : null;
        FaRitualHelper.returnStarterToSource(stack, player, srcNetwork);
        starterFromRS = null;
    }

    private ItemStack findRitualStarterItem(ServerPlayer player, INetwork network) {
        FaRitualHelper.StarterResult result = FaRitualHelper.findRitualStarterItem(player, network);
        starterFromRS = result.sourceNetwork() != null ? result.stack() : null;
        return result.stack();
    }

    private boolean canStartRitual(ItemStack stack) {
        return FaRitualHelper.canStartRitual(stack);
    }

    private void consumeRitualStarterUse(ItemStack starterStack, ServerPlayer player) {
        INetwork srcNetwork = starterFromRS != null ? network : null;
        FaRitualHelper.consumeRitualStarterUse(starterStack, player, srcNetwork);
        starterFromRS = null;
    }

    // ── Essence validation (instance wrappers) ───────────────────

    private List<Object> collectEnhancerModifiers() {
        return FaRitualHelper.collectEnhancerModifiers(ritualManager, ritual);
    }

    private boolean checkEssences() {
        return FaRitualHelper.checkEssences(player, ritual, forge, ritualManager);
    }

    // ── Pedestal finding (instance wrapper) ──────────────────────

    private List<Object> findPedestals(ServerLevel level) {
        return FaRitualHelper.findPedestals(myPos, level);
    }

    // ── Plan-time essence warnings (static, called from tryBuildPlan) ──

    /**
     * Build plan-time warnings for FA ritual essence requirements.
     * When a forge is bound, shows current vs. needed levels.
     * When no forge is bound, lists the required essence types.
     */
    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable net.minecraft.core.BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        if (!(recipe instanceof FaRitualWrapper wrapper)) return warnings;
        if (FAReflection.hephaestusForgeBEClass == null || FAReflection.ritualClass == null || FAReflection.essencesDefinitionClass == null)
            return warnings;

        Object ritual = wrapper.ritual();
        if (ritual == null) return warnings;

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] getPlanWarnings called for recipe={} dim={} pos={}",
                recipe.getId(), dim, pos);

        // When dim/pos point to a non-forge machine (e.g. smithing table
        // for ApplyModifier recipes), resolve the actual Hephaestus Forge
        // position from the player's bindings so tier/essence/enhancer
        // checks work correctly.
        if (dim != null && pos != null) {
            try {
                ServerLevel probeLevel = CraftPacketUtils.resolveLevel(player.server, dim, player);
                if (probeLevel != null && probeLevel.isLoaded(pos)) {
                    ChunkUtils.loadChunk(probeLevel, pos);
                    BlockEntity probeBe = probeLevel.getBlockEntity(pos);
                    if (probeBe == null || !FAReflection.hephaestusForgeBEClass.isInstance(probeBe)) {
                        RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] pos is not a forge, searching player bindings...");
                        // Scan player inventory + offhand + curios for a forge binding
                        List<ItemStack> allStacks = new ArrayList<>();
                        allStacks.addAll(player.getInventory().items);
                        allStacks.addAll(player.getInventory().offhand);
                        allStacks.addAll(player.getInventory().armor);
                        try {
                            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
                            if (opt.isPresent()) {
                                for (var handler : opt.get().getCurios().values()) {
                                    var stacks = handler.getStacks();
                                    for (int s = 0; s < stacks.getSlots(); s++) {
                                        allStacks.add(stacks.getStackInSlot(s));
                                    }
                                }
                            }
                        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] curios inventory probe failed", e); }
                        ResourceLocation foundDim = null;
                        BlockPos foundPos = null;
                        outer:
                        for (ItemStack stack : allStacks) {
                            if (stack.isEmpty()) continue;
                            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                                if (entry.blockKey() != null && entry.blockKey().contains("hephaestus_forge")) {
                                    foundDim = entry.dim();
                                    foundPos = entry.pos();
                                    break outer;
                                }
                            }
                        }
                        if (foundPos != null) {
                            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Resolved forge from bindings: dim={} pos={}",
                                    foundDim, foundPos);
                            dim = foundDim;
                            pos = foundPos;
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Forge pos resolution failed", e);
            }
        }

        Object requirements = FaRitualHelper.invoke(ritual, "requirements");

        // ── Essence check ──────────────────────────────────────────
        Object ritualEssences = FaRitualHelper.invoke(ritual, "essences");
        if (ritualEssences != null) {
            int reqAureal = FaRitualHelper.invokeInt(ritualEssences, "aureal");
            int reqSouls  = FaRitualHelper.invokeInt(ritualEssences, "souls");
            int reqBlood  = FaRitualHelper.invokeInt(ritualEssences, "blood");
            int reqExp    = FaRitualHelper.invokeInt(ritualEssences, "experience");

            int curAureal = -1, curSouls = -1, curBlood = -1, curExp = -1;

            if (dim != null && pos != null) {
                try {
                    ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                    if (level != null && level.isLoaded(pos)) {
                        // Force chunk to fully load so BlockEntity is available.
                        // Without this, getBlockEntity may return null even when
                        // isLoaded() returns true (chunk data present but BEs not
                        // yet instantiated).
                        ChunkUtils.loadChunk(level, pos);
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null && FAReflection.hephaestusForgeBEClass.isInstance(be)) {
                            Object ritualManager = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getRitualManager", "getRitualManager").invoke(be);
                            if (ritualManager != null) {
                                List<Object> enhancerModifiers = FaRitualHelper.collectEnhancerModifiers(ritualManager, ritual);
                                if (!enhancerModifiers.isEmpty()) {
                                    Object modified = Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "applyModifiers", "applyModifiers",
                                            List.class).invoke(ritualEssences, enhancerModifiers);
                                    reqAureal = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "aureal", "aureal").invoke(modified);
                                    reqSouls  = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "souls", "souls").invoke(modified);
                                    reqBlood  = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "blood", "blood").invoke(modified);
                                    reqExp    = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "experience", "experience").invoke(modified);
                                }
                            }

                            Object curEssences = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getEssences", "getEssences").invoke(be);
                            curAureal = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "aureal", "aureal").invoke(curEssences);
                            curSouls  = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "souls", "souls").invoke(curEssences);
                            curBlood  = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "blood", "blood").invoke(curEssences);
                            curExp    = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "experience", "experience").invoke(curEssences);
                        }
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Plan essence read failed: {}");
                }
            }

            if (curAureal >= 0) {
                if (reqAureal > 0 && curAureal < reqAureal)
                    warnings.add(Component.translatable("rsi.fa.warn.insufficient_aureal", reqAureal, curAureal).getString());
                if (reqSouls > 0 && curSouls < reqSouls)
                    warnings.add(Component.translatable("rsi.fa.warn.insufficient_souls", reqSouls, curSouls).getString());
                if (reqBlood > 0 && curBlood < reqBlood)
                    warnings.add(Component.translatable("rsi.fa.warn.insufficient_blood", reqBlood, curBlood).getString());
                if (reqExp > 0 && curExp < reqExp)
                    warnings.add(Component.translatable("rsi.fa.warn.insufficient_experience", reqExp, curExp).getString());
            } else if (reqAureal > 0 || reqSouls > 0 || reqBlood > 0 || reqExp > 0) {
                warnings.add(Component.translatable("rsi.fa.warn.essence_required",
                        reqAureal, reqSouls, reqBlood, reqExp).getString());
            }

            // Check essence input slots
            try {
                if (dim != null && pos != null) {
                    ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                    if (level != null && level.isLoaded(pos)) {
                        ChunkUtils.loadChunk(level, pos);
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null && FAReflection.hephaestusForgeBEClass.isInstance(be)) {
                            java.lang.reflect.Field slotMapField = Reflect.findField(
                                    FAReflection.hephaestusForgeBEClass, "SLOT_FROM_ESSENCE_TYPE_MAP").orElse(null);
                            if (slotMapField != null) {
                                Object slotMap = slotMapField.get(null);
                                if (slotMap instanceof java.util.Map<?, ?> map) {
                                    Object curEssences = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getEssences", "getEssences").invoke(be);
                                    int curA = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "aureal", "aureal").invoke(curEssences);
                                    int curS = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "souls", "souls").invoke(curEssences);
                                    int curB = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "blood", "blood").invoke(curEssences);
                                    int curE = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "experience", "experience").invoke(curEssences);

                                    for (var entry : map.entrySet()) {
                                        String typeName = entry.getKey().toString();
                                        int slot = ((Number) entry.getValue()).intValue();
                                        int required, current;
                                        if (typeName.contains("AUREAL")) {
                                            required = reqAureal; current = curA;
                                        } else if (typeName.contains("SOULS")) {
                                            required = reqSouls; current = curS;
                                        } else if (typeName.contains("BLOOD")) {
                                            required = reqBlood; current = curB;
                                        } else if (typeName.contains("EXPERIENCE")) {
                                            required = reqExp; current = curE;
                                        } else {
                                            continue;
                                        }
                                        if (required > 0 && current < required) {
                                            ItemStack slotStack = FaRitualHelper.getForgeSlot(be, slot);
                                            if (slotStack.isEmpty()) {
                                                warnings.add(Component.translatable(
                                                        "rsi.fa.warn.essence_slot_empty",
                                                        slot, typeName).getString());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Plan essence slot check failed: {}");
            }
        }

        // ── Forge tier check ───────────────────────────────────────
        if (requirements != null) {
            try {
                int requiredTier = (int) Reflect.getMethodOrThrow(requirements.getClass(), "tier", "tier").invoke(requirements);
                if (requiredTier > 1) {
                    if (dim != null && pos != null) {
                        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                        if (level != null && level.isLoaded(pos)) {
                            ChunkUtils.loadChunk(level, pos);
                            BlockState state = level.getBlockState(pos);
                            BlockEntity be = level.getBlockEntity(pos);
                            int forgeTier = FaRitualHelper.getForgeTier(state, be);
                            if (forgeTier < requiredTier) {
                                warnings.add(Component.translatable(
                                        "rsi.fa.warn.tier_insufficient",
                                        requiredTier, forgeTier).getString());
                            }
                        }
                    } else {
                        warnings.add(Component.translatable(
                                "rsi.fa.warn.tier_required", requiredTier).getString());
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Plan tier check failed: {}");
            }
        }

        // ── Enhancer check ─────────────────────────────────────────
        if (requirements != null) {
            try {
                List<?> requiredEnhancers = FaRitualHelper.invokeList(requirements, "enhancers");
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Plan enhancers: required={}",
                        requiredEnhancers != null ? requiredEnhancers.size() : "null");
                if (requiredEnhancers != null && !requiredEnhancers.isEmpty()) {
                    List<String> reqNames = new ArrayList<>();
                    for (Object holder : requiredEnhancers) {
                        Object reqDef = Reflect.extractHolderValue(holder);
                        RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Plan enhancer holder {} → def {}",
                                holder != null ? holder.getClass().getSimpleName() : "null",
                                reqDef != null ? reqDef.getClass().getSimpleName() : "null");
                        if (reqDef != null) {
                            reqNames.add(FaRitualHelper.enhancerDefName(reqDef));
                        }
                    }
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Plan enhancer names: {} (total required: {})",
                            reqNames, requiredEnhancers.size());
                    if (dim != null && pos != null) {
                        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                        if (level != null && level.isLoaded(pos)) {
                            ChunkUtils.loadChunk(level, pos);
                            BlockEntity be = level.getBlockEntity(pos);
                            if (be != null && FAReflection.hephaestusForgeBEClass.isInstance(be)) {
                                Object rm = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getRitualManager", "getRitualManager").invoke(be);
                                if (rm != null) {
                                    java.lang.reflect.Field af = Reflect.findField(
                                            FAReflection.ritualManagerClass, "enhancerAccessor").orElse(null);
                                    List<?> installedEnhancers = null;
                                    if (af != null) {
                                        af.setAccessible(true);
                                        Object ea = af.get(rm);
                                        if (ea != null) {
                                            installedEnhancers = (List<?>) Reflect.getMethodOrThrow(FAReflection.enhancerAccessorClass, "getEnhancers", "getEnhancers").invoke(ea);
                                        }
                                    }
                                    for (int i = 0; i < requiredEnhancers.size(); i++) {
                                        Object holder = requiredEnhancers.get(i);
                                        Object reqDef = Reflect.extractHolderValue(holder);
                                        if (reqDef == null) continue;
                                        boolean found = false;
                                        if (installedEnhancers != null) {
                                            for (Object e : installedEnhancers) {
                                                if (e == reqDef || e.equals(reqDef)) {
                                                    found = true; break;
                                                }
                                            }
                                        }
                                        if (!found) {
                                            warnings.add(Component.translatable(
                                                    "rsi.fa.warn.missing_enhancer",
                                                    FaRitualHelper.enhancerDefName(reqDef)).getString());
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        for (String name : reqNames) {
                            warnings.add(Component.translatable(
                                    "rsi.fa.warn.enhancer_required", name).getString());
                        }
                    }
                } else if (requiredEnhancers == null) {
                    warnings.add(Component.translatable(
                            "rsi.fa.warn.cant_check_enhancers").getString());
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Plan enhancer check failed: {}");
                warnings.add(Component.translatable(
                        "rsi.fa.warn.cant_check_enhancers").getString());
            }
        } else {
            warnings.add(Component.translatable(
                    "rsi.fa.warn.cant_check_enhancers").getString());
        }

        return warnings;
    }

    // ── BooleanConsumer proxy for ritual callback ─────────────────

    static final class DelegateBooleanConsumer implements InvocationHandler {
        private final FaBatchDelegate delegate;
        @javax.annotation.Nullable private final ItemStack starterStack;
        @javax.annotation.Nullable private final ServerPlayer starterPlayer;
        boolean accepted;
        boolean wasCalled;

        DelegateBooleanConsumer(FaBatchDelegate delegate) {
            this(delegate, null, null);
        }

        DelegateBooleanConsumer(FaBatchDelegate delegate,
                                @javax.annotation.Nullable ItemStack starterStack,
                                @javax.annotation.Nullable ServerPlayer starterPlayer) {
            this.delegate = delegate;
            this.starterStack = starterStack;
            this.starterPlayer = starterPlayer;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("accept") && args.length == 1 && args[0] instanceof Boolean success) {
                wasCalled = true;
                accepted = success;
                if (Boolean.TRUE.equals(success)) {
                    delegate.ritualEverSeenActive = true;
                }
                // On failure, caller handles rollbackAll + ledger.rollback uniformly.
                // Calling rollbackAll here would re-insert pedestal items to RS
                // before the ledger is rolled back, causing item duplication.
            }
            return null;
        }
    }
}
