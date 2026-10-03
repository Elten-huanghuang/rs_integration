package com.huanghuang.rsintegration.mods.summoningrituals;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.PreparationMessageScope;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.plan.MachineCandidateView;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 只负责准备 Summoning Rituals 祭坛库存的终端委托。
 *
 * <p>不切换自动模式、不启动仪式、不捕获世界产物。所有库存访问都经过
 * {@link ForgeCapabilities#ITEM_HANDLER}。</p>
 */
public final class SummoningRitualAltarBatchDelegate extends AbstractBatchDelegate {
    private static final String ALTAR_BE_CLASS =
            "com.almostreliable.summoningrituals.altar.AltarBlockEntity";

    record PreparedMaterial(IngredientSpec spec, ItemStack stack) {}

    private ServerPlayer player;
    private ResourceKey<Level> altarDimension;
    private BlockPos altarPos;
    private Recipe<?> recipe;
    private Ingredient catalyst = Ingredient.EMPTY;
    private List<IngredientSpec> recipeInputs = List.of();
    private List<IngredientSpec> missingMaterials = List.of();
    private List<ItemStack> preparedSnapshot = List.of();
    private int preparedExecutions = 1;
    private final List<SummoningRitualInventoryOps.Inserted> inserted = new ArrayList<>();
    private boolean prepared;
    private String validationFailure = "altar validation failed";

    @Override
    public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, BlockPos pos) {
        return validateAndInit(player, recipeId, dim, pos)
                ? PreparationResult.ready()
                : PreparationResult.retry(validationFailure);
    }

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player,
                                   @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim,
                                   @Nonnull BlockPos pos) {
        this.player = player;
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) return fail("祭坛维度不可用", "rsi.generic.error.dim_not_found");
        this.altarDimension = level.dimension();
        this.machineDim = level.dimension().location();
        this.altarPos = pos.immutable();

        if (!level.isLoaded(pos)) return fail("祭坛区块未加载", "rsi.error.chunk_unloaded");
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || !isAltar(blockEntity)) {
            return fail("目标不是 Summoning Rituals 祭坛", "rsi.summoning_rituals.error.altar_not_found");
        }

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null || !SummoningRitualRecipeHandler.RECIPE_CLASS
                .equals(found.getClass().getName())) {
            return fail("召唤配方不存在或类型错误", "rsi.generic.error.recipe_not_found");
        }
        this.recipe = found;
        this.catalyst = SummoningRitualRecipeHandler.catalyst(found);
        this.recipeInputs = List.copyOf(SummoningRitualRecipeHandler.inputs(found));

        IItemHandler handler = itemHandler(blockEntity);
        if (!SummoningRitualInventoryOps.valid(handler)) {
            return fail("祭坛没有可用的 ITEM_HANDLER", "rsi.summoning_rituals.error.no_item_handler");
        }

        this.preparedSnapshot = snapshot(handler);
        this.missingMaterials = calculateMissing(handler, catalyst, recipeInputs);
        this.preparedExecutions = 1;
        this.inserted.clear();
        this.prepared = false;
        RSIntegrationMod.LOGGER.debug(
                "[RSI-SummoningRituals] Prepared recipe={} altar={} slots={} missing={}",
                recipeId, pos, handler.getSlots(), missingMaterials.size());
        RSIntegrationMod.LOGGER.info(
                "[RSI-SummoningRituals] Recipe roles recipe={} catalyst={} inputs={} handler={} slots={} catalystSlot={}",
                recipeId, catalyst, recipeInputs, handler.getClass().getName(), handler.getSlots(),
                SummoningRitualInventoryOps.catalystSlot(handler));
        return true;
    }

    private boolean fail(String detail, String messageKey) {
        validationFailure = detail;
        if (player != null) player.sendSystemMessage(Component.translatable(messageKey));
        return false;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return missingMaterials.isEmpty() ? List.of() : missingMaterials;
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        if (remainingOperations <= 0) return 0;
        ServerLevel level = player == null ? null : resolveMachineLevel(player);
        BlockEntity blockEntity = level == null || altarPos == null || !level.isLoaded(altarPos)
                ? null : level.getBlockEntity(altarPos);
        IItemHandler handler = itemHandler(blockEntity);
        if (!SummoningRitualInventoryOps.valid(handler)) return 0;
        preparedExecutions = remainingOperations;
        missingMaterials = calculateMissing(handler, catalyst, recipeInputs, preparedExecutions);
        return preparedExecutions;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getFlatBatchRequiredMaterials(int executions) {
        if (executions != preparedExecutions) return null;
        return missingMaterials;
    }

    /**
     * 祭坛已经具备全部所需物品时，执行链不会携带材料列表，
     * 而是走共享账本的旧入口。这里仍然走同一套校验和准备逻辑，
     * 避免无缺料配方被错误地当成“不支持启动”。
     */
    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player,
                                       @Nonnull ExtractionLedger sharedLedger) {
        return tryStartWithMaterials(player, List.of(), sharedLedger);
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        // 兼容仍使用委托自提入口的旧执行器；无材料时账本不会产生提取记录。
        return tryStartWithMaterials(player, List.of(), new ExtractionLedger());
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                         @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger sharedLedger) {
        this.player = player;
        useSharedLedger(sharedLedger);
        ServerLevel level = resolveMachineLevel(player);
        if (level == null || altarPos == null || !level.isLoaded(altarPos)) {
            player.sendSystemMessage(Component.translatable("rsi.error.chunk_unloaded"));
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(altarPos);
        IItemHandler handler = blockEntity == null ? null : itemHandler(blockEntity);
        if (!SummoningRitualInventoryOps.valid(handler)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.summoning_rituals.error.no_item_handler"));
            return false;
        }
        if (!sameSnapshot(handler, preparedSnapshot)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.summoning_rituals.error.inventory_changed"));
            return false;
        }
        List<IngredientSpec> currentMissing = calculateMissing(
                handler, catalyst, recipeInputs, preparedExecutions);
        List<PreparedMaterial> orderedMaterials = orderPreparedMaterials(materials, currentMissing, catalyst);
        if (orderedMaterials == null) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.summoning_rituals.error.material_reservation"));
            return false;
        }
        missingMaterials = currentMissing;
        if ((!orderedMaterials.isEmpty() || hasConflictingItems(handler)) && !hasStorageAccess()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.network_unavailable"));
            return false;
        }

        // 先回收与目标配方冲突的旧物品。同配方材料和同 NBT 催化剂保持原位。
        if (!recycleConflicts(handler)) return false;

        for (PreparedMaterial material : orderedMaterials) {
            ItemStack stack = material.stack().copy();
            IngredientSpec spec = material.spec();
            RSIntegrationMod.LOGGER.info(
                    "[RSI-SummoningRituals] Placing prepared material recipe={} role={} item={} count={} catalystMatch={} slot={}",
                    recipe.getId(), spec.role(), stack.getItem(), stack.getCount(), matchesCatalyst(stack),
                    spec.role() == DemandRole.CATALYST
                            ? SummoningRitualInventoryOps.catalystSlot(handler) : "material");
            ItemStack remainder;
            if (spec.role() == DemandRole.CATALYST) {
                remainder = SummoningRitualInventoryOps.insertCatalyst(handler, stack, inserted);
            } else {
                remainder = SummoningRitualInventoryOps.insertMaterial(handler, stack, inserted);
            }
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-SummoningRituals] Altar insertion incomplete recipe={} slotRole={} remainder={}x{}",
                        recipe.getId(), spec.role(), remainder.getItem(), remainder.getCount());
                rollbackPreparedItems(handler);
                player.sendSystemMessage(Component.translatable(
                        "rsi.summoning_rituals.error.insert_failed"));
                return false;
            }
        }

        this.prepared = true;
        markCraftStarted();
        return true;
    }

    private boolean recycleConflicts(IItemHandler handler) {
        int catalystSlot = SummoningRitualInventoryOps.catalystSlot(handler);
        for (int slot = 0; slot < catalystSlot; slot++) {
            ItemStack existing = handler.getStackInSlot(slot);
            if (existing.isEmpty() || matchesAnyInput(existing)) continue;
            if (!recycleSlot(handler, slot, false)) return false;
        }

        ItemStack existingCatalyst = handler.getStackInSlot(catalystSlot);
        if (!existingCatalyst.isEmpty() && !matchesCatalyst(existingCatalyst)) {
            return recycleSlot(handler, catalystSlot, true);
        }
        return true;
    }

    private boolean hasConflictingItems(IItemHandler handler) {
        int catalystSlot = SummoningRitualInventoryOps.catalystSlot(handler);
        for (int slot = 0; slot < catalystSlot; slot++) {
            ItemStack existing = handler.getStackInSlot(slot);
            if (!existing.isEmpty() && !matchesAnyInput(existing)) return true;
        }
        if (catalystSlot >= 0) {
            ItemStack existingCatalyst = handler.getStackInSlot(catalystSlot);
            return !existingCatalyst.isEmpty() && !matchesCatalyst(existingCatalyst);
        }
        return false;
    }

    private boolean matchesCatalyst(ItemStack stack) {
        return !catalyst.isEmpty() && IngredientMatcher.test(catalyst, stack);
    }

    private boolean recycleSlot(IItemHandler handler, int slot, boolean catalystSlot) {
        List<ItemStack> preview = SummoningRitualInventoryOps.extractSlot(handler, slot, true);
        for (ItemStack stack : preview) {
            if (!insertIntoStorage(player, stack, true).isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.summoning_rituals.error.recycle_full"));
                return false;
            }
        }

        for (ItemStack extracted : SummoningRitualInventoryOps.extractSlot(handler, slot, false)) {
            ItemStack remainder = insertIntoStorage(player, extracted, false);
            if (remainder.isEmpty()) continue;
            List<SummoningRitualInventoryOps.Inserted> restored = new ArrayList<>();
            ItemStack restoreRemainder = catalystSlot
                    ? SummoningRitualInventoryOps.insertCatalyst(handler, remainder, restored)
                    : SummoningRitualInventoryOps.insertMaterial(handler, remainder, restored);
            if (!restoreRemainder.isEmpty()) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-SummoningRituals] Failed restoring recycle remainder at {} slot={} stack={}x{}",
                        altarPos, slot, restoreRemainder.getItem(), restoreRemainder.getCount());
            }
            player.sendSystemMessage(Component.translatable(
                    "rsi.summoning_rituals.error.recycle_full"));
            return false;
        }
        return true;
    }

    private boolean matchesAnyInput(ItemStack stack) {
        for (IngredientSpec spec : recipeInputs) {
            if (IngredientMatcher.test(spec.ingredient(), stack)) return true;
        }
        return false;
    }

    @Override
    protected CraftObservation observeMachineCraft(@Nonnull ServerLevel level,
                                                   @Nonnull BlockEntity blockEntity) {
        if (!prepared) return failObservation("altar preparation was not committed");
        if (!isAltar(blockEntity) || itemHandler(blockEntity) == null) {
            return failObservation("altar item handler disappeared");
        }
        player.sendSystemMessage(Component.translatable(
                "rsi.summoning_rituals.prepared"));
        return doneObservation();
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel level,
                                             @Nonnull BlockEntity blockEntity) {
        return prepared;
    }

    @Nonnull
    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean publishesDeclaredGraphOutputs() {
        return false;
    }

    @Override
    protected void clearMachineState(BlockEntity blockEntity, ServerPlayer player) {
        IItemHandler handler = itemHandler(blockEntity);
        if (handler != null) rollbackPreparedItems(handler);
        resetLocalState();
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        // 成功仅表示物品已准备好。物品留在祭坛，等待玩家手动启动或取回。
        inserted.clear();
        resetLocalState();
    }

    private void rollbackPreparedItems(IItemHandler handler) {
        recordFailureRecoveredInputs(
                SummoningRitualInventoryOps.rollbackInsertions(handler, inserted));
        prepared = false;
        preparedExecutions = 1;
    }

    private void resetLocalState() {
        prepared = false;
        recipe = null;
        catalyst = Ingredient.EMPTY;
        recipeInputs = List.of();
        missingMaterials = List.of();
        preparedSnapshot = List.of();
        resetState();
    }

    @Nonnull
    @Override
    public BlockPos getMachinePos() {
        return altarPos;
    }

    private static IItemHandler itemHandler(BlockEntity blockEntity) {
        if (blockEntity == null || blockEntity.isRemoved()) return null;
        return blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
    }

    private static boolean isAltar(BlockEntity blockEntity) {
        for (Class<?> type = blockEntity.getClass(); type != null; type = type.getSuperclass()) {
            if (ALTAR_BE_CLASS.equals(type.getName())) return true;
        }
        return false;
    }

    private static List<ItemStack> snapshot(IItemHandler handler) {
        List<ItemStack> result = new ArrayList<>(handler.getSlots());
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            result.add(handler.getStackInSlot(slot).copy());
        }
        return List.copyOf(result);
    }

    private static boolean sameSnapshot(IItemHandler handler, List<ItemStack> expected) {
        if (handler.getSlots() != expected.size()) return false;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!ItemStack.matches(handler.getStackInSlot(slot), expected.get(slot))) return false;
        }
        return true;
    }

    static List<IngredientSpec> calculateMissing(IItemHandler handler, Ingredient catalyst,
                                                  List<IngredientSpec> inputs) {
        return calculateMissing(handler, catalyst, inputs, 1);
    }

    static List<IngredientSpec> calculateMissing(IItemHandler handler, Ingredient catalyst,
                                                  List<IngredientSpec> inputs, int executions) {
        List<IngredientSpec> missing = new ArrayList<>();
        int catalystSlot = SummoningRitualInventoryOps.catalystSlot(handler);
        if (!catalyst.isEmpty() && (catalystSlot < 0
                || !IngredientMatcher.test(catalyst, handler.getStackInSlot(catalystSlot)))) {
            missing.add(new IngredientSpec(catalyst, 1, DemandRole.CATALYST));
        }

        int multiplier = Math.max(1, executions);
        int[] remaining = inputs.stream()
                .mapToInt(spec -> (int) Math.min(Integer.MAX_VALUE,
                        (long) spec.count() * multiplier))
                .toArray();
        for (int slot = 0; slot < catalystSlot; slot++) {
            ItemStack existing = handler.getStackInSlot(slot);
            if (existing.isEmpty()) continue;
            int available = existing.getCount();
            for (int index = 0; index < inputs.size() && available > 0; index++) {
                if (remaining[index] <= 0
                        || !IngredientMatcher.test(inputs.get(index).ingredient(), existing)) continue;
                int used = Math.min(available, remaining[index]);
                remaining[index] -= used;
                available -= used;
            }
        }
        for (int index = 0; index < inputs.size(); index++) {
            if (remaining[index] > 0) {
                missing.add(new IngredientSpec(inputs.get(index).ingredient(), remaining[index]));
            }
        }
        return List.copyOf(missing);
    }

    /**
     * 计划阶段也要扣除祭坛中已经存在的物品，否则终端会为可复用催化剂
     * 额外安排一次合成，随后又因委托预检发现它已存在而不使用这份材料。
     */
    public static List<IngredientSpec> planningMaterials(ServerPlayer player, Recipe<?> recipe,
                                                         @Nullable ResourceLocation dim,
                                                         @Nullable BlockPos pos,
                                                         List<IngredientSpec> requested) {
        if (player == null || recipe == null || pos == null
                || !SummoningRitualRecipeHandler.RECIPE_CLASS.equals(recipe.getClass().getName())) {
            return requested == null ? List.of() : List.copyOf(requested);
        }
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null || !level.isLoaded(pos)) return requested == null ? List.of() : List.copyOf(requested);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        IItemHandler handler = itemHandler(blockEntity);
        if (!SummoningRitualInventoryOps.valid(handler)) return requested == null ? List.of() : List.copyOf(requested);
        return calculateMissing(handler, requested == null ? List.of() : requested,
                SummoningRitualRecipeHandler.catalyst(recipe));
    }

    static List<IngredientSpec> calculateMissing(IItemHandler handler,
                                                  List<IngredientSpec> requested,
                                                  Ingredient catalyst) {
        if (!SummoningRitualInventoryOps.valid(handler) || requested == null || requested.isEmpty()) {
            return requested == null ? List.of() : List.copyOf(requested);
        }
        int catalystSlot = SummoningRitualInventoryOps.catalystSlot(handler);
        List<IngredientSpec> missing = new ArrayList<>();
        ItemStack installedCatalyst = catalystSlot < 0 ? ItemStack.EMPTY
                : handler.getStackInSlot(catalystSlot);
        boolean catalystPresent = catalyst == null || catalyst.isEmpty()
                || IngredientMatcher.test(catalyst, installedCatalyst);
        int[] remaining = new int[requested.size()];
        for (int index = 0; index < requested.size(); index++) {
            IngredientSpec spec = requested.get(index);
            if (spec == null || spec.isEmpty()) continue;
            if (spec.role() == DemandRole.CATALYST && catalystPresent) continue;
            remaining[index] = spec.count();
        }
        for (int slot = 0; slot < catalystSlot; slot++) {
            ItemStack existing = handler.getStackInSlot(slot);
            if (existing.isEmpty()) continue;
            int available = existing.getCount();
            for (int index = 0; index < requested.size() && available > 0; index++) {
                IngredientSpec spec = requested.get(index);
                if (remaining[index] <= 0 || spec == null || spec.role() == DemandRole.CATALYST
                        || !IngredientMatcher.test(spec.ingredient(), existing)) continue;
                int used = Math.min(available, remaining[index]);
                remaining[index] -= used;
                available -= used;
            }
        }
        for (int index = 0; index < requested.size(); index++) {
            IngredientSpec spec = requested.get(index);
            if (spec != null && !spec.isEmpty() && remaining[index] > 0) {
                missing.add(new IngredientSpec(spec.ingredient(), remaining[index], spec.role()));
            }
        }
        return List.copyOf(missing);
    }

    @Nullable
    static List<PreparedMaterial> orderPreparedMaterials(List<ItemStack> materials,
                                                         List<IngredientSpec> specs,
                                                         Ingredient catalyst) {
        if (materials == null || specs == null || materials.size() != specs.size()) return null;
        boolean[] used = new boolean[materials.size()];
        List<PreparedMaterial> ordered = new ArrayList<>(specs.size());

        // 催化剂角色以配方的 getCatalyst() 为准，不依赖预留物品或普通材料的顺序。
        IngredientSpec catalystSpec = null;
        if (catalyst != null && !catalyst.isEmpty()) {
            for (IngredientSpec spec : specs) {
                if (spec.role() == DemandRole.CATALYST) {
                    catalystSpec = spec;
                    break;
                }
            }
            if (catalystSpec != null) {
                int match = findMaterial(materials, used, catalystSpec, catalyst);
                if (match < 0) return null;
                used[match] = true;
                ordered.add(new PreparedMaterial(catalystSpec, materials.get(match).copy()));
            }
        }

        for (IngredientSpec spec : specs) {
            if (spec.role() == DemandRole.CATALYST) continue;
            int match = findMaterial(materials, used, spec, null);
            if (match < 0) return null;
            used[match] = true;
            ordered.add(new PreparedMaterial(spec, materials.get(match).copy()));
        }
        if (ordered.size() != materials.size()) return null;
        return List.copyOf(ordered);
    }

    /** 兼容旧测试/调用方的无角色投影；执行路径使用带角色的版本。 */
    @Nullable
    static List<ItemStack> orderMaterials(List<ItemStack> materials, List<IngredientSpec> specs) {
        Ingredient catalyst = specs.stream()
                .filter(spec -> spec.role() == DemandRole.CATALYST)
                .map(IngredientSpec::ingredient)
                .findFirst().orElse(Ingredient.EMPTY);
        List<PreparedMaterial> prepared = orderPreparedMaterials(materials, specs, catalyst);
        if (prepared == null) return null;
        return prepared.stream().map(PreparedMaterial::stack).map(ItemStack::copy).toList();
    }

    private static int findMaterial(List<ItemStack> materials, boolean[] used,
                                    IngredientSpec spec, @Nullable Ingredient requiredCatalyst) {
        for (int index = 0; index < materials.size(); index++) {
            ItemStack stack = materials.get(index);
            if (used[index] || stack == null || stack.isEmpty()
                    || stack.getCount() != spec.count()
                    || !IngredientMatcher.test(spec.ingredient(), stack)) continue;
            if (requiredCatalyst != null && !IngredientMatcher.test(requiredCatalyst, stack)) continue;
            return index;
        }
        return -1;
    }

    /** 构建计划页的多祭坛候选列表，复用 Goety 相同的选择视图。 */
    public static List<MachineCandidateView> getPlanMachineCandidates(
            ServerPlayer player, Recipe<?> recipe) {
        if (player == null || recipe == null || !SummoningRitualRecipeHandler.RECIPE_CLASS
                .equals(recipe.getClass().getName())) return List.of();
        ModType type = ModType.byId(ModIds.ID_SUMMONING_RITUALS);
        if (type == null) return List.of();

        List<MachineCandidateView> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AltarBindingRegistry.BoundMachine machine :
                AltarBindingRegistry.getBoundMachinesForRecipe(player, type, recipe.getId())) {
            String key = machine.dim() + "@" + machine.pos().asLong();
            if (!seen.add(key)) continue;
            ServerLevel level = CraftPacketUtils.resolveLevel(player.server, machine.dim(), player);
            if (level == null || !level.isLoaded(machine.pos())) continue;
            ItemStack icon = level != null && level.isLoaded(machine.pos())
                    ? new ItemStack(level.getBlockState(machine.pos()).getBlock()) : ItemStack.EMPTY;
            MachineCandidateView.State state;
            Component status;
            SummoningRitualAltarBatchDelegate delegate = null;
            try {
                delegate = new SummoningRitualAltarBatchDelegate();
                IBatchDelegate.PreparationResult check = PreparationMessageScope.prepare(
                        delegate, player, recipe.getId(), machine.dim(), machine.pos());
                state = check.state() == IBatchDelegate.PreparationState.READY
                        ? MachineCandidateView.State.READY
                        : MachineCandidateView.State.TEMPORARY;
                status = check.state() == IBatchDelegate.PreparationState.READY
                        ? Component.translatable("rsi.machine_candidate.ready")
                        : Component.translatable("rsi.machine_candidate.temporary");
            } catch (RuntimeException exception) {
                state = MachineCandidateView.State.TEMPORARY;
                status = Component.translatable("rsi.machine_candidate.check_failed");
            } finally {
                if (delegate != null) delegate.releasePreparationResources();
            }
            result.add(new MachineCandidateView(machine.dim().toString(),
                    machine.pos().getX(), machine.pos().getY(), machine.pos().getZ(),
                    icon, state, status));
        }
        return orderPlanMachineCandidates(result, player.level().dimension().location(),
                player.getX(), player.getY(), player.getZ());
    }

    static List<MachineCandidateView> orderPlanMachineCandidates(
            List<MachineCandidateView> candidates, ResourceLocation playerDimension,
            double playerX, double playerY, double playerZ) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        return candidates.stream()
                .sorted(Comparator
                        .comparingInt((MachineCandidateView candidate) ->
                                candidate.state() == MachineCandidateView.State.READY ? 0 : 1)
                        .thenComparingInt(candidate ->
                                candidate.dimension().equals(playerDimension.toString()) ? 0 : 1)
                        .thenComparingDouble(candidate -> {
                            if (!candidate.dimension().equals(playerDimension.toString())) {
                                return Double.MAX_VALUE;
                            }
                            double dx = candidate.x() + 0.5D - playerX;
                            double dy = candidate.y() + 0.5D - playerY;
                            double dz = candidate.z() + 0.5D - playerZ;
                            return dx * dx + dy * dy + dz * dz;
                        }))
                .toList();
    }
}
