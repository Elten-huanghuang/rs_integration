package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSIntegration;
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

public final class FaBatchDelegate implements IBatchDelegate {

    // ── Shared class refs ────────────────────────────────────────
    private static volatile boolean classesLoaded;
    private static volatile Class<?> hephaestusForgeBEClass;
    private static volatile Class<?> pedestalBEClass;
    private static volatile Class<?> ritualClass;
    private static volatile Class<?> essencesDefinitionClass;
    private static volatile Class<?> essencesStorageClass;
    private static volatile Class<?> ritualManagerClass;
    private static volatile Class<?> booleanConsumerClass;
    private static volatile Class<?> essenceManagerClass;
    private static volatile Class<?> createItemResultClass;
    private static volatile Class<?> upgradeTierResultClass;
    private static volatile Class<?> ritualStarterItemClass;
    private static volatile Class<?> enhancerAccessorClass;
    private static volatile Class<?> enhancerDefinitionClass;
    private static volatile Class<?> enhancerEffectClass;
    private static volatile Class<?> essenceModifierClass;

    private static void ensureClasses() {
        if (classesLoaded) return;
        try {
            hephaestusForgeBEClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.HephaestusForgeBlockEntity");
            pedestalBEClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.PedestalBlockEntity");
            ritualClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.Ritual");
            essencesDefinitionClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssencesDefinition");
            essencesStorageClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssencesStorage");
            ritualManagerClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.RitualManager");
            booleanConsumerClass = Class.forName(
                    "it.unimi.dsi.fastutil.booleans.BooleanConsumer");
            essenceManagerClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssenceManager");
            createItemResultClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.result.CreateItemResult");
            upgradeTierResultClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.result.UpgradeTierResult");
            ritualStarterItemClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.item.RitualStarterItem");
            enhancerAccessorClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.item.enhancer.EnhancerAccessor");
            enhancerDefinitionClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.item.enhancer.EnhancerDefinition");
            enhancerEffectClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.item.enhancer.EnhancerEffect");
            essenceModifierClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssenceModifier");
            classesLoaded = true;
        } catch (ClassNotFoundException e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Failed to load FA classes", e);
        }
    }

    @Nullable
    private static ResourceKey<?> getFARegistryKey() {
        try {
            Class<?> faRegistries = Class.forName(
                    "com.stal111.forbidden_arcanus.core.registry.FARegistries");
            java.lang.reflect.Field field = faRegistries.getField("RITUAL");
            field.setAccessible(true);
            return (ResourceKey<?>) field.get(null);
        } catch (Exception ex) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Failed to get FA ritual registry key", ex);
        }
        return null;
    }

    @Nullable
    private static Object getRitualById(ResourceLocation id, ServerLevel level) {
        ensureClasses();
        ResourceKey<?> key = getFARegistryKey();
        if (key == null) return null;
        try {
            net.minecraft.core.Registry<?> registry = level.registryAccess().registryOrThrow(
                    (ResourceKey<? extends net.minecraft.core.Registry<?>>) (Object) key);
            return registry.get(id);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Instance state ───────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Object forge;                // HephaestusForgeBlockEntity
    private Object ritual;               // Ritual
    private Object ritualManager;
    private Object essencesStorage;
    private ExtractionLedger ledger;
    private ExtractionLedger sharedLedger;
    private INetwork network;
    private List<Object> filledPedestals;
    private List<Object> emptyPedestals; // saved pedestal list for cleanup
    private boolean usingSharedLedger;
    private boolean ritualEverSeenActive; // guards against premature completion

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ensureClasses();
        if (hephaestusForgeBEClass == null || ritualManagerClass == null) {
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

        if (!level.isLoaded(pos)) level.getChunk(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !hephaestusForgeBEClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.forge_not_found"));
            return false;
        }
        this.forge = be;

        // Check forge tier
        BlockState state = level.getBlockState(pos);
        int forgeTier = getForgeTier(state);
        int requiredTier = getRitualRequiredTier(ritual);
        if (forgeTier < requiredTier) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.tier_insufficient", requiredTier, forgeTier));
            return false;
        }

        // UpgradeTierResult: FA's checkConditions requires EXACT tier match
        // (forge.currentTier == requiredTier), not just >= like our general check.
        try {
            Object result = invoke(ritual, "result");
            if (result != null && upgradeTierResultClass.isInstance(result)) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] UpgradeTierResult ritual — output depends on main ingredient");
                int upgradeReqTier = readUpgradeRequiredTier(result);
                if (upgradeReqTier >= 0 && forgeTier != upgradeReqTier) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.fa.error.tier_exact_required", upgradeReqTier, forgeTier));
                    return false;
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }

        // Resolve ritual manager — must come BEFORE enhancer check
        try {
            ritualManager = getMethod(hephaestusForgeBEClass, "getRitualManager").invoke(forge);
        } catch (Exception e) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.ritual_manager"));
            return false;
        }
        if (ritualManager == null) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.ritual_manager"));
            return false;
        }

        // Check required enhancers — compare Ritual.requirements().enhancers()
        // against installed enhancers on the forge (EnhancerAccessor.getEnhancers()).
        try {
            Object requirements = invoke(ritual, "requirements");
            if (requirements != null) {
                List<?> requiredEnhancers = invokeList(requirements, "enhancers");
                if (requiredEnhancers != null && !requiredEnhancers.isEmpty()) {
                    java.lang.reflect.Field accessorField = Reflect.findField(
                            ritualManagerClass, "enhancerAccessor").orElse(null);
                    List<?> installedEnhancers = null;
                    if (accessorField != null) {
                        accessorField.setAccessible(true);
                        Object ea = accessorField.get(ritualManager);
                        if (ea != null) {
                            installedEnhancers = (List<?>) getMethod(
                                    enhancerAccessorClass, "getEnhancers").invoke(ea);
                        }
                    }
                    for (Object holder : requiredEnhancers) {
                        Object reqDef = unwrapHolderValue(holder);
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
                    "rsi.fa.error.enhancer_check_failed", e.toString()));
            return false;
        }

        // Validate idle
        try {
            Boolean active = (Boolean) getMethod(ritualManagerClass, "isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) {
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
                return false;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }

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

        // Pre-check essences per-type so player knows exactly what's missing
        if (!checkEssences()) return false;

        // Re-validate idle
        try {
            Boolean active = (Boolean) getMethod(ritualManagerClass, "isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] tryStartSingleCraft: altar busy, aborting");
                return false;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }

        this.filledPedestals = new ArrayList<>();

        // Get pedestals from ritual manager
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
                ItemStack stack = (ItemStack) getMethod(pedestalBEClass, "getStack").invoke(ped);
                if (stack.isEmpty()) {
                    availablePedestals.add(ped);
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        }

        // Collect recipe requirements
        List<?> inputs = invokeList(ritual, "inputs");
        Ingredient mainIng = (Ingredient) invoke(ritual, "mainIngredient");
        int inputEntryCount = inputs != null ? inputs.size() : 0;

        // Calculate total pedestal slots needed (sum of all RitualInput.amount).
        // FA's checkIngredients counts each stack as 1 match, so we must
        // split compacted materials across 1-per-pedestal.
        int totalSlotsNeeded = 0;
        int[] inputAmounts = new int[inputEntryCount];
        for (int i = 0; i < inputEntryCount; i++) {
            int amt = (int) invoke(inputs.get(i), "amount");
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
            Ingredient ing = (Ingredient) invoke(ri, "ingredient");
            int amt = inputAmounts[i];
            if (ing == null) continue;

            ItemStack t = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, amt, ledger);
            if (t.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] tryStartSingleCraft: input[{}] extraction failed", i);
                return false;
            }
            inputTemplates.add(t);
        }

        // Phase 2: place items BEFORE committing ledger (use setStackAndSync
        // to trigger updateIngredient callbacks). Commit only after the ritual
        // starts successfully so that any failure can rollback the ledger.
        try {
            if (!mainTemplate.isEmpty()) {
                int mainSlot = getMainSlot();
                setForgeSlot(forge, mainSlot, mainTemplate);
            }

            int pedIdx = 0;
            for (int i = 0; i < inputEntryCount && i < inputTemplates.size(); i++) {
                ItemStack template = inputTemplates.get(i);
                int amt = inputAmounts[i];
                // Split into individual items — FA's checkIngredients
                // counts stacks (pedestals), not total item count.
                for (int p = 0; p < amt && p < template.getCount(); p++) {
                    ItemStack single = template.copy();
                    single.setCount(1);
                    Object ped = availablePedestals.get(pedIdx++);
                    getMethod(pedestalBEClass, "setStackAndSync", ItemStack.class)
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

        // Populate cachedIngredients in FA's RitualManager.
        // Clear non-filled pedestals first so checkIngredients' "no leftovers"
        // rule doesn't trip on pre-existing items from other pedestals.
        try {
            Object curEssences = getMethod(hephaestusForgeBEClass, "getEssences").invoke(forge);
            Method updateIngredient = getMethod(ritualManagerClass, "updateIngredient",
                    pedestalBEClass, ItemStack.class, essencesDefinitionClass);
            for (Object ped : foundPedestals) {
                if (!filledPedestals.contains(ped)) {
                    updateIngredient.invoke(ritualManager, ped, ItemStack.EMPTY, curEssences);
                }
            }
            for (Object ped : filledPedestals) {
                ItemStack stack = (ItemStack) getMethod(pedestalBEClass, "getStack").invoke(ped);
                updateIngredient.invoke(ritualManager, ped, stack, curEssences);
            }
            getMethod(ritualManagerClass, "updateValidRitual", essencesDefinitionClass)
                    .invoke(ritualManager, curEssences);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] cachedIngredients/updateValidRitual failed: {}", e.toString());
        }

        // Phase 3: require a RitualStarterItem (gavel/hammer) — FA
        // enforces this through its right-click handler; calling
        // tryStartRitual directly bypasses that requirement.
        RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] (single) Searching for RitualStarterItem...");
        final ItemStack starterStack = findRitualStarterItem(player, network);
        if (starterStack.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] tryStartSingleCraft: no usable RitualStarterItem in inventory or RS");
            player.sendSystemMessage(Component.translatable("rsi.fa.error.missing_starter_item"));
            rollbackAll();
            ledger.rollback(player);
            return false;
        }
        RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] (single) Found starter '{}' from {}",
                starterStack.getHoverName().getString(), starterFromRS != null ? "RS" : "inventory");

        // Phase 4: start the ritual BEFORE committing ledger
        try {
            Object essenceMgr = getMethod(hephaestusForgeBEClass, "getEssenceManager").invoke(forge);
            this.essencesStorage = getMethod(essenceManagerClass, "getStorage").invoke(essenceMgr);
            DelegateBooleanConsumer callback = new DelegateBooleanConsumer(this, starterStack, player);
            Object proxy = Proxy.newProxyInstance(
                    booleanConsumerClass.getClassLoader(),
                    new Class<?>[]{booleanConsumerClass},
                    callback);

            RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] (single) Invoking tryStartRitual with starter='{}'",
                    starterStack.getHoverName().getString());
            getMethod(ritualManagerClass, "tryStartRitual", essencesStorageClass, booleanConsumerClass)
                    .invoke(ritualManager, essencesStorage, proxy);
            RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] (single) tryStartRitual returned: wasCalled={} accepted={}",
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
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartRitual failed — forge rejected: {}", root.toString());
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

        // Phase 5: commit — ritual started successfully, now extract items from RS
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Ledger commit failed after ritual start");
            rollbackAll();
            returnStarterToSource(starterStack);
            return false;
        }

        // Only consume the starter AFTER everything succeeded
        consumeRitualStarterUse(starterStack, player);
        return true;
    }

    // ── Chain support: pre-reserved materials ────────────────────

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (ritual == null) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        Ingredient mainIng = (Ingredient) invoke(ritual, "mainIngredient");
        if (mainIng != null && !mainIng.isEmpty()) {
            specs.add(new IngredientSpec(mainIng, 1));
        }
        List<?> inputs = invokeList(ritual, "inputs");
        if (inputs != null) {
            for (Object ri : inputs) {
                Ingredient ing = (Ingredient) invoke(ri, "ingredient");
                int amt = (int) invoke(ri, "amount");
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

        // Pre-check essences per-type so player knows exactly what's missing
        if (!checkEssences()) return false;

        // Re-validate idle
        try {
            Boolean active = (Boolean) getMethod(ritualManagerClass, "isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) {
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
                return false;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }

        this.filledPedestals = new ArrayList<>();

        // Find pedestals
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
                ItemStack stack = (ItemStack) getMethod(pedestalBEClass, "getStack").invoke(ped);
                if (stack.isEmpty()) availablePedestals.add(ped);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        }

        // Materials order: [mainIngredient, input1, input2, ...]
        boolean hasMain = ((Ingredient) invoke(ritual, "mainIngredient")) != null;
        List<?> inputs = invokeList(ritual, "inputs");
        int inputEntryCount = inputs != null ? inputs.size() : 0;

        // Calculate total pedestal slots needed (sum of all RitualInput.amount).
        // FA's checkIngredients counts each stack as 1 match, so we must
        // split compacted materials across 1-per-pedestal.
        int totalSlotsNeeded = 0;
        int[] inputAmounts = new int[inputEntryCount];
        for (int i = 0; i < inputEntryCount; i++) {
            int amt = (int) invoke(inputs.get(i), "amount");
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
            // Main ingredient → forge main slot
            if (hasMain && matIdx < materials.size()) {
                ItemStack mainStack = materials.get(matIdx++);
                if (!mainStack.isEmpty()) {
                    int mainSlot = getMainSlot();
                    setForgeSlot(forge, mainSlot, mainStack);
                }
            }

            // Inputs → pedestals — split compacted stacks into 1-per-pedestal
            int pedIdx = 0;
            for (int i = 0; i < inputEntryCount && matIdx < materials.size(); i++) {
                ItemStack compacted = materials.get(matIdx++);
                if (compacted.isEmpty()) continue;
                int amt = inputAmounts[i];
                for (int p = 0; p < amt && p < compacted.getCount(); p++) {
                    ItemStack single = compacted.copy();
                    single.setCount(1);
                    Object ped = availablePedestals.get(pedIdx++);
                    getMethod(pedestalBEClass, "setStackAndSync", ItemStack.class)
                            .invoke(ped, single);
                    filledPedestals.add(ped);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartWithMaterials: placement failed:", e);
            rollbackAll();
            player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed", e.toString()));
            return false;
        }

        this.emptyPedestals = availablePedestals;

        // Populate cachedIngredients in FA's RitualManager.
        // Clear non-filled pedestals first so checkIngredients' "no leftovers"
        // rule doesn't trip on pre-existing items from other pedestals.
        try {
            Object curEssences = getMethod(hephaestusForgeBEClass, "getEssences").invoke(forge);
            Method updateIngredient = getMethod(ritualManagerClass, "updateIngredient",
                    pedestalBEClass, ItemStack.class, essencesDefinitionClass);
            for (Object ped : foundPedestals) {
                if (!filledPedestals.contains(ped)) {
                    updateIngredient.invoke(ritualManager, ped, ItemStack.EMPTY, curEssences);
                }
            }
            for (Object ped : filledPedestals) {
                ItemStack stack = (ItemStack) getMethod(pedestalBEClass, "getStack").invoke(ped);
                updateIngredient.invoke(ritualManager, ped, stack, curEssences);
            }
            getMethod(ritualManagerClass, "updateValidRitual", essencesDefinitionClass)
                    .invoke(ritualManager, curEssences);

            // Diagnostic: verify updateValidRitual found our ritual
            try {
                Object vR = getValidRitualSafe(ritualManager);
                if (vR == null) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] updateValidRitual: validRitual is NULL — no ritual matched cached ingredients!");
                } else if (vR != this.ritual && !vR.equals(this.ritual)) {
                    Object vRId = invoke(vR, "resourceLocation");
                    Object expectedId = invoke(ritual, "resourceLocation");
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] updateValidRitual: validRitual={} differs from expected={}", vRId, expectedId);
                } else {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] updateValidRitual: matched expected ritual");
                }
            } catch (Exception vEx) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] validRitual check failed (harmless): {}", vEx.toString());
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] cachedIngredients/updateValidRitual failed: {}", e.toString());
        }

        // Require a RitualStarterItem (gavel/hammer) — FA enforces this
        // through its right-click handler; calling tryStartRitual directly
        // bypasses that requirement.
        RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] Searching for RitualStarterItem...");
        final ItemStack starterStack = findRitualStarterItem(player, network);
        if (starterStack.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] tryStartWithMaterials: no usable RitualStarterItem in inventory or RS");
            player.sendSystemMessage(Component.translatable("rsi.fa.error.missing_starter_item"));
            rollbackAll();
            return false;
        }
        RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] Found starter '{}' from {}",
                starterStack.getHoverName().getString(), starterFromRS != null ? "RS" : "inventory");

        // Start the ritual using forge's actual essence storage
        try {
            Object essenceMgr = getMethod(hephaestusForgeBEClass, "getEssenceManager").invoke(forge);
            this.essencesStorage = getMethod(essenceManagerClass, "getStorage").invoke(essenceMgr);
            DelegateBooleanConsumer callback = new DelegateBooleanConsumer(this, starterStack, player);
            Object proxy = Proxy.newProxyInstance(
                    booleanConsumerClass.getClassLoader(),
                    new Class<?>[]{booleanConsumerClass},
                    callback);

            RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] Invoking tryStartRitual (withMaterials) with starter='{}'",
                    starterStack.getHoverName().getString());
            getMethod(ritualManagerClass, "tryStartRitual", essencesStorageClass, booleanConsumerClass)
                    .invoke(ritualManager, essencesStorage, proxy);
            RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] tryStartRitual (withMaterials) returned: wasCalled={} accepted={}",
                    callback.wasCalled, callback.accepted);

            if (callback.wasCalled && !callback.accepted) {
                // Diagnostic: dump state to help trace the rejection reason
                try {
                    Object vR = getValidRitualSafe(ritualManager);
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   validRitual={}", vR);
                    if (vR != null) {
                        Object vRId = invoke(vR, "resourceLocation");
                        RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   validRitual.id={}", vRId);
                        Object matched = vR == this.ritual || vR.equals(this.ritual);
                        RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   matches expected={}", matched);
                    }
                    int mainSlot = getMainSlot();
                    ItemStack forgeStack = getForgeSlot(forge, mainSlot);
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   forge main slot={}: {}", mainSlot, forgeStack);
                    Object curEss = getMethod(hephaestusForgeBEClass, "getEssences").invoke(forge);
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   essences: a={} s={} b={} e={}",
                            getMethod(essencesDefinitionClass, "aureal").invoke(curEss),
                            getMethod(essencesDefinitionClass, "souls").invoke(curEss),
                            getMethod(essencesDefinitionClass, "blood").invoke(curEss),
                            getMethod(essencesDefinitionClass, "experience").invoke(curEss));
                    Object req = invoke(ritual, "essences");
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   required: a={} s={} b={} e={}",
                            invoke(req, "aureal"), invoke(req, "souls"),
                            invoke(req, "blood"), invoke(req, "experience"));
                    for (int i = 0; i < materials.size(); i++) {
                        RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   material[{}]={} x{}",
                                i, materials.get(i), materials.get(i).getCount());
                    }
                } catch (Exception diagEx) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA]   diagnostic dump failed: {}", diagEx.toString());
                }
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_rejected"));
                clearFilledPedestals();
                try {
                    int mainSlot = getMainSlot();
                    setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }
                returnStarterToSource(starterStack);
                return false;
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable root = e.getCause() != null ? e.getCause() : e;
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartRitual failed (withMaterials) — forge rejected: {}", root.toString());
            rollbackAll();
            player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed", root.toString()));
            returnStarterToSource(starterStack);
            return false;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] tryStartWithMaterials: start ritual failed:", e);
            rollbackAll();
            player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed", e.toString()));
            returnStarterToSource(starterStack);
            return false;
        }

        // Only consume the starter AFTER ritual accepted
        consumeRitualStarterUse(starterStack, player);
        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        try {
            if (ritualManager != null) {
                Boolean active = (Boolean) getMethod(ritualManagerClass, "isRitualActive").invoke(ritualManager);
                if (Boolean.TRUE.equals(active)) {
                    ritualEverSeenActive = true;
                    return false;
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        return ritualEverSeenActive;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        // Try CreateItemResult first
        try {
            Object result = invoke(ritual, "result");
            if (result != null && createItemResultClass.isInstance(result)) {
                ItemStack itemResult = (ItemStack) getMethod(createItemResultClass, "getResult").invoke(result);
                if (itemResult != null && !itemResult.isEmpty()) {
                    try {
                        int mainSlot = getMainSlot();
                        setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Clear slot failed", e); }
                    return itemResult.copy();
                }
            }
            // UpgradeTierResult: the upgraded item is on the forge main slot
            if (result != null && upgradeTierResultClass.isInstance(result)) {
                try {
                    int mainSlot = getMainSlot();
                    ItemStack upgraded = getForgeSlot(forge, mainSlot);
                    if (!upgraded.isEmpty()) {
                        setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
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
        // Fallback: try forge main slot
        try {
            int mainSlot = getMainSlot();
            ItemStack result = getForgeSlot(forge, mainSlot);
            if (!result.isEmpty()) {
                setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
            }
            return result;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        // Defensive: if starterFromRS still holds an extracted item that was
        // never consumed or returned (e.g. chain failed between extraction
        // and consumeRitualStarterUse), return it now.
        if (starterFromRS != null && network != null) {
            ItemStack leftover = network.insertItem(starterFromRS, starterFromRS.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!leftover.isEmpty()) {
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
            starterFromRS = null;
        }
        rollbackAll();
        ledger = null;
        sharedLedger = null;
        network = null;
        starterFromRS = null;
        usingSharedLedger = false;
        ritualEverSeenActive = false;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        clearFilledPedestals();
        try {
            int mainSlot = hephaestusForgeBEClass.getField("MAIN_SLOT").getInt(null);
            setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        ledger = null;
        sharedLedger = null;
        network = null;
        starterFromRS = null;
        usingSharedLedger = false;
        ritualEverSeenActive = false;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    // ── Rollback / cleanup ───────────────────────────────────────

    void rollbackAll() {
        clearFilledPedestals();
        try {
            int mainSlot = hephaestusForgeBEClass.getField("MAIN_SLOT").getInt(null);
            ItemStack stack = getForgeSlot(forge, mainSlot);
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
            setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }
    }

    private void clearFilledPedestals() {
        if (filledPedestals == null) return;
        for (Object ped : filledPedestals) {
            try {
                ItemStack stack = (ItemStack) getMethod(pedestalBEClass, "getStack").invoke(ped);
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
                getMethod(pedestalBEClass, "clearStack", Level.class, boolean.class)
                        .invoke(ped, null, false);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        }
    }

    // ── RitualStarterItem helpers ─────────────────────────────────

    /** Tracks whether the starter item was extracted from RS (so we can re-insert it). */
    @javax.annotation.Nullable
    private transient ItemStack starterFromRS;

    /** Return a starter item that was extracted from RS back to its source on failure. */
    private void returnStarterToSource(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (starterFromRS != null && network != null) {
            ItemStack leftover = network.insertItem(stack, stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            starterFromRS = null;
            if (!leftover.isEmpty() && player != null) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] returnStarterToSource: RS insert failed, giving '{}' to player",
                        stack.getHoverName().getString());
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        }
        starterFromRS = null; // always clear, even if we couldn't re-insert
    }

    /**
     * Finds a valid RitualStarterItem (gavel/hammer) that has at least one
     * remaining use.  Checks the player inventory first, then falls back to
     * extracting from the RS network.  FA requires a right-click with such
     * an item to start any ritual — calling {@code tryStartRitual} directly
     * bypasses that requirement.
     *
     * @return the first usable starter stack, or {@code ItemStack.EMPTY}
     */
    private ItemStack findRitualStarterItem(ServerPlayer player, @Nullable INetwork network) {
        ensureClasses();
        if (ritualStarterItemClass == null) return ItemStack.EMPTY;

        // 1. Check player inventory + offhand
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && ritualStarterItemClass.isInstance(stack.getItem())
                    && canStartRitual(stack)) {
                RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] Found RitualStarterItem '{}' in player inventory",
                        stack.getHoverName().getString());
                return stack;
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && ritualStarterItemClass.isInstance(offhand.getItem())
                && canStartRitual(offhand)) {
            RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] Found RitualStarterItem '{}' in player offhand",
                    offhand.getHoverName().getString());
            return offhand;
        }

        // 2. Fall back to RS network
        if (network != null) {
            try {
                var cacheList = network.getItemStorageCache().getList();
                if (cacheList != null) {
                    for (var entry : cacheList.getStacks()) {
                        ItemStack rsStack = entry.getStack();
                        if (rsStack.isEmpty()) continue;
                        if (!ritualStarterItemClass.isInstance(rsStack.getItem())) continue;
                        if (!canStartRitual(rsStack)) {
                            RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] RS has RitualStarterItem '{}' but canStartRitual=false",
                                    rsStack.getHoverName().getString());
                            continue;
                        }

                        // Extract 1 from RS
                        ItemStack req = rsStack.copy();
                        req.setCount(1);
                        ItemStack extracted = network.extractItem(req, 1,
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                        if (!extracted.isEmpty()) {
                            RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] Extracted RitualStarterItem '{}' from RS",
                                    extracted.getHoverName().getString());
                            starterFromRS = extracted;
                            return extracted;
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] RS starter search failed: {}", e.toString());
            }
        }

        return ItemStack.EMPTY;
    }

    private boolean canStartRitual(ItemStack stack) {
        try {
            return (boolean) getMethod(ritualStarterItemClass,
                    "canStartRitual", ItemStack.class).invoke(stack.getItem(), stack);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Decrements remaining uses on a RitualStarterItem unless the player
     * is in creative mode.  If the item was extracted from RS, re-inserts
     * it after modifying durability.
     */
    private void consumeRitualStarterUse(ItemStack starterStack, ServerPlayer player) {
        boolean isCreative = player.isCreative();
        String source = starterFromRS != null ? "RS" : "inventory";
        RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] consumeRitualStarterUse ENTRY: item='{}' creative={} source={}",
                starterStack.getHoverName().getString(), isCreative, source);

        if (!isCreative) {
            try {
                Object item = starterStack.getItem();
                int remaining = (int) getMethod(ritualStarterItemClass,
                        "getRemainingUses", ItemStack.class).invoke(item, starterStack);
                RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] Starter '{}' uses before: {} (source: {})",
                        starterStack.getHoverName().getString(), remaining, source);
                if (remaining > 0) {
                    int newRemaining = remaining - 1;
                    getMethod(ritualStarterItemClass,
                            "setRemainingUses", ItemStack.class, int.class)
                            .invoke(item, starterStack, newRemaining);
                    RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] Starter '{}' uses after: {}",
                            starterStack.getHoverName().getString(), newRemaining);
                } else {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] Starter '{}' has {} remaining uses — cannot consume",
                            starterStack.getHoverName().getString(), remaining);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] consumeRitualStarterUse failed for '{}': {}",
                        starterStack.getHoverName().getString(), e.toString());
            }
        } else {
            RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] consumeRitualStarterUse: player is creative, not consuming durability");
        }

        // Always re-insert to RS if it came from there — even in creative mode
        if (starterFromRS != null && network != null) {
            ItemStack leftover = network.insertItem(starterStack, starterStack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            starterFromRS = null;
            if (!leftover.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] consumeRitualStarterUse: RS insert failed, giving '{}' to player",
                        starterStack.getHoverName().getString());
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        }
    }

    // ── Tier helpers ─────────────────────────────────────────────

    private static int getForgeTier(BlockState state) {
        try {
            for (net.minecraft.world.level.block.state.properties.Property<?> prop : state.getProperties()) {
                if (prop.getName().equals("tier")) {
                    Comparable<?> val = state.getValue(prop);
                    if (val instanceof Number n) return n.intValue();
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        return 1;
    }

    private static int getRitualRequiredTier(Object ritual) {
        try {
            Object req = invoke(ritual, "requirements");
            if (req != null) {
                return (int) getMethod(req.getClass(), "tier").invoke(req);
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Reflection probe failed", e); }
        return 1;
    }

    /** Reads the exact required tier from an UpgradeTierResult. Returns -1 on failure. */
    private static int readUpgradeRequiredTier(Object upgradeResult) {
        try {
            return (int) getMethod(upgradeTierResultClass, "requiredTier").invoke(upgradeResult);
        } catch (Exception e) {}
        try {
            java.lang.reflect.Field f = Reflect.findField(upgradeTierResultClass, "requiredTier").orElse(null);
            if (f != null) { f.setAccessible(true); return f.getInt(upgradeResult); }
        } catch (Exception e) {}
        return -1;
    }

    // ── Essence validation ──────────────────────────────────────

    /**
     * Checks all 4 essence types individually and sends per-type error
     * messages so the player knows exactly what's missing.
     *
     * @return true if all required essences are available
     */
    /**
     * Checks all 4 essence types individually and sends per-type error
     * messages so the player knows exactly what's missing.
     *
     * Reads current essences from forge.getEssences() (EssencesContainer
     * interface), which is the canonical source. Avoids EssenceManager
     * getters that may return stale / uninitialized values.
     *
     * @return true if all required essences are available
     */
    /**
     * Collect {@code EssenceModifier} instances from enhancers placed in the forge.
     * Mirrors what {@code RitualManager.canStartRitual} does before comparing essences.
     */
    @SuppressWarnings("unchecked")
    private List<Object> collectEnhancerModifiers() {
        List<Object> modifiers = new ArrayList<>();
        try {
            // Only collect modifiers from enhancers the ritual requires.
            // Ritual.requirements().enhancers() lists the required EnhancerDefinitions.
            Set<Object> requiredDefs = new HashSet<>();
            Object requirements = invoke(ritual, "requirements");
            if (requirements != null) {
                List<?> requiredEnhancers = invokeList(requirements, "enhancers");
                if (requiredEnhancers != null) {
                    for (Object holder : requiredEnhancers) {
                        Object def = unwrapHolderValue(holder);
                        if (def != null) requiredDefs.add(def);
                    }
                }
            }
            if (requiredDefs.isEmpty()) return modifiers;

            java.lang.reflect.Field accessorField = Reflect.findField(ritualManagerClass, "enhancerAccessor").orElse(null);
            if (accessorField == null) return modifiers;
            accessorField.setAccessible(true);
            Object enhancerAccessor = accessorField.get(ritualManager);
            if (enhancerAccessor == null) return modifiers;

            List<?> enhancers = (List<?>) getMethod(enhancerAccessorClass, "getEnhancers").invoke(enhancerAccessor);
            if (enhancers == null) return modifiers;

            for (Object enhancerDef : enhancers) {
                if (!requiredDefs.contains(enhancerDef)) continue;
                List<?> effects = (List<?>) getMethod(enhancerDefinitionClass, "effects").invoke(enhancerDef);
                if (effects == null) continue;
                for (Object effect : effects) {
                    if (essenceModifierClass.isInstance(effect)) {
                        modifiers.add(effect);
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] Failed to collect enhancer modifiers: {}", e.toString());
        }
        return modifiers;
    }

    private boolean checkEssences() {
        try {
            ensureClasses();
            Object ritualEssences = getMethod(ritualClass, "essences").invoke(ritual);

            // Apply enhancer modifiers to match FA's canStartRitual behavior
            List<Object> enhancerModifiers = collectEnhancerModifiers();
            Object requiredEssences = ritualEssences;
            if (!enhancerModifiers.isEmpty()) {
                requiredEssences = getMethod(essencesDefinitionClass, "applyModifiers",
                        List.class).invoke(ritualEssences, enhancerModifiers);
            }

            int reqAureal = (int) getMethod(essencesDefinitionClass, "aureal").invoke(requiredEssences);
            int reqSouls  = (int) getMethod(essencesDefinitionClass, "souls").invoke(requiredEssences);
            int reqBlood  = (int) getMethod(essencesDefinitionClass, "blood").invoke(requiredEssences);
            int reqExp    = (int) getMethod(essencesDefinitionClass, "experience").invoke(requiredEssences);

            // Read current essences directly from the forge via EssencesContainer.getEssences()
            // rather than through EssenceManager which may hold uninitialized internal state.
            Object curEssences = getMethod(hephaestusForgeBEClass, "getEssences").invoke(forge);
            int curAureal = (int) getMethod(essencesDefinitionClass, "aureal").invoke(curEssences);
            int curSouls  = (int) getMethod(essencesDefinitionClass, "souls").invoke(curEssences);
            int curBlood  = (int) getMethod(essencesDefinitionClass, "blood").invoke(curEssences);
            int curExp    = (int) getMethod(essencesDefinitionClass, "experience").invoke(curEssences);

            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Essence check: a={}/{}, s={}/{}, b={}/{}, e={}/{}",
                    curAureal, reqAureal, curSouls, reqSouls, curBlood, reqBlood, curExp, reqExp);

            boolean ok = true;
            if (reqAureal > 0 && curAureal < reqAureal) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.fa.error.insufficient_aureal", reqAureal, curAureal));
                ok = false;
            }
            if (reqSouls > 0 && curSouls < reqSouls) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.fa.error.insufficient_souls", reqSouls, curSouls));
                ok = false;
            }
            if (reqBlood > 0 && curBlood < reqBlood) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.fa.error.insufficient_blood", reqBlood, curBlood));
                ok = false;
            }
            if (reqExp > 0 && curExp < reqExp) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.fa.error.insufficient_experience", reqExp, curExp));
                ok = false;
            }
            return ok;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Essence check failed: {}", e.toString(), e);
            player.sendSystemMessage(Component.translatable("rsi.fa.error.essence_check_failed", e.toString()));
            return false;
        }
    }

    // ── Plan-time essence warnings (static, called from tryBuildPlan) ──

    @SuppressWarnings("unchecked")
    private static List<Object> collectEnhancerModifiersStatic(Object ritualManager, Object ritual) {
        List<Object> modifiers = new ArrayList<>();
        try {
            ensureClasses();
            // Only collect modifiers from enhancers the ritual requires
            Set<Object> requiredDefs = new HashSet<>();
            Object requirements = invoke(ritual, "requirements");
            if (requirements != null) {
                List<?> requiredEnhancers = invokeList(requirements, "enhancers");
                if (requiredEnhancers != null) {
                    for (Object holder : requiredEnhancers) {
                        Object def = unwrapHolderValue(holder);
                        if (def != null) requiredDefs.add(def);
                    }
                }
            }
            if (requiredDefs.isEmpty()) return modifiers;

            java.lang.reflect.Field accessorField = Reflect.findField(ritualManagerClass, "enhancerAccessor").orElse(null);
            if (accessorField == null) return modifiers;
            accessorField.setAccessible(true);
            Object enhancerAccessor = accessorField.get(ritualManager);
            if (enhancerAccessor == null) return modifiers;

            List<?> enhancers = (List<?>) getMethod(enhancerAccessorClass, "getEnhancers").invoke(enhancerAccessor);
            if (enhancers == null) return modifiers;

            for (Object enhancerDef : enhancers) {
                if (!requiredDefs.contains(enhancerDef)) continue;
                List<?> effects = (List<?>) getMethod(enhancerDefinitionClass, "effects").invoke(enhancerDef);
                if (effects == null) continue;
                for (Object effect : effects) {
                    if (essenceModifierClass.isInstance(effect)) {
                        modifiers.add(effect);
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] Static enhancer collection failed: {}", e.toString());
        }
        return modifiers;
    }

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
        ensureClasses();
        if (hephaestusForgeBEClass == null || ritualClass == null || essencesDefinitionClass == null)
            return warnings;

        Object ritual = wrapper.ritual();
        if (ritual == null) return warnings;

        RSIntegrationMod.LOGGER.info("[RSI-Batch-FA] getPlanWarnings called for recipe={} dim={} pos={}",
                recipe.getId(), dim, pos);

        // Resolve requirements once — shared by tier and enhancer checks
        Object requirements = invoke(ritual, "requirements");

        // ── Essence check ──────────────────────────────────────────
        Object ritualEssences = invoke(ritual, "essences");
        if (ritualEssences != null) {
            int reqAureal = invokeInt(ritualEssences, "aureal");
            int reqSouls  = invokeInt(ritualEssences, "souls");
            int reqBlood  = invokeInt(ritualEssences, "blood");
            int reqExp    = invokeInt(ritualEssences, "experience");

            // Try to read current essences from a bound forge
            int curAureal = -1, curSouls = -1, curBlood = -1, curExp = -1;

            if (dim != null && pos != null) {
                try {
                    ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                    if (level != null && level.isLoaded(pos)) {
                        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                        if (be != null && hephaestusForgeBEClass.isInstance(be)) {
                            // Apply enhancer modifiers to required essences
                            Object ritualManager = getMethod(hephaestusForgeBEClass, "getRitualManager").invoke(be);
                            if (ritualManager != null) {
                                List<Object> enhancerModifiers = collectEnhancerModifiersStatic(ritualManager, ritual);
                                if (!enhancerModifiers.isEmpty()) {
                                    Object modified = getMethod(essencesDefinitionClass, "applyModifiers",
                                            List.class).invoke(ritualEssences, enhancerModifiers);
                                    reqAureal = (int) getMethod(essencesDefinitionClass, "aureal").invoke(modified);
                                    reqSouls  = (int) getMethod(essencesDefinitionClass, "souls").invoke(modified);
                                    reqBlood  = (int) getMethod(essencesDefinitionClass, "blood").invoke(modified);
                                    reqExp    = (int) getMethod(essencesDefinitionClass, "experience").invoke(modified);
                                }
                            }

                            Object curEssences = getMethod(hephaestusForgeBEClass, "getEssences").invoke(be);
                            curAureal = (int) getMethod(essencesDefinitionClass, "aureal").invoke(curEssences);
                            curSouls  = (int) getMethod(essencesDefinitionClass, "souls").invoke(curEssences);
                            curBlood  = (int) getMethod(essencesDefinitionClass, "blood").invoke(curEssences);
                            curExp    = (int) getMethod(essencesDefinitionClass, "experience").invoke(curEssences);
                        }
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Plan essence read failed: {}", e.toString());
                }
            }

            if (curAureal >= 0) {
                // Bound forge — show current vs. needed
                if (reqAureal > 0 && curAureal < reqAureal)
                    warnings.add(Component.translatable("rsi.fa.warn.insufficient_aureal", reqAureal, curAureal).getString());
                if (reqSouls > 0 && curSouls < reqSouls)
                    warnings.add(Component.translatable("rsi.fa.warn.insufficient_souls", reqSouls, curSouls).getString());
                if (reqBlood > 0 && curBlood < reqBlood)
                    warnings.add(Component.translatable("rsi.fa.warn.insufficient_blood", reqBlood, curBlood).getString());
                if (reqExp > 0 && curExp < reqExp)
                    warnings.add(Component.translatable("rsi.fa.warn.insufficient_experience", reqExp, curExp).getString());
            } else if (reqAureal > 0 || reqSouls > 0 || reqBlood > 0 || reqExp > 0) {
                // No forge bound — list required essence types
                warnings.add(Component.translatable("rsi.fa.warn.essence_required",
                        reqAureal, reqSouls, reqBlood, reqExp).getString());
            }

            // Check essence input slots — warn if an essence type is below
            // requirement but its dedicated forge input slot is empty (no
            // items to burn for essence).
            try {
                if (dim != null && pos != null) {
                    ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                    if (level != null && level.isLoaded(pos)) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null && hephaestusForgeBEClass.isInstance(be)) {
                            java.lang.reflect.Field slotMapField = Reflect.findField(
                                    hephaestusForgeBEClass, "SLOT_FROM_ESSENCE_TYPE_MAP").orElse(null);
                            if (slotMapField != null) {
                                Object slotMap = slotMapField.get(null);
                                if (slotMap instanceof java.util.Map<?, ?> map) {
                                    Object curEssences = getMethod(hephaestusForgeBEClass,
                                            "getEssences").invoke(be);
                                    int curA = (int) getMethod(essencesDefinitionClass, "aureal").invoke(curEssences);
                                    int curS = (int) getMethod(essencesDefinitionClass, "souls").invoke(curEssences);
                                    int curB = (int) getMethod(essencesDefinitionClass, "blood").invoke(curEssences);
                                    int curE = (int) getMethod(essencesDefinitionClass, "experience").invoke(curEssences);

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
                                            ItemStack slotStack = getForgeSlot(be, slot);
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
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Plan essence slot check failed: {}", e.toString());
            }
        }

        // ── Forge tier check ───────────────────────────────────────
        if (requirements != null) {
            try {
                int requiredTier = (int) getMethod(requirements.getClass(), "tier").invoke(requirements);
                if (requiredTier > 1) {
                    if (dim != null && pos != null) {
                        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                        if (level != null && level.isLoaded(pos)) {
                            BlockState state = level.getBlockState(pos);
                            int forgeTier = getForgeTier(state);
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
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Plan tier check failed: {}", e.toString());
            }
        }

        // ── Enhancer check ─────────────────────────────────────────
        if (requirements != null) {
            try {
                List<?> requiredEnhancers = invokeList(requirements, "enhancers");
                if (requiredEnhancers != null && !requiredEnhancers.isEmpty()) {
                    // Resolve required enhancer names
                    List<String> reqNames = new ArrayList<>();
                    for (Object holder : requiredEnhancers) {
                        Object reqDef = unwrapHolderValue(holder);
                        if (reqDef != null) {
                            reqNames.add(enhancerDefName(reqDef));
                        }
                    }
                    if (dim != null && pos != null) {
                        // Bound forge — check which enhancers are installed
                        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                        if (level != null && level.isLoaded(pos)) {
                            BlockEntity be = level.getBlockEntity(pos);
                            if (be != null && hephaestusForgeBEClass.isInstance(be)) {
                                Object rm = getMethod(hephaestusForgeBEClass,
                                        "getRitualManager").invoke(be);
                                if (rm != null) {
                                    java.lang.reflect.Field af = Reflect.findField(
                                            ritualManagerClass, "enhancerAccessor").orElse(null);
                                    List<?> installedEnhancers = null;
                                    if (af != null) {
                                        af.setAccessible(true);
                                        Object ea = af.get(rm);
                                        if (ea != null) {
                                            installedEnhancers = (List<?>) getMethod(
                                                    enhancerAccessorClass, "getEnhancers").invoke(ea);
                                        }
                                    }
                                    for (int i = 0; i < requiredEnhancers.size(); i++) {
                                        Object holder = requiredEnhancers.get(i);
                                        Object reqDef = unwrapHolderValue(holder);
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
                                                    enhancerDefName(reqDef)).getString());
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // No bound forge — list required enhancers so user knows
                        for (String name : reqNames) {
                            warnings.add(Component.translatable(
                                    "rsi.fa.warn.enhancer_required", name).getString());
                        }
                    }
                } else if (requiredEnhancers == null) {
                    // Can't read enhancers — reflection failed on requirements.enhancers()
                    warnings.add(Component.translatable(
                            "rsi.fa.warn.cant_check_enhancers").getString());
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] Plan enhancer check failed: {}", e.toString());
                warnings.add(Component.translatable(
                        "rsi.fa.warn.cant_check_enhancers").getString());
            }
        } else {
            // Can't read requirements at all — reflection failed on ritual.requirements()
            warnings.add(Component.translatable(
                    "rsi.fa.warn.cant_check_enhancers").getString());
        }

        return warnings;
    }

    private static int invokeInt(Object obj, String methodName) {
        try {
            Method m = Reflect.findMethod(obj.getClass(), methodName, new Class<?>[0]);
            if (m == null) return 0;
            Object v = m.invoke(obj);
            return v instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) { return 0; }
    }

    private static String enhancerDefName(Object enhancerDef) {
        // Extract display name from EnhancerDefinition: try item.description first
        Optional<Object> item = Reflect.getField(enhancerDef, "item");
        if (item.isPresent() && item.get() instanceof Item it) {
            return it.getDescription().getString();
        }
        // Fallback: try description Component directly
        Optional<Object> desc = Reflect.invoke(enhancerDef, "getDescription");
        if (desc.isPresent() && desc.get() instanceof Component c) {
            return c.getString();
        }
        return enhancerDef.toString();
    }

    // ── Pedestal finding ─────────────────────────────────────────

    private List<Object> findPedestals(ServerLevel level) {
        List<Object> result = new ArrayList<>();
        try {
            BlockPos.betweenClosedStream(
                    myPos.offset(-8, -3, -8),
                    myPos.offset(8, 3, 8)
            ).forEach(cp -> {
                BlockEntity be = level.getBlockEntity(cp);
                if (be != null && pedestalBEClass.isInstance(be)) {
                    result.add(be);
                }
            });
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Failed to find pedestals", e);
            return null;
        }
        return result;
    }

    // ── Slot helpers ─────────────────────────────────────────────

    private static volatile Integer cachedMainSlot;

    private static int getMainSlot() {
        Integer v = cachedMainSlot;
        if (v != null) return v;
        try {
            java.lang.reflect.Field f = Reflect.findField(hephaestusForgeBEClass, "MAIN_SLOT").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                cachedMainSlot = f.getInt(null);
                return cachedMainSlot;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] MAIN_SLOT lookup failed", e);
        }
        return 0;
    }

    private static void setForgeSlot(Object be, int slot, ItemStack stack) {
        // Strategy 1: setStack(int, ItemStack) — FA's actual method name
        // (confirmed by betterjei's ForgeRitualIntegration)
        try {
            Method m = Reflect.findMethod(be.getClass(), "setStack",
                    new Class<?>[]{int.class, ItemStack.class});
            if (m != null) { m.invoke(be, slot, stack); return; }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S1 setStack({}) failed: {}", slot, e.toString());
        }
        // Strategy 2: setStackInSlot (MCP mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "setStackInSlot",
                    new Class<?>[]{int.class, ItemStack.class});
            if (m != null) { m.invoke(be, slot, stack); return; }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S2 setStackInSlot({}) failed: {}", slot, e.toString());
        }
        // Strategy 3: setItem (Mojang mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "setItem",
                    new Class<?>[]{int.class, ItemStack.class});
            if (m != null) { m.invoke(be, slot, stack); return; }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S3 setItem({}) failed: {}", slot, e.toString());
        }
        // Strategy 4: Forge IItemHandler capability
        try {
            var cap = net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER;
            var handler = be.getClass().getMethod("getCapability",
                    net.minecraftforge.common.capabilities.Capability.class,
                    net.minecraft.core.Direction.class)
                    .invoke(be, cap, null);
            if (handler != null) {
                var ih = (net.minecraftforge.items.IItemHandler) handler;
                if (slot < ih.getSlots()) {
                    ih.extractItem(slot, ih.getStackInSlot(slot).getCount(), false);
                    if (!stack.isEmpty()) ih.insertItem(slot, stack.copy(), false);
                }
                return;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S4 IItemHandler set({}) failed: {}", slot, e.toString());
        }
        // Strategy 5: itemStackHandler field
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "itemStackHandler").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                Object h = f.get(be);
                if (h instanceof net.minecraftforge.items.IItemHandler ih) {
                    if (slot < ih.getSlots()) {
                        ih.extractItem(slot, ih.getStackInSlot(slot).getCount(), false);
                        if (!stack.isEmpty()) ih.insertItem(slot, stack.copy(), false);
                    }
                    return;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S5 itemStackHandler field({}) failed: {}", slot, e.toString());
        }
        // Strategy 6: inventory / items NonNullList field
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "inventory").orElse(null);
            if (f == null) f = Reflect.findField(be.getClass(), "items").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                var list = (net.minecraft.core.NonNullList<ItemStack>) f.get(be);
                if (slot < list.size()) { list.set(slot, stack.copy()); be.getClass().getMethod("setChanged").invoke(be); }
                return;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S6 inventory field({}) failed: {}", slot, e.toString());
        }
        RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] All slot strategies failed for set slot {}", slot);
    }

    private static ItemStack getForgeSlot(Object be, int slot) {
        // Strategy 1: getStack(int) — FA's actual method name
        try {
            Method m = Reflect.findMethod(be.getClass(), "getStack",
                    new Class<?>[]{int.class});
            if (m != null) return (ItemStack) m.invoke(be, slot);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S1 getStack({}) failed: {}", slot, e.toString());
        }
        // Strategy 2: getStackInSlot (MCP mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "getStackInSlot",
                    new Class<?>[]{int.class});
            if (m != null) return (ItemStack) m.invoke(be, slot);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S2 getStackInSlot({}) failed: {}", slot, e.toString());
        }
        // Strategy 3: getItem (Mojang mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "getItem",
                    new Class<?>[]{int.class});
            if (m != null) return (ItemStack) m.invoke(be, slot);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S3 getItem({}) failed: {}", slot, e.toString());
        }
        // Strategy 4: Forge IItemHandler capability
        try {
            var cap = net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER;
            var handler = be.getClass().getMethod("getCapability",
                    net.minecraftforge.common.capabilities.Capability.class,
                    net.minecraft.core.Direction.class)
                    .invoke(be, cap, null);
            if (handler != null) {
                var ih = (net.minecraftforge.items.IItemHandler) handler;
                if (slot < ih.getSlots()) return ih.getStackInSlot(slot);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S4 IItemHandler get({}) failed: {}", slot, e.toString());
        }
        // Strategy 5: itemStackHandler field
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "itemStackHandler").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                Object h = f.get(be);
                if (h instanceof net.minecraftforge.items.IItemHandler ih) {
                    if (slot < ih.getSlots()) return ih.getStackInSlot(slot);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S5 itemStackHandler field({}) failed: {}", slot, e.toString());
        }
        // Strategy 6: inventory / items NonNullList field
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "inventory").orElse(null);
            if (f == null) f = Reflect.findField(be.getClass(), "items").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                var list = (net.minecraft.core.NonNullList<ItemStack>) f.get(be);
                if (slot < list.size()) return list.get(slot);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-FA] S6 inv field({}) failed: {}", slot, e.toString());
        }
        return ItemStack.EMPTY;
    }

    // ── Reflection helpers ───────────────────────────────────────

    private static Method getMethod(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        Method m = Reflect.findMethod(clazz, name, paramTypes);
        if (m == null) throw new NoSuchMethodException(clazz.getName() + "." + name);
        return m;
    }

    /**
     * Extract the held value from a net.minecraft.core.Holder (often Holder$Reference).
     * In SRG-mapped Forge the get()/value() methods may be named m_203334_() etc.,
     * and the backing field may be f_205752_ instead of "value".  This method tries
     * every known name, then falls back to scanning declared fields by type.
     */
    @Nullable
    private static Object unwrapHolderValue(Object holder) {
        if (holder == null) return null;
        // 1. Try method "value" (MCP) then "m_203334_" (SRG)
        for (String name : new String[]{"value", "m_203334_"}) {
            try {
                Method m = Reflect.findMethod(holder.getClass(), name, new Class<?>[0]);
                if (m != null) {
                    Object v = m.invoke(holder);
                    if (v != null) return v;
                }
            } catch (Exception ignored) {}
        }
        // 2. Try field "value" (MCP), "f_205752_" (SRG), "delegate", "wrapped"
        for (String name : new String[]{"value", "f_205752_", "delegate", "wrapped"}) {
            try {
                Optional<Field> opt = Reflect.findField(holder.getClass(), name);
                if (opt.isPresent()) {
                    Field f = opt.get();
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v != null) return v;
                }
            } catch (Exception ignored) {}
        }
        // 3. Brute-force: scan all declared fields for one with a non-null reference value
        try {
            for (Field f : holder.getClass().getDeclaredFields()) {
                if (f.getType() == Object.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(holder);
                if (v != null && !(v instanceof String) && !(v instanceof Number)
                        && !(v instanceof Boolean) && !(v instanceof java.util.Set)
                        && !(v instanceof java.util.Map) && !(v instanceof java.util.Collection)) {
                    return v;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Object invoke(Object obj, String methodName) {
        try {
            Method m = Reflect.findMethod(obj.getClass(), methodName, new Class<?>[0]);
            if (m == null) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] invoke: method not found {}.{}",
                        obj.getClass().getName(), methodName);
                return null;
            }
            return m.invoke(obj);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] invoke failed: {}.{} — {}",
                    obj.getClass().getName(), methodName, e.toString());
            return null;
        }
    }

    /**
     * Tries multiple reflection strategies to read RitualManager's current
     * valid ritual.  FA versions rename this field/method across builds.
     */
    @Nullable
    private static Object getValidRitualSafe(Object ritualManager) {
        // Strategy 1: getValidRitual() method
        try {
            Method m = Reflect.findMethod(ritualManagerClass, "getValidRitual", new Class<?>[0]);
            if (m != null) return m.invoke(ritualManager);
        } catch (Exception ignored) {}
        // Strategy 2: getRitual() method
        try {
            Method m = Reflect.findMethod(ritualManagerClass, "getRitual", new Class<?>[0]);
            if (m != null) return m.invoke(ritualManager);
        } catch (Exception ignored) {}
        // Strategy 3: validRitual field
        try {
            java.lang.reflect.Field f = Reflect.findField(ritualManagerClass, "validRitual").orElse(null);
            if (f != null) { f.setAccessible(true); return f.get(ritualManager); }
        } catch (Exception ignored) {}
        // Strategy 4: ritual field
        try {
            java.lang.reflect.Field f = Reflect.findField(ritualManagerClass, "ritual").orElse(null);
            if (f != null) { f.setAccessible(true); return f.get(ritualManager); }
        } catch (Exception ignored) {}
        return null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<?> invokeList(Object obj, String methodName) {
        try {
            Method m = Reflect.findMethod(obj.getClass(), methodName, new Class<?>[0]);
            if (m == null) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] invokeList: method not found {}.{}",
                        obj.getClass().getName(), methodName);
                return null;
            }
            return (List<?>) m.invoke(obj);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-FA] invokeList failed: {}.{} — {}",
                    obj.getClass().getName(), methodName, e.toString());
            return null;
        }
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
                    // Defer starter consumption — call consumeRitualStarterUse()
                    // only after ledger commit succeeds.
                } else if (!delegate.usingSharedLedger) {
                    delegate.rollbackAll();
                }
            }
            return null;
        }
    }
}
