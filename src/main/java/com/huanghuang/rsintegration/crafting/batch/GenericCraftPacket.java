package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.ModRecipeIndex;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class GenericCraftPacket {

    // Tick-bucketed LRU cache to avoid recomputing the same plan within ~1 second.
    // Key: "playerUUID:recipeId:tickBucket" → PlanResponse
    private static final Map<String, PlanResponse> PLAN_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PlanResponse> eldest) {
                    return size() > 32;
                }
            });

    private final ResourceLocation recipeId;
    private final boolean preview;
    /** itemRegKey → forced recipeId (only for preview mode, empty when unused) */
    private final Map<String, String> forcedRecipes;
    @Nullable private final ResourceLocation dim;
    @Nullable private final net.minecraft.core.BlockPos pos;

    /** Preview mode: compute plan and send GUI to client. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview) {
        this(recipeId, preview, Collections.emptyMap(), null, null);
    }

    /** Preview mode with forced recipe overrides (for OR-path selection). */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes) {
        this(recipeId, preview, forcedRecipes, null, null);
    }

    /** Full constructor with optional machine binding for mod recipes. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos) {
        this.recipeId = recipeId;
        this.preview = preview;
        this.forcedRecipes = forcedRecipes != null ? forcedRecipes : Collections.emptyMap();
        this.dim = dim;
        this.pos = pos;
    }

    /** Convenience: execute mode. */
    public GenericCraftPacket(ResourceLocation recipeId) {
        this(recipeId, false, Collections.emptyMap(), null, null);
    }

    /** JEI-initiated preview for a mod recipe with known machine location. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(recipeId);
        buf.writeBoolean(preview);
        buf.writeVarInt(forcedRecipes.size());
        for (var e : forcedRecipes.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
        buf.writeBoolean(dim != null);
        if (dim != null) buf.writeResourceLocation(dim);
        buf.writeBoolean(pos != null);
        if (pos != null) buf.writeBlockPos(pos);
    }

    public static GenericCraftPacket decode(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        boolean preview = buf.readBoolean();
        int forcedCount = Math.min(buf.readVarInt(), 128);
        Map<String, String> forced = new HashMap<>();
        for (int i = 0; i < forcedCount; i++) {
            String key = buf.readUtf();
            String value = buf.readUtf();
            if (!key.isEmpty() && !value.isEmpty()
                    && ResourceLocation.tryParse(key) != null
                    && ResourceLocation.tryParse(value) != null) {
                forced.put(key, value);
            }
        }
        ResourceLocation dim = buf.readBoolean() ? buf.readResourceLocation() : null;
        net.minecraft.core.BlockPos pos = null;
        if (buf.readBoolean()) {
            net.minecraft.core.BlockPos raw = buf.readBlockPos();
            if (raw.getX() >= -30000000 && raw.getX() <= 30000000
                    && raw.getY() >= -64 && raw.getY() <= 2048
                    && raw.getZ() >= -30000000 && raw.getZ() <= 30000000) {
                pos = raw;
            }
        }
        return new GenericCraftPacket(recipeId, preview, forced, dim, pos);
    }

    public static void handle(GenericCraftPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            try {
                if (packet.preview) {
                    tryBuildPlan(player, packet.recipeId, packet.forcedRecipes,
                            packet.dim, packet.pos);
                } else {
                    tryResolve(player, packet.recipeId, packet.dim, packet.pos);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Generic] Failed for {}:", packet.recipeId, e);
                player.sendSystemMessage(Component.translatable("rsi.generic.error.craft_failed", e.getMessage()));
            }
        });
        context.setPacketHandled(true);
    }

    // ── execute: resolve ingredients, craft result, give result to player ──

    private static void tryResolve(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim,
                                   @Nullable net.minecraft.core.BlockPos pos) {
        Recipe<?> recipe = player.serverLevel().getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return;
        }

        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs == null || specs.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.no_ingredients"));
            return;
        }

        // Group non-empty ingredients by item type with total count
        Map<Item, IngredientNeed> grouped = new LinkedHashMap<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack[] opts = spec.ingredient().getItems();
            if (opts.length == 0) continue;
            Item item = opts[0].getItem();
            grouped.computeIfAbsent(item, k -> new IngredientNeed(spec.ingredient(), 0)).count += spec.count();
        }

        // Resolve network via machine binding when available — the player may
        // not carry RS items, but the bound altar can locate the controller.
        INetwork network;
        if (dim != null && pos != null) {
            network = CraftPacketUtils.resolveNetworkForCraft(player,
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, dim),
                    pos);
        } else {
            network = RSIntegration.resolveNetworkFromPlayer(player);
        }

        // Mod recipe with machine binding → async chain via plan preview path
        if (!(recipe instanceof CraftingRecipe) && dim != null && pos != null
                && network != null && RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get()) {
            ModType modType = ModType.classifyRecipe(recipe);
            if (modType != null) {
                // Expand ingredient specs into flat list for typed resolver
                List<Ingredient> expanded = new ArrayList<>();
                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    for (int i = 0; i < spec.count(); i++) {
                        expanded.add(spec.ingredient());
                    }
                }
                Map<StackKey, Integer> avail = MaterialSources.listAllAvailable(player, network);
                List<String> missing = new ArrayList<>();
                List<ResolutionStep> modSteps = CraftingResolver.resolveStepsForIngredientsWithTypes(
                        expanded, avail, player.serverLevel(), player, network, missing);
                if (!missing.isEmpty()) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", String.join(", ", missing)));
                    return;
                }
                // Append the mod recipe itself as the final multi-block step
                modSteps.add(new ResolutionStep(recipeId, modType, recipeId));
                AsyncCraftChain chain = new AsyncCraftChain(player, network, modSteps);
                AsyncCraftManager.getInstance().submit(chain);
                player.sendSystemMessage(Component.translatable(
                        "rsi.async.chain_started", modSteps.size()));
                return;
            }
        }

        // Pre-resolve: if intermediate steps are needed, execute them
        if (RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get() && network != null
                && recipe instanceof CraftingRecipe cr) {
            List<ResolutionStep> allSteps = CraftPacketUtils.resolveIntermediateSteps(player, network, cr);
            if (allSteps == null || allSteps.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Generic] resolveIntermediateSteps returned null/empty for {}, falling through", recipeId);
            }
            if (allSteps != null && !allSteps.isEmpty()) {
                if (allSteps.stream().anyMatch(s -> s.modType() != ModType.GENERIC)) {
                    // Multi-block intermediates → async chain
                    allSteps.add(new ResolutionStep(recipeId, ModType.GENERIC,
                            new ResourceLocation("minecraft:crafting")));
                    AsyncCraftChain chain = new AsyncCraftChain(player, network, allSteps);
                    AsyncCraftManager.getInstance().submit(chain);
                    player.sendSystemMessage(Component.translatable(
                            "rsi.async.chain_started", allSteps.size()));
                    return;
                }
                // All GENERIC steps → execute sync chain (virtual inventory
                // forward-feed, atomic extraction, final product to player)
                List<ResourceLocation> stepIds = allSteps.stream()
                        .map(ResolutionStep::recipeId).collect(Collectors.toList());
                stepIds.add(recipeId);
                if (CraftPacketUtils.executeCraftingSteps(player, stepIds, network)) {
                    ItemStack result = ModRecipeIndex.tryGetResultItem(recipe, player.serverLevel().registryAccess());
                    if (!result.isEmpty()) {
                        ItemStack extracted = safeExtractResult(network, result);
                        if (!extracted.isEmpty()) {
                            ItemHandlerHelper.giveItemToPlayer(player, extracted);
                            player.displayClientMessage(
                                    Component.translatable("rsi.generic.info.resolved", extracted.getCount()), true);
                        } else {
                            RSIntegrationMod.LOGGER.warn("[RSI-Generic] result extract returned empty for {}", recipeId);
                        }
                    }
                } else {
                    RSIntegrationMod.LOGGER.warn("[RSI-Generic] executeCraftingSteps (Path1) returned false for {} with {} steps", recipeId, stepIds.size());
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", "Intermediate crafting failed"));
                }
                return;
            }
        }

        // Unified resolution pass: use the same resolver as tryBuildPlan so the
        // execute path sees the same recipe chain the preview showed. Only for
        // CraftingRecipe (the typed resolver works with shaped/shapeless ingredients).
        if (recipe instanceof CraftingRecipe cr2 && network != null
                && RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) {
            Map<StackKey, Integer> avail = MaterialSources.listAllAvailable(player, network);
            List<String> missingCheck = new ArrayList<>();
            List<ResolutionStep> planSteps = CraftingResolver.resolveStepsForIngredientsWithTypes(
                    cr2.getIngredients(), avail, player.serverLevel(),
                    player, network, missingCheck);
            if (planSteps != null && !planSteps.isEmpty() && missingCheck.isEmpty()) {
                if (planSteps.stream().anyMatch(s -> s.modType() != ModType.GENERIC)) {
                    planSteps.add(new ResolutionStep(recipeId, ModType.GENERIC,
                            new ResourceLocation("minecraft:crafting")));
                    AsyncCraftChain chain = new AsyncCraftChain(player, network, planSteps);
                    AsyncCraftManager.getInstance().submit(chain);
                    player.sendSystemMessage(Component.translatable(
                            "rsi.async.chain_started", planSteps.size()));
                    return;
                }
                List<ResourceLocation> stepIds = planSteps.stream()
                        .map(ResolutionStep::recipeId).collect(Collectors.toList());
                stepIds.add(recipeId);
                if (CraftPacketUtils.executeCraftingSteps(player, stepIds, network)) {
                    ItemStack result = ModRecipeIndex.tryGetResultItem(recipe, player.serverLevel().registryAccess());
                    if (!result.isEmpty()) {
                        ItemStack extracted = safeExtractResult(network, result);
                        if (!extracted.isEmpty()) {
                            ItemHandlerHelper.giveItemToPlayer(player, extracted);
                            player.displayClientMessage(
                                    Component.translatable("rsi.generic.info.resolved", extracted.getCount()), true);
                        } else {
                            RSIntegrationMod.LOGGER.warn("[RSI-Generic] result extract returned empty for {}", recipeId);
                        }
                    }
                } else {
                    RSIntegrationMod.LOGGER.warn("[RSI-Generic] executeCraftingSteps (Path2) returned false for {} with {} steps", recipeId, stepIds.size());
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", "Intermediate crafting failed"));
                }
                return;
            }
            // planSteps=0 with missing=[] means everything is directly available; not a failure
            if (!missingCheck.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Generic] Unified resolver failed for {}: planSteps={} missing={}",
                        recipeId, planSteps != null ? planSteps.size() : "null", missingCheck);
            } else {
                RSIntegrationMod.LOGGER.debug("[RSI-Generic] Unified resolver: nothing to resolve for {}, falling through to direct extraction", recipeId);
            }
        }

        // Re-resolve network in case the top-level resolution failed but
        // ensureMaterialAvailable succeeded via binding/NBT fallback internally.
        // The ledger's NETWORK entries need a valid network for commit extraction.
        INetwork commitNetwork = network != null ? network
                : CraftPacketUtils.resolveNetworkForCraft(player,
                        player.serverLevel().dimension(), player.blockPosition());

        ExtractionLedger ledger = new ExtractionLedger();

        // Plan all extractions atomically — nothing physically moved yet
        for (IngredientNeed need : grouped.values()) {
            ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                    player, player.serverLevel().dimension(),
                    player.blockPosition(), need.ingredient, need.count, ledger);
            if (reserved.isEmpty()) {
                // No items moved — safe to abort without refund
                RSIntegrationMod.LOGGER.warn("[RSI-Generic] Grouped extraction failed for {}: missing {} (needed {})",
                        recipeId, CraftPacketUtils.describeIngredient(need.ingredient), need.count);
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(need.ingredient)));
                return;
            }
        }

        // Commit all extractions atomically
        if (!ledger.commit(commitNetwork, player)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.craft_failed", "Extraction commit failed"));
            return;
        }

        // Use the resolved network for refund path too
        network = commitNetwork;

        // Craft the final recipe — materials were paid from RS, give the result
        ItemStack result = ModRecipeIndex.tryGetResultItem(recipe, player.serverLevel().registryAccess());
        if (result.isEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Generic] Result unavailable for {} ({})",
                    recipeId, recipe.getClass().getSimpleName());
        }

        if (!result.isEmpty()) {
            ItemHandlerHelper.giveItemToPlayer(player, result);
            player.displayClientMessage(
                    Component.translatable("rsi.generic.info.resolved", result.getCount()), true);
        } else {
            // Failed to get result — refund extracted materials
            if (network != null) {
                for (IngredientNeed need : grouped.values()) {
                    ItemStack refund = need.ingredient.getItems()[0].copyWithCount(need.count);
                    network.insertItem(refund, need.count,
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                }
            }
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.craft_failed", "Result unavailable"));
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Generic] Crafted {} ({} types) for {}",
                result.getCount(), grouped.size(), recipeId);
    }

    private static final class IngredientNeed {
        final Ingredient ingredient;
        int count;
        IngredientNeed(Ingredient ingredient, int count) {
            this.ingredient = ingredient;
            this.count = count;
        }
    }

    // ── preview: build plan and send to client ───────────────────

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable net.minecraft.core.BlockPos pos) {
        Recipe<?> recipe = player.serverLevel().getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) {
            sendPlanError(player, Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()).getString());
            return;
        }

        if (!RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) {
            sendPlanError(player, "Auto-crafting is disabled in config");
            return;
        }

        // Determine ingredients, output, and mod type for both vanilla and mod recipes
        List<Ingredient> recipeIngredients;
        ItemStack targetOutput;
        ModType recipeModType = null;

        if (recipe instanceof CraftingRecipe cr) {
            recipeIngredients = cr.getIngredients();
            targetOutput = cr.getResultItem(player.serverLevel().registryAccess());
        } else {
            List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
            if (specs == null || specs.isEmpty()) {
                sendPlanError(player, Component.translatable("rsi.generic.error.no_ingredients").getString());
                return;
            }
            List<Ingredient> expanded = new ArrayList<>();
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                for (int i = 0; i < spec.count(); i++) {
                    expanded.add(spec.ingredient());
                }
            }
            recipeIngredients = expanded;
            targetOutput = ModRecipeIndex.tryGetResultItem(recipe, player.serverLevel().registryAccess());
            if (targetOutput.isEmpty()) {
                sendPlanError(player, Component.translatable("rsi.generic.error.unsupported_machine", recipe.getClass().getSimpleName()).getString());
                return;
            }
            recipeModType = ModType.classifyRecipe(recipe);
        }

        // Convert forced recipe overrides (itemRegKey → recipeId) for the resolver
        Map<ResourceLocation, ResourceLocation> forcedOverrides = null;
        if (!forcedRecipes.isEmpty()) {
            forcedOverrides = new HashMap<>();
            for (var e : forcedRecipes.entrySet()) {
                ResourceLocation itemKey = ResourceLocation.tryParse(e.getKey());
                ResourceLocation forcedId = ResourceLocation.tryParse(e.getValue());
                if (itemKey != null && forcedId != null) {
                    forcedOverrides.put(itemKey, forcedId);
                }
            }
        }

        // Capture tick-bucket once to avoid drift between lookup and store
        final long tickBucket = player.serverLevel().getServer().getTickCount() / 20;
        final String cacheKey = player.getUUID() + ":" + recipeId + ":"
                + (forcedOverrides != null ? forcedOverrides.hashCode() : "0") + ":"
                + tickBucket;
        PlanResponse cached = PLAN_CACHE.get(cacheKey);
        if (cached != null) {
            BatchCraftNetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new PlanResponsePacket(cached));
            return;
        }

        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> planDimKey = dim != null
                ? net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, dim)
                : player.serverLevel().dimension();
        net.minecraft.core.BlockPos planLookupPos = pos != null ? pos : player.blockPosition();
        INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, planDimKey, planLookupPos);

        // Build available items map (RS network + player inventory)
        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, network);

        // Build plan via CraftingResolver
        List<String> missing = new ArrayList<>();

        List<ResolutionStep> resolutionSteps = null;
        List<ResourceLocation> stepIds;

        if (RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get() && network != null) {
            resolutionSteps = CraftingResolver.resolveStepsForIngredientsWithTypes(
                    recipeIngredients, available, player.serverLevel(),
                    player, network, missing, forcedOverrides, true);
        }
        boolean usedTypedResolver = resolutionSteps != null && !resolutionSteps.isEmpty();

        // ── OR debug: log resolution results ──
        RSIntegrationMod.LOGGER.info("[RSI-OR] tryBuildPlan recipe={} typedResolver={} steps={} alts={}",
                recipeId, usedTypedResolver,
                resolutionSteps != null ? resolutionSteps.size() : -1,
                resolutionSteps != null ? resolutionSteps.stream()
                        .filter(rs -> !rs.alternativeIds().isEmpty()).count() : -1);
        if (resolutionSteps != null) {
            for (ResolutionStep rs : resolutionSteps) {
                if (!rs.alternativeIds().isEmpty()) {
                    RSIntegrationMod.LOGGER.info("[RSI-OR]   step {} has {} alternatives: {}",
                            rs.recipeId(), rs.alternativeIds().size(), rs.alternativeIds());
                }
            }
        }
        if (resolutionSteps == null || resolutionSteps.isEmpty()) {
            List<ItemStack> availableStacks = new ArrayList<>();
            for (var e : available.entrySet()) {
                ItemStack s = new ItemStack(e.getKey().item(), e.getValue());
                if (e.getKey().tag() != null) {
                    try { s.setTag(net.minecraft.nbt.TagParser.parseTag(e.getKey().tag())); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed for key {}: {}", e.getKey(), ex.toString()); }
                }
                availableStacks.add(s);
            }
            stepIds = CraftingResolver.resolveStepsForIngredients(
                    recipeIngredients, availableStacks, player.serverLevel(), missing, forcedOverrides);
        } else {
            stepIds = resolutionSteps.stream()
                    .map(ResolutionStep::recipeId).collect(Collectors.toList());
        }

        // Build modType lookup from typed resolution results and recipe dimensions
        Map<ResourceLocation, ModType> modTypeByRecipe = new HashMap<>();
        Map<ResourceLocation, Integer> recipeWidths = new HashMap<>();
        Map<ResourceLocation, Integer> recipeHeights = new HashMap<>();
        Map<ResourceLocation, ResolutionStep> stepByRecipe = new HashMap<>();
        if (resolutionSteps != null) {
            for (ResolutionStep rs : resolutionSteps) {
                if (rs.modType() != ModType.GENERIC) {
                    modTypeByRecipe.putIfAbsent(rs.recipeId(), rs.modType());
                }
                stepByRecipe.putIfAbsent(rs.recipeId(), rs);
            }
        }

        // Compute itemAvailable early — needed for ingredient matching in both
        // PlanStep display and material calculation.
        Map<Item, Integer> itemAvailable = new HashMap<>();
        for (var e : available.entrySet()) {
            itemAvailable.merge(e.getKey().item(), e.getValue(), Integer::sum);
        }
        // RecipeIndex for OR alternative material checks — allows alternatives
        // whose ingredients are craftable (not just directly available).
        Map<Item, List<RecipeIndex.Entry>> recipeIndex = RecipeIndex.get(player.serverLevel());
        // Display copy: decremented on each match so the same tag ingredient
        // showing up N times doesn't pick the same item N times (e.g. 1 cherry
        // + 1 acacia shown as 2 acacia when both are logs).
        Map<Item, Integer> displayAvailable = new HashMap<>(itemAvailable);

        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            int totalItems = itemAvailable.values().stream().mapToInt(Integer::intValue).sum();
            RSIntegrationMod.LOGGER.debug("[RSI-Generic] itemAvailable: {} types, {} total items",
                    itemAvailable.size(), totalItems);
        }

        // Group step IDs into PlanSteps (batch identical consecutive steps)
        List<PlanStep> steps = new ArrayList<>();
        var rm = player.serverLevel().getRecipeManager();

        // Count occurrences
        Map<ResourceLocation, Integer> stepCounts = new LinkedHashMap<>();
        for (ResourceLocation id : stepIds) {
            stepCounts.merge(id, 1, Integer::sum);
        }

        for (var entry : stepCounts.entrySet()) {
            Recipe<?> stepRecipe = rm.byKey(entry.getKey()).orElse(null);
            if (stepRecipe == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-Generic] Plan step recipe not found: {}",
                        entry.getKey());
                continue;
            }
            ItemStack output = ModRecipeIndex.tryGetResultItem(
                    stepRecipe, player.serverLevel().registryAccess());
            if (output.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Generic] Plan step output empty: {} ({})",
                        entry.getKey(), stepRecipe.getClass().getSimpleName());
                continue;
            }
            int batches = entry.getValue();
            int recipeW = 0, recipeH = 0;

            List<ItemStack> inputs = new ArrayList<>();
            if (stepRecipe instanceof CraftingRecipe scr) {
                if (scr instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
                    recipeW = shaped.getWidth();
                    recipeH = shaped.getHeight();
                    // Preserve grid positions — include empty slots as ItemStack.EMPTY
                    for (Ingredient ing : scr.getIngredients()) {
                        if (ing.isEmpty()) {
                            inputs.add(ItemStack.EMPTY);
                        } else {
                            Item matched = matchAndConsume(ing, displayAvailable);
                            inputs.add(matched != null ? new ItemStack(matched, 1) : firstValidDisplayItem(ing));
                        }
                    }
                } else {
                    if (!scr.getIngredients().isEmpty()) {
                        int n = 0;
                        for (Ingredient ing : scr.getIngredients()) {
                            if (!ing.isEmpty()) n++;
                        }
                        if (n <= 3) { recipeW = n; recipeH = 1; }
                        else if (n <= 4) { recipeW = 2; recipeH = 2; }
                        else { recipeW = 3; recipeH = 3; }
                    }
                    for (Ingredient ing : scr.getIngredients()) {
                        if (ing.isEmpty()) continue;
                        Item matched = matchAndConsume(ing, displayAvailable);
                        inputs.add(matched != null ? new ItemStack(matched, 1) : firstValidDisplayItem(ing));
                    }
                }
            } else {
                // Multi-block recipe: extract ingredients via reflection
                List<Ingredient> modIngs = CraftPacketUtils.extractIngredients(stepRecipe);
                if (modIngs != null) {
                    for (Ingredient ing : modIngs) {
                        if (ing.isEmpty()) continue;
                        Item matched = matchAndConsume(ing, displayAvailable);
                        inputs.add(matched != null ? new ItemStack(matched, 1) : firstValidDisplayItem(ing));
                    }
                }
            }

            // Extract alternatives from the resolver's own candidate analysis.
            // Filter by material availability so the OR badge is only shown for
            // recipes the player can actually use.
            List<ResourceLocation> alternatives = new ArrayList<>();
            List<String> alternativeModTypes = new ArrayList<>();
            ResolutionStep rs = stepByRecipe.get(entry.getKey());
            RSIntegrationMod.LOGGER.info("[RSI-OR] buildStep {}: stepByRecipe has rs={} alternatives={}",
                    entry.getKey(), rs != null,
                    rs != null ? rs.alternativeIds().size() : -1);
            if (rs != null && !rs.alternativeIds().isEmpty()) {
                for (int i = 0; i < rs.alternativeIds().size(); i++) {
                    ResourceLocation altId = rs.alternativeIds().get(i);
                    String altMod = i < rs.alternativeModTypes().size()
                            ? rs.alternativeModTypes().get(i)
                            : rs.modType().id();
                    Recipe<?> altRecipe = rm.byKey(altId).orElse(null);
                    if (altRecipe == null) {
                        RSIntegrationMod.LOGGER.info("[RSI-OR]   alt {} NOT FOUND in recipe manager", altId);
                        continue;
                    }
                    List<Ingredient> altIngs;
                    if (altRecipe instanceof CraftingRecipe cr) {
                        altIngs = cr.getIngredients();
                    } else {
                        altIngs = CraftPacketUtils.extractIngredients(altRecipe);
                    }
                    boolean hasMats = altIngs != null && recipeHasSomeMaterials(altIngs, itemAvailable, recipeIndex);
                    RSIntegrationMod.LOGGER.info("[RSI-OR]   alt {}: ingCount={} hasMaterials={}",
                            altId, altIngs != null ? altIngs.size() : -1, hasMats);
                    if (hasMats) {
                        alternatives.add(altId);
                        alternativeModTypes.add(altMod);
                    }
                }
            }
            if (alternatives.isEmpty()) { alternatives = Collections.emptyList(); alternativeModTypes = Collections.emptyList(); }

            ModType mt = modTypeByRecipe.get(entry.getKey());
            recipeWidths.put(entry.getKey(), recipeW);
            recipeHeights.put(entry.getKey(), recipeH);
            steps.add(new PlanStep(entry.getKey(), output, batches, inputs, alternatives, mt,
                    0, !alternatives.isEmpty(), recipeW, recipeH, alternativeModTypes));
        }

        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Generic] Plan intermediate steps:");
            for (PlanStep s : steps) {
                StringBuilder sb = new StringBuilder("  ").append(s.recipeId())
                        .append(" -> ")
                        .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.output().getItem()))
                        .append(" x").append(s.totalOutputCount())
                        .append(" [");
                for (ItemStack in : s.inputs()) {
                    if (in.isEmpty()) sb.append("EMPTY ");
                    else sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(in.getItem())).append(" ");
                }
                sb.append("]");
                RSIntegrationMod.LOGGER.debug(sb.toString());
            }
        }

        // ── Compute tree depths ────────────────────────────────────
        // BFS depth assignment starting from target recipe ingredients.
        // Also seed raw materials (leaf items not produced by any step) as depth 0
        // so steps that consume them can propagate depth correctly.
        Map<Item, Integer> depthByItem = new HashMap<>();
        for (Ingredient ing : recipeIngredients) {
            if (ing.isEmpty()) continue;
            for (ItemStack stack : ing.getItems()) {
                if (!stack.isEmpty()) depthByItem.putIfAbsent(stack.getItem(), 0);
            }
        }
        Set<Item> outputs = new HashSet<>();
        for (PlanStep s : steps) outputs.add(s.output().getItem());
        for (PlanStep s : steps) {
            for (ItemStack in : s.inputs()) {
                Item it = in.getItem();
                if (!outputs.contains(it)) depthByItem.putIfAbsent(it, 0);
            }
        }
        // Propagate depths: each step's output gets depth = max(its ingredient depths) + 1
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < steps.size(); i++) {
                PlanStep step = steps.get(i);
                if (step.depth() > 0) continue; // already assigned
                int maxIngredientDepth = -1;
                for (ItemStack in : step.inputs()) {
                    Integer d = depthByItem.get(in.getItem());
                    if (d != null && d > maxIngredientDepth) maxIngredientDepth = d;
                }
                if (maxIngredientDepth >= 0) {
                    int newDepth = maxIngredientDepth + 1;
                    steps.set(i, new PlanStep(step.recipeId(), step.output(), step.batches(),
                            step.inputs(), step.alternatives(), step.modType(),
                            newDepth, step.hasOrSiblings(),
                            step.recipeWidth(), step.recipeHeight(),
                            step.alternativeModTypes()));
                    depthByItem.put(step.output().getItem(), newDepth);
                    changed = true;
                }
            }
        }

        // ── Add the target recipe itself as the last step so its grid is visible ──
        {
            List<ItemStack> targetInputs = new ArrayList<>();
            int targetW = 0, targetH = 0;
            if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
                targetW = shaped.getWidth();
                targetH = shaped.getHeight();
                for (Ingredient ing : recipeIngredients) {
                    if (ing.isEmpty()) {
                        targetInputs.add(ItemStack.EMPTY);
                    } else {
                        Item matched = matchAndConsume(ing, displayAvailable);
                        targetInputs.add(matched != null ? new ItemStack(matched, 1)
                                : firstValidDisplayItem(ing));
                    }
                }
            } else if (recipe instanceof CraftingRecipe) {
                int n = 0;
                for (Ingredient ing : recipeIngredients) { if (!ing.isEmpty()) n++; }
                if (n <= 3) { targetW = n; targetH = 1; }
                else if (n <= 4) { targetW = 2; targetH = 2; }
                else { targetW = 3; targetH = 3; }
                for (Ingredient ing : recipeIngredients) {
                    if (ing.isEmpty()) continue;
                    Item matched = matchAndConsume(ing, displayAvailable);
                    targetInputs.add(matched != null ? new ItemStack(matched, 1)
                            : firstValidDisplayItem(ing));
                }
            } else {
                // Mod recipe: linear layout with matched ingredients
                for (Ingredient ing : recipeIngredients) {
                    if (ing.isEmpty()) continue;
                    Item matched = matchAndConsume(ing, displayAvailable);
                    targetInputs.add(matched != null ? new ItemStack(matched, 1)
                            : firstValidDisplayItem(ing));
                }
            }
            int targetDepth = 0;
            for (PlanStep s : steps) targetDepth = Math.max(targetDepth, s.depth() + 1);

            // Collect OR alternatives for the target recipe itself from RecipeIndex
            List<ResourceLocation> targetAlts = new ArrayList<>();
            List<String> targetAltModTypes = new ArrayList<>();
            List<RecipeIndex.Entry> targetEntries = recipeIndex.get(targetOutput.getItem());
            if (targetEntries != null) {
                for (RecipeIndex.Entry e : targetEntries) {
                    if (e.recipe().getId().equals(recipeId)) continue;
                    List<Ingredient> altIngs;
                    if (e.recipe() instanceof CraftingRecipe cr) {
                        altIngs = cr.getIngredients();
                    } else {
                        altIngs = CraftPacketUtils.extractIngredients(e.recipe());
                    }
                    if (altIngs != null && recipeHasSomeMaterials(altIngs, itemAvailable, recipeIndex)) {
                        targetAlts.add(e.recipe().getId());
                        targetAltModTypes.add(e.modType().id());
                    }
                }
            }
            if (targetAlts.isEmpty()) { targetAlts = Collections.emptyList(); targetAltModTypes = Collections.emptyList(); }
            RSIntegrationMod.LOGGER.info("[RSI-OR] target step {}: {} alternatives from index",
                    recipeId, targetAlts.size());

            steps.add(new PlanStep(recipeId, targetOutput, 1, targetInputs,
                    targetAlts, recipeModType, targetDepth, !targetAlts.isEmpty(),
                    targetW, targetH, targetAltModTypes));

            if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder("[RSI-Generic] Target step: ")
                        .append(recipeId).append(" -> ")
                        .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(targetOutput.getItem()))
                        .append(" [");
                for (ItemStack in : targetInputs) {
                    if (in.isEmpty()) sb.append("EMPTY ");
                    else sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(in.getItem())).append(" ");
                }
                sb.append("] grid=").append(targetW).append("x").append(targetH);
                RSIntegrationMod.LOGGER.debug(sb.toString());
            }
        }

        // Compute material availability for ALL items in the plan.
        // neededCounts: displayItem → how many needed (negative if produced > consumed)
        // itemSource: displayItem → original Ingredient (may be a tag, for aggregate counting)
        // Use a FRESH copy of itemAvailable — displayAvailable was already
        // mutated by the step/target display pass above, and reusing it here
        // causes matchBestAvailable fallback to return arbitrary items from
        // the ingredient array, producing bizarre material requirements.
        Map<Item, Integer> matAvailable = new HashMap<>(itemAvailable);
        Map<Item, Integer> neededCounts = new LinkedHashMap<>();
        Map<Item, Ingredient> itemSource = new HashMap<>();
        for (Ingredient ing : recipeIngredients) {
            if (ing.isEmpty()) continue;
            Item matched = matchAndConsume(ing, matAvailable);
            if (matched != null) {
                neededCounts.merge(matched, 1, Integer::sum);
                itemSource.putIfAbsent(matched, ing);
            }
        }
        for (PlanStep step : steps) {
            // Target recipe: only subtract its output (inputs already counted
            // in the recipeIngredients loop above, don't double-count)
            if (step.recipeId().equals(recipeId)) {
                neededCounts.merge(step.output().getItem(), -step.totalOutputCount(), Integer::sum);
                continue;
            }
            Recipe<?> stepRecipe = rm.byKey(step.recipeId()).orElse(null);
            if (stepRecipe == null) continue;
            List<Ingredient> stepIngs;
            if (stepRecipe instanceof CraftingRecipe scr) {
                stepIngs = scr.getIngredients();
            } else {
                stepIngs = CraftPacketUtils.extractIngredients(stepRecipe);
            }
            if (stepIngs == null) continue;
            for (Ingredient ing : stepIngs) {
                if (ing.isEmpty()) continue;
                Item matched = matchAndConsume(ing, matAvailable);
                if (matched != null) {
                    neededCounts.merge(matched, step.batches(), Integer::sum);
                    itemSource.putIfAbsent(matched, ing);
                }
            }
        }
        // Subtract what intermediate steps produce (skip target — already done above).
        for (PlanStep step : steps) {
            if (step.recipeId().equals(recipeId)) continue;
            neededCounts.merge(step.output().getItem(), -step.totalOutputCount(), Integer::sum);
        }

        Map<Item, PlanResponse.Availability> materials = new LinkedHashMap<>();
        // Track tag-based ingredient groups so multiple slots of the same tag
        // (e.g. "2 × #logs") get merged into a single material entry instead of
        // showing separate per-item entries with double-counted availability.
        Set<String> mergedTagKeys = new HashSet<>();
        for (var entry : neededCounts.entrySet()) {
            if (entry.getValue() <= 0) continue; // produced >= needed
            int needed = entry.getValue();
            Item displayItem = entry.getKey();
            Ingredient source = itemSource.get(displayItem);
            if (source != null && source.getItems().length > 1) {
                String tagKey = source.toJson().toString();
                if (!mergedTagKeys.add(tagKey)) continue; // already merged
                int totalNeeded = 0;
                int totalHave = 0;
                for (ItemStack opt : source.getItems()) {
                    if (opt.isEmpty()) continue;
                    Item optItem = opt.getItem();
                    totalNeeded += neededCounts.getOrDefault(optItem, 0);
                    totalHave += itemAvailable.getOrDefault(optItem, 0);
                }
                materials.put(displayItem, new PlanResponse.Availability(totalNeeded, totalHave));
            } else {
                int have = itemAvailable.getOrDefault(displayItem, 0);
                materials.put(displayItem, new PlanResponse.Availability(needed, have));
            }
        }

        boolean feasible = missing.isEmpty() && materials.values().stream().allMatch(PlanResponse.Availability::isEnough);

        if (RSIntegrationMod.LOGGER.isDebugEnabled()) {
            long shortageCount = materials.values().stream().filter(a -> !a.isEnough()).count();
            RSIntegrationMod.LOGGER.debug("[RSI-Generic] Plan for {}: {} steps, feasible={}, resolver={}, missing={}, shortages={}",
                    recipeId, steps.size(), feasible, usedTypedResolver ? "typed" : "fallback",
                    missing.size(), shortageCount);
        }
        // Dedup missing items — the resolver may add the same ingredient
        // name for every step that references it, producing an unreadable wall.
        List<String> dedupedMissing = missing.stream().distinct().toList();

        PlanResponse plan = new PlanResponse(
                feasible,
                targetOutput.getHoverName().getString(),
                targetOutput,
                steps,
                materials,
                dedupedMissing,
                recipeId.toString(),
                recipeModType != null ? recipeModType.id() : null,
                dim != null ? dim.toString() : null,
                pos != null ? pos.getX() : 0,
                pos != null ? pos.getY() : 0,
                pos != null ? pos.getZ() : 0
        );

        // Cache the plan with the same tick-bucket captured earlier
        PLAN_CACHE.put(cacheKey, plan);

        BatchCraftNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PlanResponsePacket(plan));
        RSIntegrationMod.LOGGER.debug("[RSI-Generic] Plan built for {}: {} steps, feasible={}",
                recipeId, steps.size(), feasible);
    }

    /**
     * Returns the best item from {@code ingredient.getItems()} — the one with
     * the highest available count in player inventory + RS network.
     */
    @Nullable
    private static Item matchBestAvailable(Ingredient ingredient, Map<Item, Integer> itemAvailable) {
        Item best = null;
        int bestCount = -1;
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            int count = itemAvailable.getOrDefault(item, 0);
            if (count > bestCount) {
                bestCount = count;
                best = item;
            }
        }
        if (best != null) return best;
        // Fallback: first non-empty item from the ingredient.
        // Never return Items.AIR — that produces invisible slots in the plan GUI.
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) return stack.getItem();
        }
        return null;
    }

    /**
     * Returns the first non-empty ItemStack from the ingredient, or
     * ItemStack.EMPTY if every entry is empty. Never returns a stack of air.
     */
    private static ItemStack firstValidDisplayItem(Ingredient ing) {
        for (ItemStack s : ing.getItems()) {
            if (!s.isEmpty()) return s.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    /**
     * Like {@link #matchBestAvailable} but also decrements the matched item
     * from the availability map so subsequent calls for the same tag-ingredient
     * spread across different valid items instead of always picking the same one.
     */
    @Nullable
    private static Item matchAndConsume(Ingredient ingredient, Map<Item, Integer> available) {
        Item matched = matchBestAvailable(ingredient, available);
        if (matched != null) {
            available.merge(matched, -1, Integer::sum);
        }
        return matched;
    }

    /**
     * Returns true if at least one matching item is available (directly or craftable)
     * for every non-empty ingredient. Uses RecipeIndex to check if an item can be
     * produced even when not directly in inventory — this prevents filtering out
     * alternatives that require one extra crafting hop (e.g. "其他原木 → 橡木原木 → 木板").
     */
    private static boolean recipeHasSomeMaterials(List<Ingredient> ingredients,
                                                   Map<Item, Integer> itemAvailable,
                                                   Map<Item, List<RecipeIndex.Entry>> recipeIndex) {
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) continue;
            boolean any = false;
            for (ItemStack opt : ing.getItems()) {
                if (opt.isEmpty()) continue;
                // Direct availability
                if (itemAvailable.getOrDefault(opt.getItem(), 0) > 0) { any = true; break; }
                // Craftable — player can make this item from raw materials
                if (recipeIndex.containsKey(opt.getItem())) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }

    private static void sendPlanError(ServerPlayer player, String msg) {
        BatchCraftNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PlanResponsePacket(new PlanResponse(false, "", ItemStack.EMPTY,
                        List.of(), Map.of(), List.of(msg), "")));
    }

    /**
     * Extract the crafting result from RS after {@code executeCraftingSteps}
     * has flushed the chain outputs. Uses a count-1 template (matching how
     * every other extraction point in the mod works) and falls back to
     * ingredient-based extraction if the exact-match fails.
     */
    @javax.annotation.Nullable
    private static ItemStack safeExtractResult(INetwork network, ItemStack result) {
        // Primary: extract with count-1 template (matches RS stored stacks correctly)
        ItemStack template = result.copy();
        template.setCount(1);
        ItemStack extracted = network.extractItem(template, result.getCount(),
                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        if (!extracted.isEmpty()) return extracted;

        // Fallback: ingredient-based extraction (aggregates across multiple stacks)
        extracted = RSIntegration.extractFromNetwork(network,
                net.minecraft.world.item.crafting.Ingredient.of(result.getItem()),
                result.getCount());
        return extracted;
    }
}
