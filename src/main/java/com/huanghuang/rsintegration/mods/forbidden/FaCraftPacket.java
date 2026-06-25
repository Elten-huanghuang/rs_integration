package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Supplier;

public final class FaCraftPacket {

    private final ResourceLocation ritualId;
    @Nullable private final ResourceLocation dim;
    private final BlockPos pos;

    // Cache registry keys and class refs
    private static volatile ResourceKey<?> faRitualKey;
    private static volatile boolean classesLoaded;
    private static volatile Class<?> hephaestusForgeBEClass;
    private static volatile Class<?> pedestalBEClass;
    private static volatile Class<?> ritualClass;
    private static volatile Class<?> ritualInputClass;
    private static volatile Class<?> createItemResultClass;
    private static volatile Class<?> essencesDefinitionClass;
    private static volatile Class<?> essencesStorageClass;
    private static volatile Class<?> ritualRequirementsClass;
    private static volatile Class<?> ritualManagerClass;
    private static volatile Class<?> booleanConsumerClass;
    private static volatile Class<?> essenceManagerClass;
    private static volatile Class<?> upgradeTierResultClass;
    private static volatile Class<?> ritualStarterItemClass;
    private static volatile Class<?> enhancerAccessorClass;
    private static volatile Class<?> enhancerDefinitionClass;
    private static volatile Class<?> enhancerEffectClass;
    private static volatile Class<?> essenceModifierClass;

    // ── static init ────────────────────────────────────────────

    private static void ensureClasses() {
        if (classesLoaded) return;
        classesLoaded = true;
        try {
            hephaestusForgeBEClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.HephaestusForgeBlockEntity");
            pedestalBEClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.PedestalBlockEntity");
            ritualClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.Ritual");
            ritualInputClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.RitualInput");
            createItemResultClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.result.CreateItemResult");
            essencesDefinitionClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssencesDefinition");
            essencesStorageClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssencesStorage");
            ritualRequirementsClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.RitualRequirements");
            ritualManagerClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.RitualManager");
            booleanConsumerClass = Class.forName(
                    "it.unimi.dsi.fastutil.booleans.BooleanConsumer");
            essenceManagerClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssenceManager");
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
        } catch (ClassNotFoundException e) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Failed to load FA classes", e);
        }
    }

    @Nullable
    private static ResourceKey<?> getFARegistryKey() {
        if (faRitualKey != null) return faRitualKey;
        try {
            Class<?> faRegistries = Class.forName(
                    "com.stal111.forbidden_arcanus.core.registry.FARegistries");
            java.lang.reflect.Field field = faRegistries.getField("RITUAL");
            field.setAccessible(true);
            faRitualKey = (ResourceKey<?>) field.get(null);
        } catch (Exception ex) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Failed to get FA ritual registry key", ex);
        }
        return faRitualKey;
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

    // ── packet ─────────────────────────────────────────────────

    public FaCraftPacket(ResourceLocation ritualId, @Nullable ResourceLocation dim, BlockPos pos) {
        this.ritualId = ritualId;
        this.dim = dim;
        this.pos = pos;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(ritualId);
        buf.writeBoolean(dim != null);
        if (dim != null) buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
    }

    public static FaCraftPacket decode(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        ResourceLocation d = buf.readBoolean() ? buf.readResourceLocation() : null;
        return new FaCraftPacket(id, d, buf.readBlockPos());
    }

    public static void handle(FaCraftPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }        context.enqueueWork(() -> {
            try {
                tryCraft(player, packet.ritualId, packet.dim, packet.pos);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-FA] Ritual craft failed for {}:", packet.ritualId, e);
                player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed", e.getMessage()));
            }
        });
        context.setPacketHandled(true);
    }

    // ── main logic ─────────────────────────────────────────────

    private static void tryCraft(ServerPlayer player, ResourceLocation ritualId,
                                  @Nullable ResourceLocation dim, BlockPos pos) {
        ensureClasses();
        ServerLevel level = resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return;
        }

        Object ritual = getRitualById(ritualId, level);
        if (ritual == null) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.ritual_not_found", ritualId.toString()));
            return;
        }

        if (!level.isLoaded(pos)) level.getChunk(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !hephaestusForgeBEClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.forge_not_found"));
            return;
        }

        BlockState state = level.getBlockState(pos);

        // Log ritual result type for diagnostics
        try {
            Object result = invoke(ritual, "result");
            if (result != null && upgradeTierResultClass.isInstance(result)) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] UpgradeTierResult ritual — output depends on main ingredient");
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] Reflection probe failed", e); }

        // Check forge tier
        int forgeTier = getForgeTier(state);
        int requiredTier = getRitualRequiredTier(ritual);
        if (forgeTier < requiredTier) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.tier_insufficient", requiredTier, forgeTier));
            return;
        }

        // UpgradeTierResult: FA's checkConditions requires EXACT tier match
        try {
            Object result = invoke(ritual, "result");
            if (result != null && upgradeTierResultClass.isInstance(result)) {
                int upgradeReqTier = readUpgradeRequiredTier(result);
                if (upgradeReqTier >= 0 && forgeTier != upgradeReqTier) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.fa.error.tier_exact_required", upgradeReqTier, forgeTier));
                    return;
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] Reflection probe failed", e); }

        ResourceKey<Level> altarDim = level.dimension();

        // Check ritual manager active
        Object ritualManager = invoke(be, "getRitualManager");
        if (ritualManager == null) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.ritual_manager"));
            return;
        }
        try {
            Boolean active = (Boolean) getMethod(ritualManagerClass, "isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) {
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
                return;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

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
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Enhancer check failed", e);
            player.sendSystemMessage(Component.translatable(
                    "rsi.fa.error.enhancer_check_failed", e.toString()));
            return;
        }

        // Gather needed items
        List<ItemStack> needed = collectNeededItems(ritual);
        if (needed.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.no_materials_required"));
            return;
        }

        // Count available materials
        INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, altarDim, pos);

        // Pre-check essences per-type before material extraction
        if (!checkEssences(player, ritual, be, ritualManager)) return;

        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, network);

        // Try auto-crafting if items are missing
        if (RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) {
            List<ItemStack> allAvailable = available.entrySet().stream()
                    .map(e -> {
                        ItemStack s = new ItemStack(e.getKey().item(), e.getValue());
                        if (e.getKey().tag() != null) {
                            try { s.setTag(net.minecraft.nbt.TagParser.parseTag(e.getKey().tag())); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed for key {}: {}", e.getKey(), ex.toString()); }
                        }
                        return s;
                    })
                    .toList();
            List<String> missing = new ArrayList<>();
            List<ResourceLocation> autoSteps = CraftingResolver.resolveStepsForStacks(needed, allAvailable, level, missing);

            if (!missing.isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials", String.join(", ", missing)));
                return;
            }

            // Execute auto-crafting steps if needed
            if (!autoSteps.isEmpty() && network != null) {
                player.displayClientMessage(Component.translatable("rsi.generic.info.auto_crafting", autoSteps.size()), true);
                if (!CraftPacketUtils.executeCraftingSteps(player, autoSteps, network)) {
                    player.sendSystemMessage(Component.translatable("rsi.generic.error.auto_craft_failed"));
                    return;
                }
            }
        }

        // Find pedestals
        List<Object> pedestals = findPedestals(ritualManager, level, pos);
        if (pedestals == null) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.pedestal_not_found"));
            return;
        }

        // Get pedestals from ritual manager
        List<Object> emptyPedestals = new ArrayList<>();
        for (Object ped : pedestals) {
            try {
                ItemStack stack = (ItemStack) getMethod(pedestalBEClass, "getStack").invoke(ped);
                if (stack.isEmpty()) {
                    emptyPedestals.add(ped);
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }

        // Extract and place items
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
        if (totalSlotsNeeded > emptyPedestals.size()) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.pedestals_insufficient", totalSlotsNeeded, emptyPedestals.size()));
            return;
        }

        // Phase 1: reserve all items via ledger (no physical extraction yet)
        ExtractionLedger ledger = new ExtractionLedger();
        // Preserve the binding-resolved network from earlier; only fall back
        // to player-inventory scan if no binding was found.
        if (network == null) {
            network = RSIntegration.resolveNetworkFromPlayer(player);
        }

        ItemStack mainTemplate = ItemStack.EMPTY;
        if (mainIng != null && !mainIng.isEmpty()) {
            mainTemplate = ensureMaterialAvailable(player, altarDim, pos, mainIng, 1, ledger);
            if (mainTemplate.isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(mainIng)));
                return;
            }
        }

        List<ItemStack> inputTemplates = new ArrayList<>();
        for (int i = 0; i < inputEntryCount; i++) {
            Object ri = inputs.get(i);
            Ingredient ing = (Ingredient) invoke(ri, "ingredient");
            int amt = inputAmounts[i];
            if (ing == null) continue;
            ItemStack t = ensureMaterialAvailable(player, altarDim, pos, ing, amt, ledger);
            if (t.isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(ing)));
                return;
            }
            inputTemplates.add(t);
        }

        // Phase 2: place items BEFORE committing ledger so we can rollback on failure
        List<Object> filledPedestals = new ArrayList<>();
        try {
            if (!mainTemplate.isEmpty()) {
                int mainSlot = getMainSlot();
                setForgeSlot(be, mainSlot, mainTemplate);
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
                    Object ped = emptyPedestals.get(pedIdx++);
                    getMethod(pedestalBEClass, "setStackAndSync", ItemStack.class)
                            .invoke(ped, single);
                    filledPedestals.add(ped);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Placement failed for ritual {}:", ritualId, e);
            rollbackAll(player, be, filledPedestals, network);
            ledger.rollback(player);
            player.sendSystemMessage(Component.translatable("rsi.generic.error.prepare_failed", e.getMessage()));
            return;
        }

        // Populate cachedIngredients in FA's RitualManager.
        // Clear non-filled pedestals first so checkIngredients' "no leftovers"
        // rule doesn't trip on pre-existing items from other pedestals.
        try {
            Object curEssences = getMethod(hephaestusForgeBEClass, "getEssences").invoke(be);
            Method updateIngredient = getMethod(ritualManagerClass, "updateIngredient",
                    pedestalBEClass, ItemStack.class, essencesDefinitionClass);
            for (Object ped : pedestals) {
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
            RSIntegrationMod.LOGGER.debug("[RSI-FA] cachedIngredients/updateValidRitual failed: {}", e.toString());
        }

        // Phase 3: require a RitualStarterItem (gavel/hammer) — FA
        // enforces this through its right-click handler; calling
        // tryStartRitual directly bypasses that requirement.
        final ItemStack starterStack = findRitualStarterItem(player, network);
        if (starterStack.isEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] tryCraft: no usable RitualStarterItem in inventory or RS");
            player.sendSystemMessage(Component.translatable("rsi.fa.error.missing_starter_item"));
            rollbackAll(player, be, filledPedestals, network);
            ledger.rollback(player);
            return;
        }

        // Phase 4: start the ritual BEFORE committing ledger
        try {
            Object essenceMgr = getMethod(hephaestusForgeBEClass, "getEssenceManager").invoke(be);
            Object essencesStorage = getMethod(essenceManagerClass, "getStorage").invoke(essenceMgr);
            BooleanConsumerProxy callback = new BooleanConsumerProxy(
                    player, be, filledPedestals, network, starterStack);
            Object proxy = Proxy.newProxyInstance(
                    booleanConsumerClass.getClassLoader(),
                    new Class<?>[]{booleanConsumerClass},
                    callback);

            getMethod(ritualManagerClass, "tryStartRitual", essencesStorageClass, booleanConsumerClass)
                    .invoke(ritualManager, essencesStorage, proxy);

            if (callback.rejected) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] tryStartRitual: ritual rejected by forge");
                rollbackAll(player, be, filledPedestals, network);
                ledger.rollback(player);
                returnStarterToSource(starterStack);
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_rejected"));
                return;
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable root = e.getCause() != null ? e.getCause() : e;
            RSIntegrationMod.LOGGER.error("[RSI-FA] tryStartRitual failed — forge rejected: {}", root.toString());
            rollbackAll(player, be, filledPedestals, network);
            ledger.rollback(player);
            returnStarterToSource(starterStack);
            player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed", root.getMessage()));
            return;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Failed to start ritual {}:", ritualId, e);
            rollbackAll(player, be, filledPedestals, network);
            ledger.rollback(player);
            returnStarterToSource(starterStack);
            player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed", e.getMessage()));
            return;
        }

        // Phase 4: commit — ritual started successfully, now extract items from RS
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Ledger commit failed for ritual {}", ritualId);
            rollbackAll(player, be, filledPedestals, network);
            returnStarterToSource(starterStack);
            player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed", "commit failed"));
            return;
        }

        // Only consume the starter AFTER everything succeeded
        consumeRitualStarterUse(starterStack, player);

        Object result = invoke(invoke(ritual, "result"), "getResult");
        String resultName = "???";
        if (result instanceof ItemStack rs && !rs.isEmpty()) {
            resultName = rs.getDisplayName().getString();
        }
        player.displayClientMessage(Component.translatable("rsi.fa.info.ritual_started", resultName), true);
        RSIntegrationMod.LOGGER.debug("[RSI-FA] Player {} started FA ritual '{}'",
                player.getName().getString(), ritualId);
    }

    // ── helpers ────────────────────────────────────────────────

    private static List<ItemStack> collectNeededItems(Object ritual) {
        List<ItemStack> result = new ArrayList<>();
        // Main ingredient
        Ingredient mainIng = (Ingredient) invoke(ritual, "mainIngredient");
        if (mainIng != null) {
            ItemStack[] options = mainIng.getItems();
            if (options.length > 0 && !options[0].isEmpty()) {
                result.add(options[0].copyWithCount(1));
            }
        }
        // Ritual inputs
        List<?> inputs = invokeList(ritual, "inputs");
        if (inputs != null) {
            for (Object ri : inputs) {
                Ingredient ing = (Ingredient) invoke(ri, "ingredient");
                int amt = (int) invoke(ri, "amount");
                if (ing != null) {
                    ItemStack[] options = ing.getItems();
                    if (options.length > 0 && !options[0].isEmpty()) {
                        result.add(options[0].copyWithCount(amt));
                    }
                }
            }
        }
        return result;
    }

    private static int getForgeTier(BlockState state) {
        // Try reading numeric "tier" property from block state via reflection
        try {
            for (net.minecraft.world.level.block.state.properties.Property<?> prop : state.getProperties()) {
                if (prop.getName().equals("tier")) {
                    Comparable<?> val = state.getValue(prop);
                    if (val instanceof Number n) return n.intValue();
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        return 1;
    }

    private static int getRitualRequiredTier(Object ritual) {
        try {
            Object req = invoke(ritual, "requirements");
            if (req != null) {
                return (int) getMethod(req.getClass(), "tier").invoke(req);
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        return 1;
    }

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
     * Collect {@code EssenceModifier} instances from enhancers placed in the forge.
     * Mirrors what {@code RitualManager.canStartRitual} does before comparing essences.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> collectEnhancerModifiers(Object ritualManager, Object ritual) {
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
            RSIntegrationMod.LOGGER.debug("[RSI-FA] Failed to collect enhancer modifiers: {}", e.toString());
        }
        return modifiers;
    }

    /** @return true if all required essences are available */
    private static boolean checkEssences(ServerPlayer player, Object ritual, Object forge, Object ritualManager) {
        try {
            ensureClasses();
            Object ritualEssences = getMethod(ritualClass, "essences").invoke(ritual);

            // Apply enhancer modifiers to match FA's canStartRitual behavior
            List<Object> enhancerModifiers = collectEnhancerModifiers(ritualManager, ritual);
            Object requiredEssences = ritualEssences;
            if (!enhancerModifiers.isEmpty()) {
                requiredEssences = getMethod(essencesDefinitionClass, "applyModifiers",
                        List.class).invoke(ritualEssences, enhancerModifiers);
            }

            int reqAureal = (int) getMethod(essencesDefinitionClass, "aureal")    .invoke(requiredEssences);
            int reqSouls  = (int) getMethod(essencesDefinitionClass, "souls")     .invoke(requiredEssences);
            int reqBlood  = (int) getMethod(essencesDefinitionClass, "blood")     .invoke(requiredEssences);
            int reqExp    = (int) getMethod(essencesDefinitionClass, "experience").invoke(requiredEssences);

            // Read current essences directly from the forge via EssencesContainer.getEssences()
            Object curEssences = getMethod(hephaestusForgeBEClass, "getEssences").invoke(forge);
            int curAureal = (int) getMethod(essencesDefinitionClass, "aureal")    .invoke(curEssences);
            int curSouls  = (int) getMethod(essencesDefinitionClass, "souls")     .invoke(curEssences);
            int curBlood  = (int) getMethod(essencesDefinitionClass, "blood")     .invoke(curEssences);
            int curExp    = (int) getMethod(essencesDefinitionClass, "experience").invoke(curEssences);

            RSIntegrationMod.LOGGER.debug("[RSI-FA] Essence check: a={}/{}, s={}/{}, b={}/{}, e={}/{}",
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
            RSIntegrationMod.LOGGER.error("[RSI-FA] Essence check failed: {}", e.toString(), e);
            player.sendSystemMessage(Component.translatable("rsi.fa.error.essence_check_failed", e.toString()));
            return false;
        }
    }

    private static List<Object> findPedestals(Object ritualManager, ServerLevel level, BlockPos forgePos) {
        List<Object> pedestals = new ArrayList<>();
        try {
            BlockPos.betweenClosedStream(
                    forgePos.offset(-8, -3, -8),
                    forgePos.offset(8, 3, 8)
            ).forEach(cp -> {
                BlockEntity be = level.getBlockEntity(cp);
                if (be != null && pedestalBEClass.isInstance(be)) {
                    pedestals.add(be);
                }
            });
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Failed to find pedestals", e);
            return null;
        }
        return pedestals;
    }

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
            RSIntegrationMod.LOGGER.debug("[RSI-FA] MAIN_SLOT lookup failed", e);
        }
        return 0;
    }

    private static void setForgeSlot(Object be, int slot, ItemStack stack) {
        // Strategy 1: setStack(int, ItemStack) — FA's actual method name
        try {
            Method m = Reflect.findMethod(be.getClass(), "setStack",
                    new Class<?>[]{int.class, ItemStack.class});
            if (m != null) { m.invoke(be, slot, stack); return; }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] S1 setStack({}) failed: {}", slot, e.toString()); }
        // Strategy 2: setStackInSlot (MCP mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "setStackInSlot",
                    new Class<?>[]{int.class, ItemStack.class});
            if (m != null) { m.invoke(be, slot, stack); return; }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] S2 setStackInSlot({}) failed: {}", slot, e.toString()); }
        // Strategy 3: setItem (Mojang mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "setItem",
                    new Class<?>[]{int.class, ItemStack.class});
            if (m != null) { m.invoke(be, slot, stack); return; }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] S3 setItem({}) failed: {}", slot, e.toString()); }
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
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] S4 IItemHandler set({}) failed: {}", slot, e.toString()); }
        // Strategy 5: inventory / items NonNullList field
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "inventory").orElse(null);
            if (f == null) f = Reflect.findField(be.getClass(), "items").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                var list = (net.minecraft.core.NonNullList<ItemStack>) f.get(be);
                if (slot < list.size()) { list.set(slot, stack.copy()); be.getClass().getMethod("setChanged").invoke(be); }
                return;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] S5 inventory field({}) failed: {}", slot, e.toString()); }
        RSIntegrationMod.LOGGER.warn("[RSI-FA] All slot strategies failed for set slot {}", slot);
    }

    private static ItemStack getForgeSlot(Object be, int slot) {
        // Strategy 1: getStack(int) — FA's actual method name
        try {
            Method m = Reflect.findMethod(be.getClass(), "getStack",
                    new Class<?>[]{int.class});
            if (m != null) return (ItemStack) m.invoke(be, slot);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] S1 getStack({}) failed: {}", slot, e.toString()); }
        // Strategy 2: getStackInSlot (MCP mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "getStackInSlot",
                    new Class<?>[]{int.class});
            if (m != null) return (ItemStack) m.invoke(be, slot);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] S2 getStackInSlot({}) failed: {}", slot, e.toString()); }
        // Strategy 3: getItem (Mojang mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "getItem",
                    new Class<?>[]{int.class});
            if (m != null) return (ItemStack) m.invoke(be, slot);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] S3 getItem({}) failed: {}", slot, e.toString()); }
        // Strategy 4: inventory / items NonNullList field
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "inventory").orElse(null);
            if (f == null) f = Reflect.findField(be.getClass(), "items").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                var list = (net.minecraft.core.NonNullList<ItemStack>) f.get(be);
                if (slot < list.size()) return list.get(slot);
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] S4 inv field({}) failed: {}", slot, e.toString()); }
        return ItemStack.EMPTY;
    }

    // ── material extraction ────────────────────────────────────

    private static ItemStack ensureMaterialAvailable(ServerPlayer player, ResourceKey<Level> altarDim,
                                                      BlockPos altarPos, Ingredient ingredient, int count,
                                                      ExtractionLedger ledger) {
        return CraftPacketUtils.ensureMaterialAvailable(player, altarDim, altarPos, ingredient, count, ledger);
    }

    @Nullable
    private static ServerLevel resolveLevel(MinecraftServer server, @Nullable ResourceLocation dim,
                                            ServerPlayer player) {
        return CraftPacketUtils.resolveLevel(server, dim, player);
    }

    // ── reflection helpers ─────────────────────────────────────

    @Nullable
    private static Object unwrapHolderValue(Object holder) {
        if (holder == null) return null;
        for (String name : new String[]{"value", "m_203334_"}) {
            try {
                Method m = Reflect.findMethod(holder.getClass(), name, new Class<?>[0]);
                if (m != null) {
                    Object v = m.invoke(holder);
                    if (v != null) return v;
                }
            } catch (Exception ignored) {}
        }
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

    @Nullable
    private static Object invoke(Object obj, String methodName) {
        try {
            Method m = Reflect.findMethod(obj.getClass(), methodName, new Class<?>[0]);
            if (m == null) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] invoke: method not found {}.{}",
                        obj.getClass().getName(), methodName);
                return null;
            }
            return m.invoke(obj);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] invoke failed: {}.{} — {}",
                    obj.getClass().getName(), methodName, e.toString());
            return null;
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<?> invokeList(Object obj, String methodName) {
        try {
            Method m = Reflect.findMethod(obj.getClass(), methodName, new Class<?>[0]);
            if (m == null) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] invokeList: method not found {}.{}",
                        obj.getClass().getName(), methodName);
                return null;
            }
            return (List<?>) m.invoke(obj);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] invokeList failed: {}.{} — {}",
                    obj.getClass().getName(), methodName, e.toString());
            return null;
        }
    }

    private static Method getMethod(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        Method m = Reflect.findMethod(clazz, name, paramTypes);
        if (m == null) throw new NoSuchMethodException(clazz.getName() + "." + name);
        return m;
    }

    // ── RitualStarterItem helpers ──────────────────────────────────

    /**
     * When the starter item was extracted from RS (rather than found in the
     * player inventory), this holds the network so {@link #consumeRitualStarterUse}
     * can re-insert the modified stack.  Cleared after use.
     */
    @javax.annotation.Nullable
    private static INetwork starterFromRSNetwork;

    /**
     * Finds a valid RitualStarterItem (gavel/hammer) that has at least one
     * remaining use.  Checks the player inventory first, then falls back to
     * extracting from the RS network.  FA requires a right-click with such
     * an item to start any ritual — calling {@code tryStartRitual} directly
     * bypasses that requirement.
     */
    @javax.annotation.Nullable
    private static ItemStack findRitualStarterItem(ServerPlayer player,
                                                    @Nullable INetwork network) {
        ensureClasses();
        if (ritualStarterItemClass == null) return ItemStack.EMPTY;

        // 1. Player inventory + offhand
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && ritualStarterItemClass.isInstance(stack.getItem())
                    && canStartRitual(stack))
                return stack;
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && ritualStarterItemClass.isInstance(offhand.getItem())
                && canStartRitual(offhand))
            return offhand;

        // 2. RS network fallback
        if (network != null) {
            try {
                var cacheList = network.getItemStorageCache().getList();
                if (cacheList != null) {
                    for (var entry : cacheList.getStacks()) {
                        ItemStack rsStack = entry.getStack();
                        if (rsStack.isEmpty()) continue;
                        if (!ritualStarterItemClass.isInstance(rsStack.getItem())) continue;
                        if (!canStartRitual(rsStack)) continue;

                        ItemStack req = rsStack.copy();
                        req.setCount(1);
                        ItemStack extracted = network.extractItem(req, 1,
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                        if (!extracted.isEmpty()) {
                            starterFromRSNetwork = network;
                            return extracted;
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] RS starter search failed: {}", e.toString());
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean canStartRitual(ItemStack stack) {
        try {
            return (boolean) getMethod(ritualStarterItemClass,
                    "canStartRitual", ItemStack.class).invoke(stack.getItem(), stack);
        } catch (Exception e) {
            return false;
        }
    }

    private static void consumeRitualStarterUse(ItemStack starterStack, ServerPlayer player) {
        if (player.isCreative()) return;
        String source = starterFromRSNetwork != null ? "RS" : "inventory";
        try {
            Object item = starterStack.getItem();
            int remaining = (int) getMethod(ritualStarterItemClass,
                    "getRemainingUses", ItemStack.class).invoke(item, starterStack);
            RSIntegrationMod.LOGGER.info("[RSI-FA] Starter '{}' uses before: {} (source: {})",
                    starterStack.getHoverName().getString(), remaining, source);
            if (remaining > 0) {
                int newRemaining = remaining - 1;
                getMethod(ritualStarterItemClass,
                        "setRemainingUses", ItemStack.class, int.class)
                        .invoke(item, starterStack, newRemaining);
                RSIntegrationMod.LOGGER.info("[RSI-FA] Starter '{}' uses after: {}",
                        starterStack.getHoverName().getString(), newRemaining);
            } else {
                RSIntegrationMod.LOGGER.warn("[RSI-FA] Starter '{}' has {} remaining uses — cannot consume",
                        starterStack.getHoverName().getString(), remaining);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] consumeRitualStarterUse failed for '{}': {}",
                    starterStack.getHoverName().getString(), e.toString());
        }
        // Re-insert if the starter was extracted from RS
        INetwork net = starterFromRSNetwork;
        if (net != null) {
            starterFromRSNetwork = null;
            net.insertItem(starterStack, starterStack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        }
    }

    // ── rollback ───────────────────────────────────────────────

    private static void rollbackAll(ServerPlayer player, Object forge,
                                     List<Object> filledPedestals, @Nullable INetwork network) {
        for (Object ped : filledPedestals) {
            try {
                ItemStack stack = (ItemStack) getMethod(pedestalBEClass, "getStack").invoke(ped);
                if (stack != null && !stack.isEmpty()) {
                    if (network != null) {
                        network.insertItem(stack, stack.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    } else {
                        ItemHandlerHelper.giveItemToPlayer(player, stack);
                    }
                }
                getMethod(pedestalBEClass, "clearStack", Level.class, boolean.class)
                        .invoke(ped, null, false);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
        try {
            int mainSlot = hephaestusForgeBEClass.getField("MAIN_SLOT").getInt(null);
            ItemStack stack = getForgeSlot(forge, mainSlot);
            if (!stack.isEmpty()) {
                if (network != null) {
                    network.insertItem(stack, stack.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                } else {
                    ItemHandlerHelper.giveItemToPlayer(player, stack);
                }
            }
            setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
    }

    // ── BooleanConsumer proxy for ritual callback ──────────────

    private static final class BooleanConsumerProxy implements InvocationHandler {
        private final ServerPlayer player;
        private final Object forge;
        private final List<Object> filledPedestals;
        @Nullable private final INetwork network;
        @javax.annotation.Nullable private final ItemStack starterStack;
        boolean rejected;

        BooleanConsumerProxy(ServerPlayer player, Object forge,
                             List<Object> filledPedestals, @Nullable INetwork network) {
            this(player, forge, filledPedestals, network, null);
        }

        BooleanConsumerProxy(ServerPlayer player, Object forge,
                             List<Object> filledPedestals, @Nullable INetwork network,
                             @javax.annotation.Nullable ItemStack starterStack) {
            this.player = player;
            this.forge = forge;
            this.filledPedestals = filledPedestals;
            this.network = network;
            this.starterStack = starterStack;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("accept") && args.length == 1 && args[0] instanceof Boolean success) {
                if (Boolean.TRUE.equals(success)) {
                    // Defer starter consumption — consumeRitualStarterUse()
                    // is called only after ledger commit succeeds.
                } else {
                    rejected = true;
                }
            }
            return null;
        }
    }

    private static void returnStarterToSource(ItemStack stack) {
        if (stack.isEmpty()) return;
        INetwork net = starterFromRSNetwork;
        if (net != null) {
            starterFromRSNetwork = null;
            net.insertItem(stack, stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        }
    }
}
