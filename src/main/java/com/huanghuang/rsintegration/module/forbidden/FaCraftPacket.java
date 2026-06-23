package com.huanghuang.rsintegration.module.forbidden;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.integration.RSIntegration;
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

        // Check forge tier
        int forgeTier = getForgeTier(state);
        int requiredTier = getRitualRequiredTier(ritual);
        if (forgeTier < requiredTier) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.tier_insufficient", requiredTier, forgeTier));
            return;
        }

        ResourceKey<Level> altarDim = level.dimension();

        // Check ritual manager active
        Object ritualManager = invoke(be, "getRitualManager");
        if (ritualManager == null) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.ritual_manager"));
            return;
        }
        try {
            Boolean active = (Boolean) ritualManager.getClass().getMethod("isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) {
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
                return;
            }
        } catch (Exception ignored) {}

        // Gather needed items
        List<ItemStack> needed = collectNeededItems(ritual);
        if (needed.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.no_materials_required"));
            return;
        }

        // Count available materials
        INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, altarDim, pos);
        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, network);

        // Try auto-crafting if items are missing
        if (RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) {
            List<ItemStack> allAvailable = available.entrySet().stream()
                    .map(e -> {
                        ItemStack s = new ItemStack(e.getKey().item(), e.getValue());
                        if (e.getKey().tag() != null) {
                            try { s.setTag(net.minecraft.nbt.TagParser.parseTag(e.getKey().tag())); } catch (Exception ignored) {}
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
                ItemStack stack = (ItemStack) ped.getClass().getMethod("getStack").invoke(ped);
                if (stack.isEmpty()) {
                    emptyPedestals.add(ped);
                }
            } catch (Exception ignored) {}
        }

        // Extract and place items
        List<?> inputs = invokeList(ritual, "inputs");
        Ingredient mainIng = (Ingredient) invoke(ritual, "mainIngredient");
        int inputCount = inputs != null ? inputs.size() : 0;

        if (inputCount > emptyPedestals.size()) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.pedestals_insufficient", inputCount, emptyPedestals.size()));
            return;
        }

        // Phase 1: reserve all items via ledger (no physical extraction yet)
        ExtractionLedger ledger = new ExtractionLedger();
        network = RSIntegration.resolveNetworkFromPlayer(player);

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
        for (int i = 0; i < inputCount; i++) {
            Object ri = inputs.get(i);
            Ingredient ing = (Ingredient) invoke(ri, "ingredient");
            int amt = (int) invoke(ri, "amount");
            if (ing == null) continue;
            ItemStack t = ensureMaterialAvailable(player, altarDim, pos, ing, amt, ledger);
            if (t.isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(ing)));
                return;
            }
            inputTemplates.add(t);
        }

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Ledger commit failed for ritual {}", ritualId);
            player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed", "commit failed"));
            return;
        }

        // Phase 3: place items in forge + pedestals
        List<Object> filledPedestals = new ArrayList<>();
        try {
            if (!mainTemplate.isEmpty()) {
                int mainSlot = hephaestusForgeBEClass.getField("MAIN_SLOT").getInt(null);
                setForgeSlot(be, mainSlot, mainTemplate);
            }

            int pedIdx = 0;
            for (int i = 0; i < inputCount && i < inputTemplates.size(); i++) {
                Object ped = emptyPedestals.get(pedIdx++);
                ped.getClass().getMethod("setStack", ItemStack.class).invoke(ped, inputTemplates.get(i));
                filledPedestals.add(ped);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Placement failed for ritual {}:", ritualId, e);
            rollbackAll(player, be, filledPedestals, network);
            player.sendSystemMessage(Component.translatable("rsi.generic.error.prepare_failed", e.getMessage()));
            return;
        }

        // Phase 4: start the ritual
        try {
            Object essencesStorage = invoke(invoke(ritual, "essences"), "mutable");
            BooleanConsumerProxy callback = new BooleanConsumerProxy(player, be, filledPedestals, network);
            Object proxy = Proxy.newProxyInstance(
                    booleanConsumerClass.getClassLoader(),
                    new Class<?>[]{booleanConsumerClass},
                    callback);

            ritualManager.getClass()
                    .getMethod("tryStartRitual", essencesStorageClass, booleanConsumerClass)
                    .invoke(ritualManager, essencesStorage, proxy);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Failed to start ritual {}:", ritualId, e);
            rollbackAll(player, be, filledPedestals, network);
            player.sendSystemMessage(Component.translatable("rsi.fa.error.craft_failed", e.getMessage()));
            return;
        }

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
        } catch (Exception ignored) {}
        return 1;
    }

    private static int getRitualRequiredTier(Object ritual) {
        try {
            Object req = invoke(ritual, "requirements");
            if (req != null) {
                return (int) req.getClass().getMethod("tier").invoke(req);
            }
        } catch (Exception ignored) {}
        return 1;
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

    private static void setForgeSlot(Object be, int slot, ItemStack stack) {
        try {
            be.getClass().getMethod("setStackInSlot", int.class, ItemStack.class).invoke(be, slot, stack);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] Failed to set forge slot {}: {}", slot, e.getMessage());
        }
    }

    private static ItemStack getForgeSlot(Object be, int slot) {
        try {
            return (ItemStack) be.getClass().getMethod("getStackInSlot", int.class).invoke(be, slot);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
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
    private static Object invoke(Object obj, String recordComponent) {
        try {
            return obj.getClass().getMethod(recordComponent).invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<?> invokeList(Object obj, String recordComponent) {
        try {
            return (List<?>) obj.getClass().getMethod(recordComponent).invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    // ── rollback ───────────────────────────────────────────────

    private static void rollbackAll(ServerPlayer player, Object forge,
                                     List<Object> filledPedestals, @Nullable INetwork network) {
        for (Object ped : filledPedestals) {
            try {
                ItemStack stack = (ItemStack) ped.getClass().getMethod("getStack").invoke(ped);
                if (stack != null && !stack.isEmpty()) {
                    if (network != null) {
                        network.insertItem(stack, stack.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    } else {
                        ItemHandlerHelper.giveItemToPlayer(player, stack);
                    }
                }
                ped.getClass().getMethod("setStack", ItemStack.class).invoke(ped, ItemStack.EMPTY);
            } catch (Exception ignored) {}
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
        } catch (Exception ignored) {}
    }

    // ── BooleanConsumer proxy for ritual callback ──────────────

    private static final class BooleanConsumerProxy implements InvocationHandler {
        private final ServerPlayer player;
        private final Object forge;
        private final List<Object> filledPedestals;
        @Nullable private final INetwork network;

        BooleanConsumerProxy(ServerPlayer player, Object forge,
                             List<Object> filledPedestals, @Nullable INetwork network) {
            this.player = player;
            this.forge = forge;
            this.filledPedestals = filledPedestals;
            this.network = network;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("accept") && args.length == 1 && args[0] instanceof Boolean success) {
                if (Boolean.FALSE.equals(success)) {
                    rollbackAll(player, forge, filledPedestals, network);
                    player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_rejected"));
                }
            }
            return null;
        }
    }
}
