package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.crafting.plan.PlanWarnings;
import com.huanghuang.rsintegration.mods.crabbersdelight.CrabTrapRecipeResolver;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotBatchDelegate;
import com.huanghuang.rsintegration.mods.farmersdelight.CookingPotBatchDelegate;
import com.huanghuang.rsintegration.mods.immortalersdelight.EnchantalCoolerBatchDelegate;
import com.huanghuang.rsintegration.mods.youkaishomecoming.moka.MokaPotBatchDelegate;
import com.huanghuang.rsintegration.mods.embers.EmbersPlanInfo;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketBatchDelegate;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualHelper;
import com.huanghuang.rsintegration.mods.forbidden.FaRitualWrapper;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.ChainRepeatController;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.PreviewRateLimiter;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.TextBuilder;
import com.huanghuang.rsintegration.util.ModIds;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class GenericCraftPacket {

    // Time-based plan cache — serves both dedup and compute-avoidance.
    // On cache hit within TTL: reply with cached plan immediately (no silent drop).
    // Key: "playerUUID:recipeId:forcedHash:repeatCount"
    private static final java.util.concurrent.ConcurrentHashMap<String, CachedPlan> PLAN_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PLAN_CACHE_TTL_NANOS = 5_000_000_000L; // 5 seconds

    private static final class CachedPlan {
        final PlanResponse plan;
        final long createdNanos;
        CachedPlan(PlanResponse plan, long createdNanos) {
            this.plan = plan;
            this.createdNanos = createdNanos;
        }
    }

    private final ResourceLocation recipeId;
    private final boolean preview;
    /** itemRegKey → forced recipeId (only for preview mode, empty when unused) */
    private final Map<String, String> forcedRecipes;
    @Nullable private final ResourceLocation dim;
    @Nullable private final net.minecraft.core.BlockPos pos;
    private final int repeatCount;
    private final boolean inferMode;
    /** JEI-provided base item for FA ApplyModifierRecipe prefill */
    @Nullable private final ItemStack baseItem;

    /** Preview mode: compute plan and send GUI to client. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview) {
        this(recipeId, preview, Collections.emptyMap(), null, null, 1);
    }

    /** Preview mode with forced recipe overrides (for OR-path selection). */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes) {
        this(recipeId, preview, forcedRecipes, null, null, 1);
    }

    /** Convenience: without explicit machine binding. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos) {
        this(recipeId, preview, forcedRecipes, dim, pos, 1);
    }

    /** Convenience: with repeat count, no infer mode. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
                              int repeatCount) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, false);
    }

    /** Convenience: with infer mode, no base item. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
                              int repeatCount, boolean inferMode) {
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode, null);
    }

    /** All parameters, including JEI base item for FA smithing prefill. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem) {
        this.recipeId = recipeId;
        this.preview = preview;
        this.forcedRecipes = forcedRecipes != null ? forcedRecipes : Collections.emptyMap();
        this.dim = dim;
        this.pos = pos;
        this.repeatCount = Math.max(1, Math.min(repeatCount, RSIntegrationConfig.REPEAT_COUNT_MAX.get()));
        this.inferMode = inferMode;
        this.baseItem = baseItem != null ? baseItem.copy() : null;
    }

    /** Convenience: execute mode. */
    public GenericCraftPacket(ResourceLocation recipeId) {
        this(recipeId, false, Collections.emptyMap(), null, null, 1);
    }

    /** JEI-initiated preview for a mod recipe with known machine location. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, 1);
    }

    /** JEI-initiated preview with repeat, inferMode, and specific base item. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, repeatCount, inferMode, baseItem);
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
        buf.writeVarInt(repeatCount);
        buf.writeBoolean(inferMode);
        buf.writeBoolean(baseItem != null);
        if (baseItem != null) buf.writeItem(baseItem);
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
        int repeatCount = Math.max(1, Math.min(buf.readVarInt(), RSIntegrationConfig.REPEAT_COUNT_MAX.get()));
        boolean inferMode = buf.readBoolean();
        ItemStack baseItem = buf.readBoolean() ? buf.readItem() : null;
        return new GenericCraftPacket(recipeId, preview, forced, dim, pos, repeatCount, inferMode, baseItem);
    }

    public static void handle(GenericCraftPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        RSIntegrationMod.LOGGER.debug("[RSI-Generic] handle() ENTRY: recipeId={} preview={} dim={} pos={} repeat={}",
                packet.recipeId, packet.preview, packet.dim, packet.pos, packet.repeatCount);
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Generic] handle() DROP: player is null, recipeId={}", packet.recipeId);
            context.setPacketHandled(true);
            return;
        }
        if (player instanceof net.minecraftforge.common.util.FakePlayer) {
            RSIntegrationMod.LOGGER.warn("[RSI-Generic] handle() DROP: FakePlayer, recipeId={}", packet.recipeId);
            context.setPacketHandled(true);
            return;
        }
        // Supplementary: catch fake players that extend ServerPlayer directly
        // without implementing FakePlayer (some mods do this).
        if (player.getServer() != null
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
            RSIntegrationMod.LOGGER.warn("[RSI-Generic] handle() DROP: not in player list (fake?), recipeId={}", packet.recipeId);
            context.setPacketHandled(true);
            return;
        }
        if (packet.preview && PreviewRateLimiter.isRateLimited(player.getUUID())) {
            RSIntegrationMod.LOGGER.warn("[RSI-Generic] handle() DROP: rate-limited, recipeId={} player={}",
                    packet.recipeId, player.getGameProfile().getName());
            context.setPacketHandled(true);
            return;
        }
        if (packet.preview) {
            String cacheKey = player.getUUID() + ":" + packet.recipeId + ":"
                    + packet.forcedRecipes.hashCode() + ":" + packet.repeatCount;
            CachedPlan cached = PLAN_CACHE.get(cacheKey);
            if (cached != null && System.nanoTime() - cached.createdNanos < PLAN_CACHE_TTL_NANOS) {
                RSIntegrationMod.LOGGER.warn("[RSI-Generic] handle() CACHE HIT: replying with cached plan, recipeId={} player={} ageMs={}",
                        packet.recipeId, player.getGameProfile().getName(),
                        (System.nanoTime() - cached.createdNanos) / 1_000_000L);
                BatchCraftNetworkHandler.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new PlanResponsePacket(cached.plan));
                context.setPacketHandled(true);
                return;
            }
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Generic] handle() enqueueWork: recipeId={} preview={}",
                packet.recipeId, packet.preview);
        context.enqueueWork(() -> {
            try {
                if (packet.preview) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Generic] handle() → tryBuildPlan: recipeId={}", packet.recipeId);
                    tryBuildPlan(player, packet.recipeId, packet.forcedRecipes,
                            packet.dim, packet.pos, packet.repeatCount, packet.baseItem);
                } else {
                    RSIntegrationMod.LOGGER.debug("[RSI-Generic] handle() → tryResolve: recipeId={}", packet.recipeId);
                    tryResolve(player, packet.recipeId, packet.dim, packet.pos,
                            packet.repeatCount, packet.inferMode, packet.baseItem);
                }
            } catch (Throwable e) {
                RSIntegrationMod.LOGGER.error("[RSI-Generic] Failed for {}:", packet.recipeId, e);
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                try {
                    player.sendSystemMessage(Component.translatable("rsi.generic.error.craft_failed", reason));
                } catch (Exception ex) {
                    RSIntegrationMod.LOGGER.error("[RSI-Generic] Failed to send error message to player", ex);
                }
            }
        });
        context.setPacketHandled(true);
    }

    // ── execute: resolve ingredients, craft result, give result to player ──

    // ── FA ritual lookup (delegates to FaRitualHelper) ──────────

    /**
     * Look up a recipe from RecipeManager first, then fall back to
     * FARegistries.RITUAL (wrapping the FA Ritual in a FaRitualWrapper).
     */
    @Nullable
    private static Recipe<?> resolveRecipe(ServerLevel level, ResourceLocation recipeId) {
        // Strip JEI pagination prefix if present (e.g. mod:jei.real_path -> mod:real_path)
        recipeId = unwrapJeiId(recipeId);
        Recipe<?> recipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe != null) return recipe;

        // FA ApplyModifierRecipe lives under smithing/ subdirectory in
        // RecipeManager (e.g. forbidden_arcanus:smithing/apply_eternal_modifier)
        // but the JEI fake recipe sends only the synthetic ID
        // (e.g. forbidden_arcanus:apply_eternal_modifier).
        if (ModIds.FORBIDDEN_ARCANUS.equals(recipeId.getNamespace())) {
            recipe = level.getRecipeManager().byKey(
                    new ResourceLocation(recipeId.getNamespace(),
                            "smithing/" + recipeId.getPath())).orElse(null);
            if (recipe != null) return recipe;
        }

        recipe = resolveFARitual(level, recipeId);
        if (recipe != null) return recipe;
        recipe = MarketBatchDelegate.resolveMarketEntry(recipeId);
        if (recipe != null) return recipe;
        recipe = CrabTrapRecipeResolver.resolveRecipe(level, recipeId);
        if (recipe != null) return recipe;

        // Scan FA rituals directly (bypasses cache in case of
        // registry-key vs lookup-key mismatch).  FA's ritual registry
        // is small (typically < 100 entries), so this is safe.
        recipe = FaRitualHelper.resolveFARitualScan(level, recipeId);
        if (recipe != null) return recipe;

        RSIntegrationMod.LOGGER.warn("[RSI-resolveRecipe] All lookups failed for {}", recipeId);
        return null;
    }

    @Nullable
    private static Recipe<?> resolveFARitual(ServerLevel level, ResourceLocation recipeId) {
        Object ritual = FaRitualHelper.getRitualById(recipeId, level);
        if (ritual == null) {
            RSIntegrationMod.LOGGER.debug("[RSI-Generic] FA ritual not found in registry: {}", recipeId);
            return null;
        }
        return FaRitualHelper.wrapFaRitual(recipeId, ritual);
    }

    /** Returns the blockKey keyword that a machine must contain to be
     *  compatible with the given recipe, or {@code null} for unknown types. */
    @javax.annotation.Nullable
    private static String getMachineKeywordForRecipe(Recipe<?> recipe) {
        if (recipe instanceof SmithingTransformRecipe
                || recipe instanceof SmithingTrimRecipe) {
            return "smithing_table";
        }
        if (recipe instanceof AbstractCookingRecipe acr) {
            // JEI category is baked into the recipe; use class name hint first
            String cn = recipe.getClass().getName();
            if (cn.contains("Campfire")) return "campfire";
            // Fallback: cooking type field (BLOCK recipes have isBlastFurnace etc.)
            // AbstractCookingRecipe itself doesn't expose the type, so use known subclasses
            if (cn.contains("Blasting")) return "blast_furnace";
            if (cn.contains("Smoking")) return "smoker";
            return "furnace"; // generic furnace
        }
        if (recipe instanceof StonecutterRecipe) {
            return "stonecutter";
        }
        return null; // non-vanilla or unknown — don't filter
    }

    /** Strip JEI pagination prefix from pseudo-IDs like {@code mod:jei.real_path/page}. */
    private static ResourceLocation unwrapJeiId(ResourceLocation id) {
        if (id == null) return null;
        String path = id.getPath();
        if (!path.startsWith("jei.")) return id;
        String inner = path.substring(4);
        int slash = inner.lastIndexOf('/');
        if (slash > 0 && slash < inner.length() - 1) {
            inner = inner.substring(0, slash);
        }
        return new ResourceLocation(id.getNamespace(), inner);
    }

    private static void tryResolve(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim,
                                   @Nullable net.minecraft.core.BlockPos pos,
                                   int repeatCount, boolean inferMode,
                                   @Nullable ItemStack baseItem) {
        Recipe<?> recipe = resolveRecipe(player.serverLevel(), recipeId);
        if (recipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return;
        }

        // FA ApplyModifierRecipe: no fixed base item, so auto-crafting is impossible.
        // Redirect to opening the smithing table GUI with template & addition pre-filled.
        if (OpenBoundMachineGuiPacket.isFaApplyModifier(recipe)) {
            OpenBoundMachineGuiPacket.openSmithingForFaModifier(player, recipeId, baseItem);
            return;
        }

        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs == null || specs.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.no_ingredients"));
            return;
        }

        // Group non-empty ingredients by item type with total count
        Map<String, IngredientNeed> grouped = new LinkedHashMap<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            String key = spec.ingredient().toJson().toString();
            grouped.computeIfAbsent(key, k -> new IngredientNeed(spec.ingredient(), 0)).count += spec.count();
        }

        ModType modType = ModType.classifyRecipe(recipe);
        INetwork network = resolveNetworkForRecipe(player, dim, pos, modType);

        // Auto-select a bound machine for mod recipes when dim/pos are not
        // explicitly provided (e.g. triggered from RS terminal instead of
        // a specific machine's JEI page).  Without this, mod recipes fall
        // through to the grouped-extraction fallback which bypasses the
        // machine entirely and may fail or give results for free.
        ResourceLocation effectiveDim = dim;
        net.minecraft.core.BlockPos effectivePos = pos;
        if ((effectiveDim == null || effectivePos == null) && !(recipe instanceof CraftingRecipe) && modType != null) {
            String reqKeyword = getMachineKeywordForRecipe(recipe);
            for (var m : AltarBindingRegistry
                    .getBoundMachinesForType(player, modType)) {
                if (reqKeyword != null && m.blockKey() != null && !m.blockKey().contains(reqKeyword))
                    continue;
                effectiveDim = m.dim();
                effectivePos = m.pos();
                if (network == null) {
                    network = resolveNetworkForRecipe(player, effectiveDim, effectivePos, modType);
                }
                break;
            }
        }

        // Smithing recipes don't need a bound machine — they compute results
        // directly via getResultItem().  Skip the async chain path.
        if (!(recipe instanceof CraftingRecipe) && effectiveDim != null && effectivePos != null
                && network != null && RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get()
                && modType != ModType.byId("smithing")) {
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
                            "rsi.generic.error.missing_materials", CraftPacketUtils.formatMissingSummary(missing)));
                    return;
                }
                // Append the mod recipe itself as the final multi-block step
                modSteps.add(new ResolutionStep(recipeId, modType, recipeId, inferMode));
                AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(), network, modSteps);
                final UUID capturedUuidA = player.getUUID();
                final var capturedServerA = player.getServer();
                AsyncCraftManager.getInstance().submit(chain);
                chain.onDone(() -> {
                    AsyncCraftManager.getInstance().remove(chain);
                    ChainRepeatController.scheduleNext(
                            chain, capturedServerA, capturedUuidA, repeatCount, chain.getMachineCount(),
                            (p, rem) -> tryResolve(p, recipeId, dim, pos, rem, inferMode, baseItem));
                });
                player.sendSystemMessage(
                        TextBuilder.translate("rsi.async.chain_started", modSteps.size())
                                .build());
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
                    AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(), network, allSteps);
                    final UUID capturedUuidB = player.getUUID();
                    final var capturedServerB = player.getServer();
                    AsyncCraftManager.getInstance().submit(chain);
                    chain.onDone(() -> {
                        AsyncCraftManager.getInstance().remove(chain);
                        ChainRepeatController.scheduleNext(
                                chain, capturedServerB, capturedUuidB, repeatCount, chain.getMachineCount(),
                                (p, rem) -> tryResolve(p, recipeId, dim, pos, rem, inferMode, baseItem));
                    });
                    player.sendSystemMessage(
                            TextBuilder.translate("rsi.async.chain_started", allSteps.size())
                                    .build());
                    return;
                }
                // All GENERIC steps → execute sync chain
                List<ResolutionStep> execSteps = new ArrayList<>(allSteps);
                execSteps.add(new ResolutionStep(recipeId, ModType.GENERIC,
                        new ResourceLocation("minecraft:crafting")));
                for (int r = 0; r < repeatCount; r++) {
                    if (!CraftPacketUtils.executeCraftingSteps(player, execSteps, network)) {
                        RSIntegrationMod.LOGGER.warn("[RSI-Generic] executeCraftingSteps (Path1) returned false for {} (iteration {}/{})", recipeId, r + 1, repeatCount);
                        player.sendSystemMessage(Component.translatable(
                                "rsi.generic.error.craft_failed", "Intermediate crafting failed"));
                        break;
                    }
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
                    AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(), network, planSteps);
                    final UUID capturedUuidC = player.getUUID();
                    final var capturedServerC = player.getServer();
                    AsyncCraftManager.getInstance().submit(chain);
                    chain.onDone(() -> {
                        AsyncCraftManager.getInstance().remove(chain);
                        ChainRepeatController.scheduleNext(
                                chain, capturedServerC, capturedUuidC, repeatCount, chain.getMachineCount(),
                                (p, rem) -> tryResolve(p, recipeId, dim, pos, rem, inferMode, baseItem));
                    });
                    player.sendSystemMessage(
                            TextBuilder.translate("rsi.async.chain_started", planSteps.size())
                                    .build());
                    return;
                }
                List<ResolutionStep> execSteps2 = new ArrayList<>(planSteps);
                execSteps2.add(new ResolutionStep(recipeId, ModType.GENERIC,
                        new ResourceLocation("minecraft:crafting")));
                for (int r = 0; r < repeatCount; r++) {
                    if (!CraftPacketUtils.executeCraftingSteps(player, execSteps2, network)) {
                        RSIntegrationMod.LOGGER.warn("[RSI-Generic] executeCraftingSteps (Path2) returned false for {} (iteration {}/{})", recipeId, r + 1, repeatCount);
                        player.sendSystemMessage(Component.translatable(
                                "rsi.generic.error.craft_failed", "Intermediate crafting failed"));
                        break;
                    }
                }
                return;
            }
            // planSteps=0 with missing=[] means everything is directly available; not a failure
            if (!missingCheck.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Generic] Unified resolver failed for {}: planSteps={} missing={}",
                        recipeId, planSteps != null ? planSteps.size() : "null", missingCheck);
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.missing_materials", CraftPacketUtils.formatMissingSummary(missingCheck)));
                return;
            }

            // NBT-dependent recipes (e.g. SlashBlade) must go through
            // executeCraftingSteps so assemble() is called instead of the
            // legacy getResultItem() path which returns a bare, stat-less item.
            if (CraftPacketUtils.isSlashBladeRecipe(cr2)) {
                List<ResolutionStep> soloSteps = List.of(
                        new ResolutionStep(recipeId, ModType.GENERIC,
                                new ResourceLocation("minecraft:crafting")));
                for (int r = 0; r < repeatCount; r++) {
                    if (!CraftPacketUtils.executeCraftingSteps(player, soloSteps, network)) {
                        RSIntegrationMod.LOGGER.warn("[RSI-Generic] SlashBlade solo executeCraftingSteps failed for {} (iteration {}/{})", recipeId, r + 1, repeatCount);
                        player.sendSystemMessage(Component.translatable(
                                "rsi.generic.error.craft_failed", "SlashBlade crafting failed"));
                        return;
                    }
                }
                return;
            }

            RSIntegrationMod.LOGGER.debug("[RSI-Generic] Unified resolver: nothing to resolve for {}, falling through to direct extraction", recipeId);
        }

        // Guard: non-CraftingRecipe mod recipes REQUIRE a bound machine.
        // Falling through to grouped extraction would consume items without
        // actually running the machine crafting.
        // Exception: smithing recipes can produce results directly via
        // getResultItem() (e.g. netherite upgrade) without a machine.
        if (!(recipe instanceof CraftingRecipe) && modType != null && modType != ModType.GENERIC
                && modType != ModType.byId("smithing")
                && (effectiveDim == null || effectivePos == null)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.no_bound_machine", modType.id()));
            return;
        }

        // Re-resolve network in case the top-level resolution failed but
        // ensureMaterialAvailable succeeded via binding/NBT fallback internally.
        // The ledger's NETWORK entries need a valid network for commit extraction.
        network = network != null ? network
                : CraftPacketUtils.resolveNetworkForCraft(player,
                        player.serverLevel().dimension(), player.blockPosition());

        // Resolve intermediate crafting steps for smithing recipes.
        // Smithing skips the CraftingRecipe-only paths above, so intermediates
        // (e.g. diamond from diamond block) would not be crafted otherwise.
        if (modType == ModType.byId("smithing") && network != null
                && RSIntegrationConfig.ENABLE_MULTIBLOCK_AUTO_CRAFTING.get()) {
            List<Ingredient> smithingIngredients = new ArrayList<>();
            for (IngredientSpec spec : specs) {
                if (!spec.isEmpty()) smithingIngredients.add(spec.ingredient());
            }
            if (!smithingIngredients.isEmpty()) {
                Map<StackKey, Integer> avail = MaterialSources.listAllAvailable(player, network);
                List<String> missing = new ArrayList<>();
                List<ResolutionStep> interSteps = CraftingResolver.resolveStepsForIngredientsWithTypes(
                        smithingIngredients, avail, player.serverLevel(), player, network, missing);
                if (interSteps != null && !interSteps.isEmpty() && missing.isEmpty()) {
                    if (interSteps.stream().allMatch(s -> s.modType() == ModType.GENERIC)) {
                        if (!CraftPacketUtils.executeCraftingSteps(player, interSteps, network)) {
                            player.sendSystemMessage(Component.translatable(
                                    "rsi.generic.error.craft_failed", "Smithing intermediate failed"));
                            return;
                        }
                    }
                }
            }
        }

        for (int r = 0; r < repeatCount; r++) {
            List<ItemStack> allExtracted = new ArrayList<>();
            ExtractionLedger ledger = new ExtractionLedger();
            boolean extractionIncomplete = false;

            // Plan all extractions atomically — nothing physically moved yet
            for (IngredientNeed need : grouped.values()) {
                ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                        player, player.serverLevel().dimension(),
                        player.blockPosition(), need.ingredient, need.count, ledger);
                if (reserved.isEmpty()) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Generic] Grouped extraction failed for {}: missing {} (needed {}) (iteration {}/{})",
                            recipeId, CraftPacketUtils.describeIngredient(need.ingredient), need.count, r + 1, repeatCount);
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials",
                            CraftPacketUtils.describeIngredient(need.ingredient)));
                    extractionIncomplete = true;
                    break;
                }
                allExtracted.add(reserved.copy());
            }

            if (extractionIncomplete) {
                ledger.rollback(player);
                break;
            }

            // Commit all extractions atomically
            if (!ledger.commit(network, player)) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.craft_failed", "Extraction commit failed"));
                break;
            }

            // Craft the final recipe — materials were paid from RS, give the result
            ItemStack result = RecipeIndex.tryGetResultItem(recipe, player.serverLevel().registryAccess());
            if (result.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Generic] Result unavailable for {} ({})",
                        recipeId, recipe.getClass().getSimpleName());
            }

            if (!result.isEmpty()) {
                if (network != null) {
                    ItemStack leftover = network.insertItem(result.copy(), result.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    if (!leftover.isEmpty()) {
                        safeGiveToPlayer(player, leftover);
                    }
                } else {
                    safeGiveToPlayer(player, result);
                }
                player.displayClientMessage(
                        Component.translatable("rsi.generic.info.resolved", result.getCount()), true);
                // Return CT catalyst items (e.g. .reuse(), .transformDamage()) to RS
                if (recipe instanceof CraftingRecipe cr && network != null) {
                    for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(cr)) {
                        if (!remainder.isEmpty()) {
                            ItemStack leftover = network.insertItem(remainder.copy(),
                                    remainder.getCount(),
                                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                            var tracker = network.getItemStorageTracker();
                            if (tracker != null) tracker.changed(player, remainder.copy());
                            if (!leftover.isEmpty()) {
                                safeGiveToPlayer(player, leftover);
                            }
                        }
                    }
                }
            } else {
                // Failed to get result — refund actual extracted materials
                if (network != null) {
                    for (ItemStack refundStack : allExtracted) {
                        if (refundStack.isEmpty()) continue;
                        ItemStack refund = refundStack.copy();
                        ItemStack leftover = network.insertItem(refund, refund.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                        var tracker = network.getItemStorageTracker();
                        if (tracker != null) tracker.changed(player, refund.copy());
                        if (!leftover.isEmpty()) {
                            safeGiveToPlayer(player, leftover);
                        }
                    }
                }
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.craft_failed", "Result unavailable"));
                break;
            }
            RSIntegrationMod.LOGGER.debug("[RSI-Generic] Crafted {} (iteration {}/{}) for {}",
                    result.getCount(), r + 1, repeatCount, recipeId);
        }
    }

    @Nullable
    private static INetwork resolveNetworkForRecipe(ServerPlayer player,
            @Nullable ResourceLocation dim, @Nullable net.minecraft.core.BlockPos pos,
            @Nullable ModType modType) {
        // 1. Try primary machine (from packet/JEI).
        // Validate that the player has a binding to this position before
        // resolving the RS network — prevents coordinate-spoofing exploits
        // where a malicious client sends another player's machine coordinates.
        if (dim != null && pos != null) {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dim);
            if (AltarBindingRegistry.isBound(key, pos, player)) {
                INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, key, pos);
                if (network != null) return network;
            }
        }
        // 2. Fallback to player inventory / nearby RS node
        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network != null) return network;
        // 3. For mod recipes: try all bound machines of matching type
        if (modType != null && modType != ModType.GENERIC) {
            for (AltarBindingRegistry.BoundMachine m :
                    AltarBindingRegistry.getBoundMachinesForType(player, modType)) {
                if (m.dim().equals(dim) && m.pos().equals(pos)) continue;
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, m.dim());
                network = AltarBindingRegistry.resolveNetworkForAltar(player, key, m.pos());
                if (network != null) return network;
            }
        }
        return null;
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
                                      @Nullable net.minecraft.core.BlockPos pos,
                                      int repeatCount,
                                      @Nullable ItemStack baseItem) {
        Recipe<?> recipe = resolveRecipe(player.serverLevel(), recipeId);
        if (recipe == null) {
            sendPlanError(player, Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()).getString());
            return;
        }

        // FA ApplyModifierRecipe: if JEI provides a base item, build a full
        // recursive plan so the player can see what materials are needed.
        // Without a base item (e.g. manual /craft command), redirect to the
        // smithing table GUI instead.
        boolean faRecipe = OpenBoundMachineGuiPacket.isFaApplyModifier(recipe);
        if (faRecipe && (baseItem == null || baseItem.isEmpty())) {
            sendPlanError(player, Component.translatable("rsi.generic.error.fa_open_smithing").getString());
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

        // Un-expanded ingredients for PlanStep display (avoids 64× icon spam).
        List<Ingredient> displayIngredients;

        if (faRecipe) {
            // Build ingredient specs from FA recipe: template + baseItem + addition
            try {
                java.lang.reflect.Method getTemplate = recipe.getClass().getMethod("getTemplate");
                java.lang.reflect.Method getAddition = recipe.getClass().getMethod("getAddition");
                Ingredient template = (Ingredient) getTemplate.invoke(recipe);
                Ingredient addition = (Ingredient) getAddition.invoke(recipe);

                Ingredient baseIngredient = Ingredient.of(baseItem);
                List<IngredientSpec> specs = new ArrayList<>();
                if (!template.isEmpty()) specs.add(new IngredientSpec(template, 1));
                specs.add(new IngredientSpec(baseIngredient, 1));
                if (!addition.isEmpty()) specs.add(new IngredientSpec(addition, 1));

                List<Ingredient> perRecipe = new ArrayList<>();
                List<Ingredient> expanded = new ArrayList<>();
                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    perRecipe.add(spec.ingredient());
                    for (int i = 0; i < spec.count() * repeatCount; i++) expanded.add(spec.ingredient());
                }
                displayIngredients = perRecipe;
                recipeIngredients = expanded;
                // Apply modifier to the JEI-provided base item so the plan
                // shows the actual modified output (e.g. sword + eternal),
                // not the unmodified base material.
                targetOutput = baseItem.copy();
                try {
                    java.lang.reflect.Method getModifier = recipe.getClass().getMethod("getModifier");
                    Object modifier = getModifier.invoke(recipe);
                    if (modifier != null) {
                        Class<?> helperClass = Class.forName(
                                "com.stal111.forbidden_arcanus.common.item.modifier.ModifierHelper");
                        java.lang.reflect.Method setModifier = null;
                        for (java.lang.reflect.Method m : helperClass.getMethods()) {
                            if (m.getName().equals("setModifier")
                                    && m.getParameterCount() == 2
                                    && m.getParameterTypes()[0].isAssignableFrom(ItemStack.class)) {
                                setModifier = m;
                                break;
                            }
                        }
                        if (setModifier != null) {
                            setModifier.invoke(null, targetOutput, modifier);
                        }
                    }
                } catch (Exception ex) {
                    RSIntegrationMod.LOGGER.warn("[RSI-tryBuildPlan] Failed to apply FA modifier", ex);
                }
                recipeModType = ModType.byId("smithing");
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-tryBuildPlan] FA reflection failed", e);
                sendPlanError(player, Component.translatable("rsi.generic.error.fa_open_smithing").getString());
                return;
            }
        } else if (recipe instanceof CraftingRecipe cr) {
            List<Ingredient> raw = cr.getIngredients();
            displayIngredients = raw;
            recipeIngredients = new ArrayList<>(raw.size() * repeatCount);
            for (int r = 0; r < repeatCount; r++) recipeIngredients.addAll(raw);
            targetOutput = cr.getResultItem(player.serverLevel().registryAccess());
        } else {
            List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
            if (specs == null || specs.isEmpty()) {
                sendPlanError(player, Component.translatable("rsi.generic.error.no_ingredients").getString());
                return;
            }
            List<Ingredient> perRecipe = new ArrayList<>();
            List<Ingredient> expanded = new ArrayList<>();
            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                perRecipe.add(spec.ingredient());
                for (int i = 0; i < spec.count() * repeatCount; i++) expanded.add(spec.ingredient());
            }
            displayIngredients = perRecipe;
            recipeIngredients = expanded;
            targetOutput = ModRecipeHandlers.tryGetResultItem(recipe, player.serverLevel().registryAccess());
            recipeModType = ModType.classifyRecipe(recipe);
            RSIntegrationMod.LOGGER.debug("[RSI-tryBuildPlan] targetOutput: recipeId={} class={} result={}x{} isEmpty={} modType={}",
                    recipeId,
                    recipe.getClass().getSimpleName(),
                    targetOutput.isEmpty() ? "EMPTY"
                            : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(targetOutput.getItem()),
                    targetOutput.getCount(), targetOutput.isEmpty(),
                    recipeModType != null ? recipeModType.id() : "null");
            if (targetOutput.isEmpty() && recipeModType != null
                    && !ModIds.ID_EMBERS_ALCHEMY.equals(recipeModType.id())
                    && !ModIds.ID_AETHERWORKS_ANVIL.equals(recipeModType.id())
                    && !ModIds.TOUHOU_LITTLE_MAID.equals(recipeModType.id())
                    && !ModIds.FORBIDDEN_ARCANUS.equals(recipeModType.id())
                    && !ModIds.AETHER.equals(recipeModType.id())
                    && !ModIds.ID_YHK_KETTLE.equals(recipeModType.id())
                    && !ModIds.ID_FR_KETTLE.equals(recipeModType.id())) {
                sendPlanError(player, Component.translatable("rsi.generic.error.unsupported_machine", recipe.getClass().getSimpleName()).getString());
                return;
            }
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

        final String cacheKey = player.getUUID() + ":" + recipeId + ":"
                + (forcedOverrides != null ? forcedOverrides.hashCode() : "0") + ":"
                + repeatCount;
        CachedPlan cached = PLAN_CACHE.get(cacheKey);
        if (cached != null && System.nanoTime() - cached.createdNanos < PLAN_CACHE_TTL_NANOS) {
            RSIntegrationMod.LOGGER.debug("[RSI-tryBuildPlan] Cache hit: recipeId={}", recipeId);
            BatchCraftNetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new PlanResponsePacket(cached.plan));
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
        RSIntegrationMod.LOGGER.debug("[RSI-OR] tryBuildPlan recipe={} typedResolver={} steps={} alts={}",
                recipeId, usedTypedResolver,
                resolutionSteps != null ? resolutionSteps.size() : -1,
                resolutionSteps != null ? resolutionSteps.stream()
                        .filter(rs -> !rs.alternativeIds().isEmpty()).count() : -1);
        if (resolutionSteps != null) {
            for (ResolutionStep rs : resolutionSteps) {
                if (!rs.alternativeIds().isEmpty()) {
                    RSIntegrationMod.LOGGER.debug("[RSI-OR]   step {} has {} alternatives: {}",
                            rs.recipeId(), rs.alternativeIds().size(), rs.alternativeIds());
                }
            }
        }
        if (resolutionSteps == null || resolutionSteps.isEmpty()) {
            List<ItemStack> availableStacks = new ArrayList<>();
            for (var e : available.entrySet()) {
                ItemStack s = new ItemStack(e.getKey().item(), e.getValue());
                if (e.getKey().tag() != null) {
                    try { s.setTag(net.minecraft.nbt.TagParser.parseTag(e.getKey().tag())); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed for key {}", e.getKey(), ex); }
                }
                availableStacks.add(s);
            }
            stepIds = CraftingResolver.resolveStepsForIngredients(
                    recipeIngredients, availableStacks, player.serverLevel(), missing, forcedOverrides);
        } else {
            stepIds = resolutionSteps.stream()
                    .map(ResolutionStep::recipeId).collect(Collectors.toList());
        }

        // Diagnostic: log stepId distribution before dedup
        Map<ResourceLocation, Integer> diagStepCounts = new LinkedHashMap<>();
        for (ResourceLocation id : stepIds) diagStepCounts.merge(id, 1, Integer::sum);
        RSIntegrationMod.LOGGER.debug("[RSI-plan] stepIds: total={} unique={} | {}",
                stepIds.size(), diagStepCounts.size(), diagStepCounts);

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

        // Merge only consecutive identical recipe IDs to preserve dependency order.
        // A global merge (e.g. LinkedHashMap.merge) collapses non-adjacent
        // occurrences and can reorder steps (e.g. [A, B, A, C] → [A:2, B:1, C:1]
        // vs correct consecutive: [A:1, B:1, A:1, C:1]).
        List<PlanStep> steps = new ArrayList<>();

        List<ResourceLocation> mergedStepIds = new ArrayList<>();
        List<Integer> mergedBatchCounts = new ArrayList<>();
        for (ResourceLocation id : stepIds) {
            int lastIdx = mergedStepIds.size() - 1;
            if (lastIdx >= 0 && mergedStepIds.get(lastIdx).equals(id)) {
                mergedBatchCounts.set(lastIdx, mergedBatchCounts.get(lastIdx) + 1);
            } else {
                mergedStepIds.add(id);
                mergedBatchCounts.add(1);
            }
        }

        for (int si = 0; si < mergedStepIds.size(); si++) {
            ResourceLocation stepId = mergedStepIds.get(si);
            int batches = mergedBatchCounts.get(si);
            Recipe<?> stepRecipe = resolveRecipe(player.serverLevel(), stepId);
            if (stepRecipe == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-Generic] Plan step recipe not found: {}",
                        stepId);
                continue;
            }
            ItemStack output = RecipeIndex.tryGetResultItem(
                    stepRecipe, player.serverLevel().registryAccess());
            if (output.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Generic] Plan step output empty: {} ({})",
                        stepId, stepRecipe.getClass().getSimpleName());
                continue;
            }
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
                            ItemStack matched = matchAndConsume(ing, displayAvailable);
                            inputs.add(matched != null ? matched : firstValidDisplayItem(ing));
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
                        ItemStack matched = matchAndConsume(ing, displayAvailable);
                        inputs.add(matched != null ? matched : firstValidDisplayItem(ing));
                    }
                }
            } else {
                // Multi-block recipe: use extractIngredientSpecs to preserve
                // per-ingredient counts (extractIngredients drops counts and
                // returns empty for wrappers like FaRitualWrapper).
                List<IngredientSpec> modSpecs = CraftPacketUtils.extractIngredientSpecs(stepRecipe);
                if (modSpecs != null) {
                    for (IngredientSpec spec : modSpecs) {
                        if (spec.isEmpty()) continue;
                        int cnt = spec.count();
                        ItemStack matched = matchAndConsume(spec.ingredient(), displayAvailable);
                        ItemStack display = matched != null ? matched.copy() : firstValidDisplayItem(spec.ingredient());
                        display.setCount(cnt);
                        for (int c = 1; c < cnt; c++) matchAndConsume(spec.ingredient(), displayAvailable);
                        inputs.add(display);
                    }
                }
            }

            // Extract alternatives from the resolver's own candidate analysis.
            // Filter by material availability so the OR badge is only shown for
            // recipes the player can actually use.
            List<ResourceLocation> alternatives = new ArrayList<>();
            List<String> alternativeModTypes = new ArrayList<>();
            ResolutionStep rs = stepByRecipe.get(stepId);
            RSIntegrationMod.LOGGER.debug("[RSI-OR] buildStep {}: stepByRecipe has rs={} alternatives={}",
                    stepId, rs != null,
                    rs != null ? rs.alternativeIds().size() : -1);
            if (rs != null && !rs.alternativeIds().isEmpty()) {
                for (int i = 0; i < rs.alternativeIds().size(); i++) {
                    ResourceLocation altId = rs.alternativeIds().get(i);
                    String altMod = i < rs.alternativeModTypes().size()
                            ? rs.alternativeModTypes().get(i)
                            : rs.modType().id();
                    Recipe<?> altRecipe = resolveRecipe(player.serverLevel(), altId);
                    if (altRecipe == null) {
                        RSIntegrationMod.LOGGER.debug("[RSI-OR]   alt {} NOT FOUND in recipe manager", altId);
                        continue;
                    }
                    List<Ingredient> altIngs;
                    if (altRecipe instanceof CraftingRecipe cr) {
                        altIngs = cr.getIngredients();
                    } else {
                        altIngs = CraftPacketUtils.extractIngredients(altRecipe);
                    }
                    boolean hasMats = altIngs != null && recipeHasSomeMaterials(altIngs, itemAvailable, recipeIndex);
                    boolean hasMachine = true;
                    if (hasMats && altMod != null) {
                        ModType altType = ModType.byId(altMod);
                        if (altType != null && altType != ModType.GENERIC) {
                            hasMachine = AltarBindingRegistry.hasAnyBindingForType(player, altType);
                        }
                    }
                    RSIntegrationMod.LOGGER.debug("[RSI-OR]   alt {}: ingCount={} hasMaterials={} hasMachine={}",
                            altId, altIngs != null ? altIngs.size() : -1, hasMats, hasMachine);
                    if (hasMats && hasMachine) {
                        alternatives.add(altId);
                        alternativeModTypes.add(altMod);
                    }
                }
            }
            if (alternatives.isEmpty()) { alternatives = Collections.emptyList(); alternativeModTypes = Collections.emptyList(); }

            ModType mt = modTypeByRecipe.get(stepId);
            recipeWidths.put(stepId, recipeW);
            recipeHeights.put(stepId, recipeH);
            steps.add(new PlanStep(stepId, output, batches, inputs, alternatives, mt,
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
        List<String> modWarnings = new ArrayList<>();
        {
            List<ItemStack> targetInputs = new ArrayList<>();
            int targetW = 0, targetH = 0;
            if (faRecipe) {
                // FA ApplyModifierRecipe: use the pre-built displayIngredients
                // (template + baseItem + addition) so the JEI-extracted base
                // item is visible in the target step's input grid.
                for (Ingredient ing : displayIngredients) {
                    if (ing.isEmpty()) continue;
                    ItemStack matched = matchAndConsume(ing, displayAvailable);
                    ItemStack display = matched != null ? matched.copy() : firstValidDisplayItem(ing);
                    targetInputs.add(display);
                }
            } else if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
                targetW = shaped.getWidth();
                targetH = shaped.getHeight();
                for (Ingredient ing : displayIngredients) {
                    if (ing.isEmpty()) {
                        targetInputs.add(ItemStack.EMPTY);
                    } else {
                        ItemStack matched = matchAndConsume(ing, displayAvailable);
                        targetInputs.add(matched != null ? matched
                                : firstValidDisplayItem(ing));
                    }
                }
            } else if (recipe instanceof CraftingRecipe) {
                int n = 0;
                for (Ingredient ing : displayIngredients) { if (!ing.isEmpty()) n++; }
                if (n <= 3) { targetW = n; targetH = 1; }
                else if (n <= 4) { targetW = 2; targetH = 2; }
                else { targetW = 3; targetH = 3; }
                for (Ingredient ing : displayIngredients) {
                    if (ing.isEmpty()) continue;
                    ItemStack matched = matchAndConsume(ing, displayAvailable);
                    targetInputs.add(matched != null ? matched
                            : firstValidDisplayItem(ing));
                }
            } else {
                // Mod recipe: linear layout — re-read specs for per-ingredient counts
                // (displayIngredients is no longer unrolled per-unit).
                List<IngredientSpec> targetSpecs = CraftPacketUtils.extractIngredientSpecs(recipe);
                if (targetSpecs != null) {
                    for (IngredientSpec spec : targetSpecs) {
                        if (spec.isEmpty()) continue;
                        int cnt = spec.count();
                        ItemStack matched = matchAndConsume(spec.ingredient(), displayAvailable);
                        ItemStack display = matched != null ? matched.copy() : firstValidDisplayItem(spec.ingredient());
                        display.setCount(cnt);
                        for (int c = 1; c < cnt; c++) matchAndConsume(spec.ingredient(), displayAvailable);
                        targetInputs.add(display);
                    }
                }
            }
            int targetDepth = 0;
            for (PlanStep s : steps) targetDepth = Math.max(targetDepth, s.depth() + 1);

            // Collect OR alternatives for the target recipe itself from RecipeIndex.
            // Must check NBT so tacz:attachment variants (bracelet_zenith vs
            // muzzle_brake_pioneer) aren't cross-contaminated — they share the
            // same base Item but have different NBT and are different products.
            List<ResourceLocation> targetAlts = new ArrayList<>();
            List<String> targetAltModTypes = new ArrayList<>();
            List<RecipeIndex.Entry> targetEntries = recipeIndex.get(targetOutput.getItem());
            boolean targetHasTag = targetOutput.hasTag();
            if (targetEntries != null) {
                for (RecipeIndex.Entry e : targetEntries) {
                    if (e.recipe().getId().equals(recipeId)) continue;
                    // NBT guard: skip alternatives whose output NBT differs from target
                    if (targetHasTag) {
                        ItemStack altOut;
                        if (e.recipe() instanceof CraftingRecipe cr) {
                            altOut = cr.getResultItem(player.serverLevel().registryAccess());
                        } else {
                            altOut = ModRecipeHandlers.tryGetResultItem(e.recipe(), player.serverLevel().registryAccess());
                        }
                        if (!ItemStack.isSameItemSameTags(altOut, targetOutput)) continue;
                    }
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
            RSIntegrationMod.LOGGER.debug("[RSI-OR] target step {}: {} alternatives from index",
                    recipeId, targetAlts.size());

            // Collect plan-time mod warnings (Goety research/structure, FA essences).
            // These render in the "unavailable" area, not inside step cards.
            if (recipeModType != null) {
                modWarnings.addAll(PlanWarnings.collect(recipeModType.id(), player, recipe, dim, pos));
            }

            steps.add(new PlanStep(recipeId, targetOutput, repeatCount, targetInputs,
                    targetAlts, recipeModType, targetDepth, !targetAlts.isEmpty(),
                    targetW, targetH, targetAltModTypes, Collections.emptyList()));

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
            ItemStack matched = matchAndConsume(ing, matAvailable);
            if (matched != null) {
                neededCounts.merge(matched.getItem(), 1, Integer::sum);
                itemSource.putIfAbsent(matched.getItem(), ing);
            }
        }
        for (PlanStep step : steps) {
            // Target recipe: only subtract its output (inputs already counted
            // in the recipeIngredients loop above, don't double-count)
            if (step.recipeId().equals(recipeId)) {
                neededCounts.merge(step.output().getItem(), -step.totalOutputCount(), Integer::sum);
                continue;
            }
            Recipe<?> stepRecipe = resolveRecipe(player.serverLevel(), step.recipeId());
            if (stepRecipe == null) continue;
            List<Ingredient> stepIngs;
            if (stepRecipe instanceof CraftingRecipe scr) {
                stepIngs = scr.getIngredients();
            } else {
                stepIngs = CraftPacketUtils.extractIngredients(stepRecipe);
            }
            // Fallback: some mod recipes (e.g. CrockPot) don't expose
            // getIngredients() — use extractIngredientSpecs which goes through
            // the registered ModRecipeHandler and preserves per-ingredient counts.
            if (stepIngs == null) {
                List<IngredientSpec> fallbackSpecs = CraftPacketUtils.extractIngredientSpecs(stepRecipe);
                if (fallbackSpecs != null) {
                    for (IngredientSpec spec : fallbackSpecs) {
                        if (spec.isEmpty()) continue;
                        ItemStack matched = matchAndConsume(spec.ingredient(), matAvailable);
                        if (matched != null) {
                            neededCounts.merge(matched.getItem(),
                                    spec.count() * step.batches(), Integer::sum);
                            itemSource.putIfAbsent(matched.getItem(), spec.ingredient());
                        }
                    }
                }
            } else {
                for (Ingredient ing : stepIngs) {
                    if (ing.isEmpty()) continue;
                    ItemStack matched = matchAndConsume(ing, matAvailable);
                    if (matched != null) {
                        neededCounts.merge(matched.getItem(), step.batches(), Integer::sum);
                        itemSource.putIfAbsent(matched.getItem(), ing);
                    }
                }
            }
        }
        // Subtract what intermediate steps produce (skip target — already done above).
        for (PlanStep step : steps) {
            if (step.recipeId().equals(recipeId)) continue;
            neededCounts.merge(step.output().getItem(), -step.totalOutputCount(), Integer::sum);
        }

        // FA smithing: the target output shares the baseItem's Item type, so the
        // target-output subtraction above canceled out the baseItem.  Add it back
        // so the material panel shows the base item as a required material.
        if (faRecipe && baseItem != null && !baseItem.isEmpty()) {
            neededCounts.merge(baseItem.getItem(), repeatCount, Integer::sum);
        }

        // Add fuel requirement for machine recipes that consume fuel items.
        // CrockPot: any burnable item (coal, charcoal, etc.)
        // Aether: machine-specific fuel — fuel type depends on the machine BE,
        //   so we skip it here and rely on getPlanWarnings() to inform the user.
        CrockPotBatchDelegate.addFuelIfNeeded(
                recipeModType != null ? recipeModType.id() : null,
                itemAvailable, itemSource, neededCounts, repeatCount);
        // EnchantalCooler: lapis lazuli or fuel tag items
        EnchantalCoolerBatchDelegate.addFuelIfNeeded(
                recipeModType != null ? recipeModType.id() : null,
                itemAvailable, itemSource, neededCounts, repeatCount);
        CookingPotBatchDelegate.addFuelIfNeeded(
                recipeModType != null ? recipeModType.id() : null,
                itemAvailable, itemSource, neededCounts, repeatCount);
        MokaPotBatchDelegate.addFuelIfNeeded(
                recipeModType != null ? recipeModType.id() : null,
                itemAvailable, itemSource, neededCounts, repeatCount);

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
                    totalNeeded += Math.max(0, neededCounts.getOrDefault(optItem, 0));
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

        String targetName = targetOutput.getHoverName().getString();

        // ── Embers Alchemy: lookup cached codes from prior inference ──
        EmbersPlanInfo embersInfo = EmbersPlanInfo.build(
                player, recipe, network, recipeId,
                recipeModType != null ? recipeModType.id() : null,
                dim, pos);

        boolean executionMachineSupportsGui = false;
        if (dim != null && pos != null) {
            ServerLevel execLevel = player.getServer().getLevel(
                    ResourceKey.create(Registries.DIMENSION, dim));
            if (execLevel != null) {
                executionMachineSupportsGui = BindingEventHandler
                        .supportsGuiAt(execLevel, pos);
            }
        }

        PlanResponse plan = new PlanResponse(
                feasible,
                targetName,
                targetOutput,
                steps,
                materials,
                dedupedMissing,
                recipeId.toString(),
                recipeModType != null ? recipeModType.id() : null,
                dim != null ? dim.toString() : null,
                pos != null ? pos.getX() : 0,
                pos != null ? pos.getY() : 0,
                pos != null ? pos.getZ() : 0,
                modWarnings,
                repeatCount,
                embersInfo.code(),
                embersInfo.aspectNames(),
                embersInfo.inputNames(),
                embersInfo.seed(),
                embersInfo.canInfer(),
                embersInfo.codeFromCache(),
                executionMachineSupportsGui,
                baseItem
        );

        PLAN_CACHE.put(cacheKey, new CachedPlan(plan, System.nanoTime()));
        // Prune stale entries if cache grows too large
        if (PLAN_CACHE.size() > 64) {
            long cutoff = System.nanoTime() - PLAN_CACHE_TTL_NANOS;
            PLAN_CACHE.values().removeIf(e -> e.createdNanos < cutoff);
        }

        RSIntegrationMod.LOGGER.debug("[RSI-tryBuildPlan] SENDING PlanResponsePacket: recipeId={} steps={} feasible={} player={}",
                recipeId, steps.size(), feasible, player.getGameProfile().getName());
        BatchCraftNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PlanResponsePacket(plan));
        RSIntegrationMod.LOGGER.debug("[RSI-tryBuildPlan] PlanResponsePacket SENT: recipeId={}", recipeId);
    }

    /**
     * Returns the best ItemStack from {@code ingredient.getItems()} — the one with
     * the highest available count in player inventory + RS network.
     * Preserves NBT so TACZ/Applied Armorer items display with correct attachments.
     * Strips BlockEntityTag/BlockId to avoid purple-black block entity rendering.
     */
    @Nullable
    private static ItemStack matchBestAvailable(Ingredient ingredient, Map<Item, Integer> itemAvailable) {
        ItemStack best = null;
        int bestCount = -1;
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            int count = itemAvailable.getOrDefault(stack.getItem(), 0);
            if (count > bestCount) {
                bestCount = count;
                best = stack;
            }
        }
        if (best != null) return cleanDisplayNbt(best.copy());
        // Fallback: first non-empty item from the ingredient.
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) return cleanDisplayNbt(stack.copy());
        }
        return null;
    }

    /** Strip BlockEntityTag and BlockId from NBT so items render as items,
     *  not as placed blocks (which lack item models → purple-black). */
    private static ItemStack cleanDisplayNbt(ItemStack stack) {
        if (!stack.hasTag()) return stack;
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove("BlockEntityTag");
            tag.remove("BlockId");
            if (tag.isEmpty()) stack.setTag(null);
        }
        return stack;
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
    private static ItemStack matchAndConsume(Ingredient ingredient, Map<Item, Integer> available) {
        ItemStack matched = matchBestAvailable(ingredient, available);
        if (matched != null) {
            available.merge(matched.getItem(), -1, Integer::sum);
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

    /** Give item to player only if still connected. Prevents ghost items
     *  when a player disconnects mid-batch and the item is voided. */
    private static void safeGiveToPlayer(ServerPlayer player, ItemStack stack) {
        if (player != null && !player.hasDisconnected() && !player.isRemoved()) {
            ItemHandlerHelper.giveItemToPlayer(player, stack);
        }
    }

    private static void sendPlanError(ServerPlayer player, String msg) {
        RSIntegrationMod.LOGGER.warn("[RSI-tryBuildPlan] sendPlanError: recipe={} msg={} player={}",
                "?", msg, player.getGameProfile().getName());
        BatchCraftNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PlanResponsePacket(new PlanResponse(false, "", ItemStack.EMPTY,
                        List.of(), Map.of(), List.of(msg), "", null, null, 0, 0, 0, Collections.emptyList(), 1)));
    }

}
