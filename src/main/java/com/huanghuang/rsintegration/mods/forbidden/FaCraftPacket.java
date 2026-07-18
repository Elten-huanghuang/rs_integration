package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.reflection.probes.FAReflection;
import com.huanghuang.rsintegration.util.ChunkUtils;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Supplier;

public final class FaCraftPacket {

    private final ResourceLocation ritualId;
    private final ResourceLocation dim;
    private final BlockPos pos;

    // ── static init ────────────────────────────────────────────

    private static Object getRitualById(ResourceLocation id, ServerLevel level) {
        return FaRitualHelper.getRitualById(id, level);
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
        if (player == null || player instanceof net.minecraftforge.common.util.FakePlayer) {
            context.setPacketHandled(true);
            return;
        }        context.enqueueWork(() -> {
            try {
                tryCraft(player, packet.ritualId, packet.dim, packet.pos);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-FA] Ritual craft failed for {}:", packet.ritualId, e);
                player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed"));
            }
        });
        context.setPacketHandled(true);
    }

    // ── main logic ─────────────────────────────────────────────

    private static void tryCraft(ServerPlayer player, ResourceLocation ritualId,
                                  ResourceLocation dim, BlockPos pos) {
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

        // Verify binding before accessing remote machine at client-supplied coords
        if (dim != null) {
            ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim);
            if (!AltarBindingRegistry.isBound(key, pos, player)) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.not_bound"));
                return;
            }
        }

        ChunkUtils.loadChunk(level, pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !FAReflection.hephaestusForgeBEClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.forge_not_found"));
            return;
        }

        BlockState state = level.getBlockState(pos);

        // Log ritual result type for diagnostics
        try {
            Object result = FaRitualHelper.invoke(ritual, "result");
            if (result != null && FAReflection.upgradeTierResultClass.isInstance(result)) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] UpgradeTierResult ritual — output depends on main ingredient");
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] Reflection probe failed", e); }

        // Check forge tier
        int forgeTier = FaRitualHelper.getForgeTier(state, be);
        int requiredTier = FaRitualHelper.getRitualRequiredTier(ritual);
        if (forgeTier < requiredTier) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.tier_insufficient", requiredTier, forgeTier));
            return;
        }

        // UpgradeTierResult: FA's checkConditions requires EXACT tier match
        try {
            Object result = FaRitualHelper.invoke(ritual, "result");
            if (result != null && FAReflection.upgradeTierResultClass.isInstance(result)) {
                int upgradeReqTier = FaRitualHelper.readUpgradeRequiredTier(result);
                if (upgradeReqTier >= 0 && forgeTier != upgradeReqTier) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.fa.error.tier_exact_required", upgradeReqTier, forgeTier));
                    return;
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FA] Reflection probe failed", e); }

        ResourceKey<Level> altarDim = level.dimension();

        // Check ritual manager active
        Object ritualManager = FaRitualHelper.invoke(be, "getRitualManager");
        if (ritualManager == null) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.ritual_manager"));
            return;
        }
        try {
            Boolean active = (Boolean) Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "isRitualActive", "isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) {
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
                return;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

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
        if (!FaRitualHelper.checkEssences(player, ritual, be, ritualManager)) return;

        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, network);

        // Try auto-crafting if items are missing
        if (RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) {
            List<ItemStack> allAvailable = available.entrySet().stream()
                    .map(e -> {
                        ItemStack s = new ItemStack(e.getKey().item(), e.getValue());
                        if (e.getKey().tag() != null) {
                            try { s.setTag(net.minecraft.nbt.TagParser.parseTag(e.getKey().tag())); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed for key {}", e.getKey(), ex); }
                        }
                        return s;
                    })
                    .toList();
            List<String> missing = new ArrayList<>();
            List<ResourceLocation> autoSteps = CraftingResolver.resolveStepsForStacks(needed, allAvailable, level, missing);

            if (!missing.isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials", CraftPacketUtils.formatMissingSummary(missing)));
                return;
            }

            if (!autoSteps.isEmpty() && network != null) {
                player.displayClientMessage(Component.translatable("rsi.generic.info.auto_crafting", autoSteps.size()), true);
                List<CraftingResolver.ResolutionStep> wrapped = new ArrayList<>();
                for (ResourceLocation id : autoSteps) {
                    wrapped.add(new CraftingResolver.ResolutionStep(id, ModType.GENERIC,
                            new ResourceLocation("minecraft:crafting")));
                }
                if (!CraftPacketUtils.executeCraftingSteps(player, wrapped, network)) {
                    player.sendSystemMessage(Component.translatable("rsi.generic.error.auto_craft_failed"));
                    return;
                }
            }
        }

        // Find pedestals
        List<Object> pedestals = FaRitualHelper.findPedestals(pos, level);
        if (pedestals == null) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.pedestal_not_found"));
            return;
        }

        List<Object> emptyPedestals = new ArrayList<>();
        for (Object ped : pedestals) {
            try {
                ItemStack stack = (ItemStack) Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "getStack", "getStack").invoke(ped);
                if (stack.isEmpty()) {
                    emptyPedestals.add(ped);
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }

        // Extract and place items
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
        if (totalSlotsNeeded > emptyPedestals.size()) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.pedestals_insufficient", totalSlotsNeeded, emptyPedestals.size()));
            return;
        }

        // Phase 1: reserve all items via ledger
        if (network == null) {
            network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        }

        List<Object> filledPedestals = new ArrayList<>();
        ItemStack mainTemplate = ItemStack.EMPTY;
        List<ItemStack> inputTemplates = new ArrayList<>();
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            if (mainIng != null && !mainIng.isEmpty()) {
                mainTemplate = ensureMaterialAvailable(player, altarDim, pos, mainIng, 1, ledger);
                if (mainTemplate.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials",
                            CraftPacketUtils.describeIngredient(mainIng)));
                    return;
                }
            }

            for (int i = 0; i < inputEntryCount; i++) {
                Object ri = inputs.get(i);
                Ingredient ing = (Ingredient) FaRitualHelper.invoke(ri, "ingredient");
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

            // Phase 2: place items BEFORE committing ledger
            try {
                if (!mainTemplate.isEmpty()) {
                    int mainSlot = FaRitualHelper.getMainSlot();
                    FaRitualHelper.setForgeSlot(be, mainSlot, mainTemplate);
                }

                int pedIdx = 0;
                for (int i = 0; i < inputEntryCount && i < inputTemplates.size(); i++) {
                    ItemStack template = inputTemplates.get(i);
                    int amt = inputAmounts[i];
                    for (int p = 0; p < amt && p < template.getCount(); p++) {
                        ItemStack single = template.copy();
                        single.setCount(1);
                        Object ped = emptyPedestals.get(pedIdx++);
                        Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "setStackAndSync", "setStackAndSync", ItemStack.class)
                                .invoke(ped, single);
                        filledPedestals.add(ped);
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-FA] Placement failed for ritual {}:", ritualId, e);
                rollbackAll(player, be, filledPedestals, network);
                player.sendSystemMessage(Component.translatable("rsi.generic.error.prepare_failed"));
                return;
            }

            // Populate cachedIngredients
            try {
                Object curEssences = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getEssences", "getEssences").invoke(be);
                Method updateIngredient = Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "updateIngredient", "updateIngredient",
                        FAReflection.pedestalBEClass, ItemStack.class, FAReflection.essencesDefinitionClass);
                for (Object ped : pedestals) {
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
                RSIntegrationMod.LOGGER.debug("[RSI-FA] cachedIngredients/updateValidRitual failed", e);
            }

            // Phase 3: require a RitualStarterItem
            final FaRitualHelper.StarterResult starterResult = FaRitualHelper.findRitualStarterItem(player, network);
            final ItemStack starterStack = starterResult.stack();
            final INetwork starterNetwork = starterResult.sourceNetwork();
            if (starterStack.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] tryCraft: no usable RitualStarterItem in inventory or RS");
                player.sendSystemMessage(Component.translatable("rsi.fa.error.missing_starter_item"));
                rollbackAll(player, be, filledPedestals, network);
                return;
            }

            // Phase 4: commit ledger first
            if (!ledger.commit(network, player)) {
                RSIntegrationMod.LOGGER.error("[RSI-FA] Ledger commit failed for ritual {}", ritualId);
                rollbackAll(player, be, filledPedestals, network);
                FaRitualHelper.returnStarterToSource(starterStack, player, starterNetwork);
                player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed"));
                return;
            }

            // Phase 5: start the ritual AFTER committing ledger
            try {
                Object essenceMgr = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getEssenceManager", "getEssenceManager").invoke(be);
                Object essencesStorage = Reflect.getMethodOrThrow(FAReflection.essenceManagerClass, "getStorage", "getStorage").invoke(essenceMgr);
                BooleanConsumerProxy callback = new BooleanConsumerProxy(
                        player, be, filledPedestals, network, starterStack);
                Object proxy = Proxy.newProxyInstance(
                        FAReflection.booleanConsumerClass.getClassLoader(),
                        new Class<?>[]{FAReflection.booleanConsumerClass},
                        callback);

                Reflect.getMethodOrThrow(FAReflection.ritualManagerClass, "tryStartRitual", "tryStartRitual", FAReflection.essencesStorageClass, FAReflection.booleanConsumerClass)
                        .invoke(ritualManager, essencesStorage, proxy);

                if (callback.rejected) {
                    RSIntegrationMod.LOGGER.debug("[RSI-FA] tryStartRitual: ritual rejected by forge");
                    rollbackAll(player, be, filledPedestals, network);
                    FaRitualHelper.returnStarterToSource(starterStack, player, starterNetwork);
                    player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_rejected"));
                    return;
                }
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable root = e.getCause() != null ? e.getCause() : e;
                RSIntegrationMod.LOGGER.error("[RSI-FA] tryStartRitual failed — forge rejected", root);
                rollbackAll(player, be, filledPedestals, network);
                FaRitualHelper.returnStarterToSource(starterStack, player, starterNetwork);
                player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed"));
                return;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-FA] Failed to start ritual {}:", ritualId, e);
                rollbackAll(player, be, filledPedestals, network);
                FaRitualHelper.returnStarterToSource(starterStack, player, starterNetwork);
                player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed"));
                return;
            }

            // Only consume the starter AFTER everything succeeded
            FaRitualHelper.consumeRitualStarterUse(starterStack, player, starterNetwork);

            Object result = FaRitualHelper.invoke(FaRitualHelper.invoke(ritual, "result"), "getResult");
            String resultName = "???";
            if (result instanceof ItemStack rs && !rs.isEmpty()) {
                resultName = rs.getDisplayName().getString();
            }
            player.displayClientMessage(Component.translatable("rsi.fa.info.ritual_started", resultName), true);
            RSIntegrationMod.LOGGER.debug("[RSI-FA] Player {} started FA ritual '{}'",
                    player.getName().getString(), ritualId);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private static List<ItemStack> collectNeededItems(Object ritual) {
        List<ItemStack> result = new ArrayList<>();
        Ingredient mainIng = (Ingredient) FaRitualHelper.invoke(ritual, "mainIngredient");
        if (mainIng != null) {
            ItemStack[] options = mainIng.getItems();
            if (options.length > 0 && !options[0].isEmpty()) {
                result.add(options[0].copyWithCount(1));
            }
        }
        List<?> inputs = FaRitualHelper.invokeList(ritual, "inputs");
        if (inputs != null) {
            for (Object ri : inputs) {
                Ingredient ing = (Ingredient) FaRitualHelper.invoke(ri, "ingredient");
                int amt = (int) FaRitualHelper.invoke(ri, "amount");
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

    // ── material extraction ────────────────────────────────────

    private static ItemStack ensureMaterialAvailable(ServerPlayer player, ResourceKey<Level> altarDim,
                                                      BlockPos altarPos, Ingredient ingredient, int count,
                                                      ExtractionLedger ledger) {
        return CraftPacketUtils.ensureMaterialAvailable(player, altarDim, altarPos, ingredient, count, ledger);
    }

    private static ServerLevel resolveLevel(MinecraftServer server, ResourceLocation dim,
                                            ServerPlayer player) {
        return CraftPacketUtils.resolveLevel(server, dim, player);
    }

    // ── rollback ───────────────────────────────────────────────

    private static void rollbackAll(ServerPlayer player, Object forge,
                                     List<Object> filledPedestals, INetwork network) {
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
                    } else {
                        ItemHandlerHelper.giveItemToPlayer(player, stack);
                    }
                }
                Reflect.getMethodOrThrow(FAReflection.pedestalBEClass, "clearStack", "clearStack", Level.class, boolean.class)
                        .invoke(ped, null, false);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
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
                } else {
                    ItemHandlerHelper.giveItemToPlayer(player, stack);
                }
            }
            FaRitualHelper.setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
    }

    // ── BooleanConsumer proxy for ritual callback ──────────────

    private static final class BooleanConsumerProxy implements InvocationHandler {
        private final ServerPlayer player;
        private final Object forge;
        private final List<Object> filledPedestals;
        private final INetwork network;
        private final ItemStack starterStack;
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
}
