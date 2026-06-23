package com.huanghuang.rsintegration.batch.delegate;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry;
import com.huanghuang.rsintegration.integration.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

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
            essencesDefinitionClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssencesDefinition");
            essencesStorageClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.essence.EssencesStorage");
            ritualManagerClass = Class.forName(
                    "com.stal111.forbidden_arcanus.common.block.entity.forge.ritual.RitualManager");
            booleanConsumerClass = Class.forName(
                    "it.unimi.dsi.fastutil.booleans.BooleanConsumer");
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

        // Resolve ritual manager
        try {
            ritualManager = forge.getClass().getMethod("getRitualManager").invoke(forge);
        } catch (Exception e) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.ritual_manager"));
            return false;
        }
        if (ritualManager == null) {
            player.sendSystemMessage(Component.translatable("rsi.fa.error.ritual_manager"));
            return false;
        }

        // Validate idle
        try {
            Boolean active = (Boolean) ritualManager.getClass().getMethod("isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) {
                player.sendSystemMessage(Component.translatable("rsi.fa.warn.ritual_active"));
                return false;
            }
        } catch (Exception ignored) {}

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

        // Re-validate idle
        try {
            Boolean active = (Boolean) ritualManager.getClass().getMethod("isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) return false;
        } catch (Exception ignored) {}

        this.filledPedestals = new ArrayList<>();

        // Get pedestals from ritual manager
        List<Object> foundPedestals;
        try {
            foundPedestals = findPedestals(player.serverLevel());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Failed to find pedestals", e);
            return false;
        }
        if (foundPedestals == null) return false;

        List<Object> availablePedestals = new ArrayList<>();
        for (Object ped : foundPedestals) {
            try {
                ItemStack stack = (ItemStack) ped.getClass().getMethod("getStack").invoke(ped);
                if (stack.isEmpty()) {
                    availablePedestals.add(ped);
                }
            } catch (Exception ignored) {}
        }

        // Collect recipe requirements
        List<?> inputs = invokeList(ritual, "inputs");
        Ingredient mainIng = (Ingredient) invoke(ritual, "mainIngredient");
        int inputCount = inputs != null ? inputs.size() : 0;

        if (inputCount > availablePedestals.size()) return false;

        // Phase 1: reserve all items via ledger
        ItemStack mainTemplate = ItemStack.EMPTY;
        if (mainIng != null && !mainIng.isEmpty()) {
            mainTemplate = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, mainIng, 1, ledger);
            if (mainTemplate.isEmpty()) return false;
        }

        List<ItemStack> inputTemplates = new ArrayList<>();
        for (int i = 0; i < inputCount; i++) {
            Object ri = inputs.get(i);
            Ingredient ing = (Ingredient) invoke(ri, "ingredient");
            int amt = (int) invoke(ri, "amount");
            if (ing == null) continue;

            ItemStack t = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, amt, ledger);
            if (t.isEmpty()) return false;
            inputTemplates.add(t);
        }

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Ledger commit failed");
            return false;
        }

        // Phase 3: place items
        try {
            if (!mainTemplate.isEmpty()) {
                int mainSlot = hephaestusForgeBEClass.getField("MAIN_SLOT").getInt(null);
                setForgeSlot(forge, mainSlot, mainTemplate);
            }

            int pedIdx = 0;
            for (int i = 0; i < inputCount && i < inputTemplates.size(); i++) {
                Object ped = availablePedestals.get(pedIdx++);
                ped.getClass().getMethod("setStack", ItemStack.class).invoke(ped, inputTemplates.get(i));
                filledPedestals.add(ped);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Placement failed:", e);
            rollbackAll();
            return false;
        }

        this.emptyPedestals = availablePedestals;

        // Phase 4: start the ritual
        try {
            this.essencesStorage = invoke(invoke(ritual, "essences"), "mutable");
            DelegateBooleanConsumer callback = new DelegateBooleanConsumer(this);
            Object proxy = Proxy.newProxyInstance(
                    booleanConsumerClass.getClassLoader(),
                    new Class<?>[]{booleanConsumerClass},
                    callback);

            ritualManager.getClass()
                    .getMethod("tryStartRitual", essencesStorageClass, booleanConsumerClass)
                    .invoke(ritualManager, essencesStorage, proxy);

            if (callback.wasCalled && !callback.accepted) {
                // rollbackAll already called in accept(false) for non-shared mode
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Failed to start ritual:", e);
            rollbackAll();
            return false;
        }

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
        this.usingSharedLedger = true;

        // Re-validate idle
        try {
            Boolean active = (Boolean) ritualManager.getClass().getMethod("isRitualActive").invoke(ritualManager);
            if (Boolean.TRUE.equals(active)) return false;
        } catch (Exception ignored) {}

        this.filledPedestals = new ArrayList<>();

        // Find pedestals
        List<Object> foundPedestals;
        try {
            foundPedestals = findPedestals(player.serverLevel());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Failed to find pedestals", e);
            return false;
        }
        if (foundPedestals == null) return false;

        List<Object> availablePedestals = new ArrayList<>();
        for (Object ped : foundPedestals) {
            try {
                ItemStack stack = (ItemStack) ped.getClass().getMethod("getStack").invoke(ped);
                if (stack.isEmpty()) availablePedestals.add(ped);
            } catch (Exception ignored) {}
        }

        // Materials order: [mainIngredient, input1, input2, ...]
        boolean hasMain = ((Ingredient) invoke(ritual, "mainIngredient")) != null;
        List<?> inputs = invokeList(ritual, "inputs");
        int inputCount = inputs != null ? inputs.size() : 0;

        try {
            int matIdx = 0;
            // Main ingredient → forge main slot
            if (hasMain && matIdx < materials.size()) {
                ItemStack mainStack = materials.get(matIdx++);
                if (!mainStack.isEmpty()) {
                    int mainSlot = hephaestusForgeBEClass.getField("MAIN_SLOT").getInt(null);
                    setForgeSlot(forge, mainSlot, mainStack);
                }
            }

            // Inputs → pedestals
            int pedIdx = 0;
            for (int i = 0; i < inputCount && matIdx < materials.size(); i++) {
                ItemStack stack = materials.get(matIdx++);
                if (stack.isEmpty()) continue;
                Object ped = availablePedestals.get(pedIdx++);
                ped.getClass().getMethod("setStack", ItemStack.class).invoke(ped, stack);
                filledPedestals.add(ped);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Material placement failed:", e);
            rollbackAll();
            return false;
        }

        this.emptyPedestals = availablePedestals;

        // Start the ritual
        try {
            this.essencesStorage = invoke(invoke(ritual, "essences"), "mutable");
            DelegateBooleanConsumer callback = new DelegateBooleanConsumer(this);
            Object proxy = Proxy.newProxyInstance(
                    booleanConsumerClass.getClassLoader(),
                    new Class<?>[]{booleanConsumerClass},
                    callback);

            ritualManager.getClass()
                    .getMethod("tryStartRitual", essencesStorageClass, booleanConsumerClass)
                    .invoke(ritualManager, essencesStorage, proxy);

            if (callback.wasCalled && !callback.accepted) {
                // Shared ledger: don't rollbackAll (caller handles refund).
                // Just remove placed items from machine slots.
                clearFilledPedestals();
                try {
                    int mainSlot = hephaestusForgeBEClass.getField("MAIN_SLOT").getInt(null);
                    setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
                } catch (Exception ignored) {}
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-FA] Failed to start ritual:", e);
            rollbackAll();
            return false;
        }

        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        try {
            if (ritualManager != null) {
                Boolean active = (Boolean) ritualManager.getClass().getMethod("isRitualActive").invoke(ritualManager);
                if (Boolean.TRUE.equals(active)) {
                    ritualEverSeenActive = true;
                    return false;
                }
            }
        } catch (Exception ignored) {}
        return ritualEverSeenActive;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        try {
            int mainSlot = hephaestusForgeBEClass.getField("MAIN_SLOT").getInt(null);
            ItemStack result = (ItemStack) forge.getClass().getMethod("getStackInSlot", int.class).invoke(forge, mainSlot);
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
        if (!usingSharedLedger) {
            rollbackAll();
        } else {
            clearFilledPedestals();
            try {
                int mainSlot = hephaestusForgeBEClass.getField("MAIN_SLOT").getInt(null);
                setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
            } catch (Exception ignored) {}
        }
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
        ritualEverSeenActive = false;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        clearFilledPedestals();
        try {
            int mainSlot = hephaestusForgeBEClass.getField("MAIN_SLOT").getInt(null);
            setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
        } catch (Exception ignored) {}
        ledger = null;
        sharedLedger = null;
        network = null;
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
                    network.insertItem(stack, stack.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                } else if (player != null) {
                    ItemHandlerHelper.giveItemToPlayer(player, stack);
                }
            }
            setForgeSlot(forge, mainSlot, ItemStack.EMPTY);
        } catch (Exception ignored) {}
    }

    private void clearFilledPedestals() {
        if (filledPedestals == null) return;
        for (Object ped : filledPedestals) {
            try {
                ItemStack stack = (ItemStack) ped.getClass().getMethod("getStack").invoke(ped);
                if (stack != null && !stack.isEmpty() && !usingSharedLedger) {
                    if (network != null) {
                        network.insertItem(stack, stack.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    } else if (player != null) {
                        ItemHandlerHelper.giveItemToPlayer(player, stack);
                    }
                }
                ped.getClass().getMethod("setStack", ItemStack.class).invoke(ped, ItemStack.EMPTY);
            } catch (Exception ignored) {}
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
        } catch (Exception ignored) {}
        return 1;
    }

    private static int getRitualRequiredTier(Object ritual) {
        try {
            Object req = invokeStatic(ritual, "requirements");
            if (req != null) {
                return (int) req.getClass().getMethod("tier").invoke(req);
            }
        } catch (Exception ignored) {}
        return 1;
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

    private static void setForgeSlot(Object be, int slot, ItemStack stack) {
        try {
            be.getClass().getMethod("setStackInSlot", int.class, ItemStack.class).invoke(be, slot, stack);
        } catch (Exception ignored) {}
    }

    private static ItemStack getForgeSlot(Object be, int slot) {
        try {
            return (ItemStack) be.getClass().getMethod("getStackInSlot", int.class).invoke(be, slot);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    // ── Reflection helpers ───────────────────────────────────────

    @Nullable
    private static Object invoke(Object obj, String methodName) {
        try {
            return obj.getClass().getMethod(methodName).invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Object invokeStatic(Object obj, String methodName) {
        try {
            return obj.getClass().getMethod(methodName).invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static List<?> invokeList(Object obj, String methodName) {
        try {
            return (List<?>) obj.getClass().getMethod(methodName).invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    // ── BooleanConsumer proxy for ritual callback ─────────────────

    static final class DelegateBooleanConsumer implements InvocationHandler {
        private final FaBatchDelegate delegate;
        boolean accepted;
        boolean wasCalled;

        DelegateBooleanConsumer(FaBatchDelegate delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("accept") && args.length == 1 && args[0] instanceof Boolean success) {
                wasCalled = true;
                accepted = success;
                if (Boolean.TRUE.equals(success)) {
                    delegate.ritualEverSeenActive = true;
                } else if (!delegate.usingSharedLedger) {
                    delegate.rollbackAll();
                }
            }
            return null;
        }
    }
}
