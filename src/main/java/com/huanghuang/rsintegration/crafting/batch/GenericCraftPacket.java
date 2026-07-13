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
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
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
import com.huanghuang.rsintegration.recipe.CrockPotRecipeHandler;
import com.huanghuang.rsintegration.recipe.WRRecipeHandler;
import com.huanghuang.rsintegration.util.TextBuilder;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.Reflect;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
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
    private final ResourceLocation dim;
    private final net.minecraft.core.BlockPos pos;
    private final int repeatCount;
    private final boolean inferMode;
    /** JEI-provided base item for FA ApplyModifierRecipe prefill */
    private final ItemStack baseItem;
    /**
     * JEI-provided concrete output (ghost slot) the player clicked. Lets the
     * server distinguish NBT-variant outputs that share one recipe id — e.g. WR
     * arcane iterator "Curse II" vs "Curse I" both resolve to the same recipe.
     * Null when the client didn't supply it (backward compatible).
     */
    private final ItemStack targetOutput;

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
        this(recipeId, preview, forcedRecipes, dim, pos, repeatCount, inferMode, baseItem, null);
    }

    /** Master constructor: adds the JEI ghost-output target for NBT-variant recipes. */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              Map<String, String> forcedRecipes,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput) {
        this.recipeId = recipeId;
        this.preview = preview;
        this.forcedRecipes = forcedRecipes != null ? forcedRecipes : Collections.emptyMap();
        this.dim = dim;
        this.pos = pos;
        this.repeatCount = Math.max(1, Math.min(repeatCount, RSIntegrationConfig.REPEAT_COUNT_MAX.get()));
        this.inferMode = inferMode;
        this.baseItem = baseItem != null ? baseItem.copy() : null;
        this.targetOutput = targetOutput != null && !targetOutput.isEmpty() ? targetOutput.copy() : null;
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

    /** JEI-initiated preview carrying the clicked ghost output (NBT-variant target). */
    public GenericCraftPacket(ResourceLocation recipeId, boolean preview,
                              @Nullable ResourceLocation dim,
                              @Nullable net.minecraft.core.BlockPos pos,
                              int repeatCount, boolean inferMode,
                              @Nullable ItemStack baseItem,
                              @Nullable ItemStack targetOutput) {
        this(recipeId, preview, Collections.emptyMap(), dim, pos, repeatCount, inferMode, baseItem, targetOutput);
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
        buf.writeBoolean(targetOutput != null);
        if (targetOutput != null) buf.writeItem(targetOutput);
    }

    public static GenericCraftPacket decode(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        boolean preview = buf.readBoolean();
        int forcedCount = buf.readVarInt();
        // Reject rather than truncate: silently capping the loop at 128 would
        // leave the remaining declared pairs in the buffer, desyncing every
        // subsequent read (dim/pos) for a malicious or corrupt packet.
        if (forcedCount < 0 || forcedCount > 128) {
            throw new io.netty.handler.codec.DecoderException(
                    "GenericCraftPacket forcedCount out of range: " + forcedCount);
        }
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
        // targetOutput appended at tail; guard readability so a shorter buffer
        // from an older client decodes cleanly to null instead of throwing.
        ItemStack targetOutput = null;
        if (buf.isReadable() && buf.readBoolean()) {
            targetOutput = buf.readItem();
        }
        return new GenericCraftPacket(recipeId, preview, forced, dim, pos, repeatCount, inferMode, baseItem, targetOutput);
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
                    + packet.forcedRecipes.hashCode() + ":" + packet.repeatCount + ":"
                    + clickedOutputCacheToken(packet.targetOutput);
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
                            packet.dim, packet.pos, packet.repeatCount, packet.baseItem, packet.targetOutput);
                } else {
                    RSIntegrationMod.LOGGER.debug("[RSI-Generic] handle() → tryResolve: recipeId={} forced={}", packet.recipeId, packet.forcedRecipes.size());
                    tryResolve(player, packet.recipeId, packet.forcedRecipes, packet.dim, packet.pos,
                            packet.repeatCount, packet.inferMode, packet.baseItem, packet.targetOutput);
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

    /** Launch an async craft chain with standard onDone/scheduleNext wiring. */
    private static void launchAsyncChain(ServerPlayer player, List<ResolutionStep> steps,
                                          INetwork network, int repeatCount,
                                          ResourceLocation recipeId,
                                          Map<String, String> forcedRecipes,
                                          @Nullable ResourceLocation dim,
                                          @Nullable net.minecraft.core.BlockPos pos,
                                          boolean inferMode, @Nullable ItemStack baseItem,
                                          @Nullable ItemStack targetOutput) {
        // Amplify mod-step executions by repeatCount so a single chain covers
        // all repeats and the parallel multi-machine path can trigger inside
        // AsyncCraftChain.startModStep.
        List<ResolutionStep> chainSteps = steps;
        boolean amplified = false;
        if (repeatCount > 1) {
            boolean hasMod = false;
            for (ResolutionStep s : steps) {
                if (s.modType() != ModType.GENERIC) { hasMod = true; break; }
            }
            if (hasMod) {
                List<ResolutionStep> amplifiedList = new ArrayList<>(steps.size());
                for (ResolutionStep s : steps) {
                    if (s.modType() != ModType.GENERIC) {
                        amplifiedList.add(new ResolutionStep(s.recipeId(), s.modType(), s.recipeTypeId(),
                                s.alternativeIds(), s.alternativeModTypes(), s.inferMode(),
                                CraftPacketUtils.mulCount(s.executions(), repeatCount),
                                s.syntheticInput(), s.syntheticOutput()));
                    } else {
                        amplifiedList.add(s);
                    }
                }
                chainSteps = amplifiedList;
                amplified = true;
            }
        }
        AsyncCraftChain chain = new AsyncCraftChain(player.getUUID(), player.getServer(), network, chainSteps);
        chain.setTargetOutput(targetOutput);
        final UUID capturedUuid = player.getUUID();
        final var capturedServer = player.getServer();
        AsyncCraftManager.getInstance().submit(chain);
        // When amplification already baked repeatCount into step.executions(),
        // the chain runs all repetitions internally — don't let ChainRepeatController
        // schedule additional repeats.  Pass 1 so that next = 1 - machineCount <= 0
        // and the repeat loop terminates immediately.
        final int effectiveRepeat = amplified ? 1 : repeatCount;
        chain.onDone(() -> {
            AsyncCraftManager.getInstance().remove(chain);
            ChainRepeatController.scheduleNext(
                    chain, capturedServer, capturedUuid, effectiveRepeat, chain.getMachineCount(),
                    (p, rem) -> tryResolve(p, recipeId, forcedRecipes, dim, pos, rem, inferMode, baseItem, targetOutput));
        });
        player.sendSystemMessage(
                TextBuilder.translate("rsi.async.chain_started", chainSteps.size()).build());
    }

    /** Execute a list of GENERIC-only steps synchronously repeatCount times. */
    private static boolean executeSyncLoop(ServerPlayer player, List<ResolutionStep> steps,
                                            INetwork network, ResourceLocation recipeId,
                                            int repeatCount, String failMsg) {
        for (int r = 0; r < repeatCount; r++) {
            if (!CraftPacketUtils.executeCraftingSteps(player, steps, network)) {
                RSIntegrationMod.LOGGER.warn("[RSI-Generic] executeCraftingSteps failed for {} (iteration {}/{})",
                        recipeId, r + 1, repeatCount);
                player.sendSystemMessage(Component.translatable("rsi.generic.error.craft_failed", failMsg));
                return false;
            }
        }
        return true;
    }

    private static void tryResolve(ServerPlayer player, ResourceLocation recipeId,
                                   Map<String, String> forcedRecipes,
                                   @Nullable ResourceLocation dim,
                                   @Nullable net.minecraft.core.BlockPos pos,
                                   int repeatCount, boolean inferMode,
                                   @Nullable ItemStack baseItem,
                                   @Nullable ItemStack targetOutput) {
        // v3.4: convert forced recipe overrides for the resolver (same format as tryBuildPlan).
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
            // CrockPot pure-category recipes carry no fixed ingredient list — the batch delegate
            // selects items by food value at run time (Phase 2). Let these through with an empty
            // spec list so they reach the machine dispatch below; the no-bound-machine guard still
            // blocks genuinely unrunnable cases.
            if (!CrockPotRecipeHandler.hasCategoryConstraints(recipe)) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.no_ingredients"));
                return;
            }
            specs = new ArrayList<>();
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
                        expanded, avail, player.serverLevel(), player, network, missing, forcedOverrides);
                if (!missing.isEmpty()) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials", CraftPacketUtils.formatMissingSummary(missing)));
                    return;
                }
                // Append the mod recipe itself as the final multi-block step
                modSteps.add(new ResolutionStep(recipeId, modType, recipeId, inferMode));
                launchAsyncChain(player, modSteps, network, repeatCount, recipeId, forcedRecipes, dim, pos, inferMode, baseItem, targetOutput);
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
                if (allSteps.stream().anyMatch(s -> s.modType() != ModType.GENERIC
                        || s.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP))) {
                    // Multi-block intermediates → async chain
                    allSteps.add(new ResolutionStep(recipeId, ModType.GENERIC,
                            new ResourceLocation("minecraft:crafting")));
                    launchAsyncChain(player, allSteps, network, repeatCount, recipeId, forcedRecipes, dim, pos, inferMode, baseItem, targetOutput);
                    return;
                }
                // All GENERIC steps → execute sync chain
                List<ResolutionStep> execSteps = new ArrayList<>(allSteps);
                execSteps.add(new ResolutionStep(recipeId, ModType.GENERIC,
                        new ResourceLocation("minecraft:crafting")));
                executeSyncLoop(player, execSteps, network, recipeId, repeatCount, "Intermediate crafting failed");
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
                    player, network, missingCheck, forcedOverrides);
            if (planSteps != null && !planSteps.isEmpty() && missingCheck.isEmpty()) {
                if (planSteps.stream().anyMatch(s -> s.modType() != ModType.GENERIC
                        || s.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP))) {
                    planSteps.add(new ResolutionStep(recipeId, ModType.GENERIC,
                            new ResourceLocation("minecraft:crafting")));
                    launchAsyncChain(player, planSteps, network, repeatCount, recipeId, forcedRecipes, dim, pos, inferMode, baseItem, targetOutput);
                    return;
                }
                List<ResolutionStep> execSteps2 = new ArrayList<>(planSteps);
                execSteps2.add(new ResolutionStep(recipeId, ModType.GENERIC,
                        new ResourceLocation("minecraft:crafting")));
                executeSyncLoop(player, execSteps2, network, recipeId, repeatCount, "Intermediate crafting failed");
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
                        smithingIngredients, avail, player.serverLevel(), player, network, missing, forcedOverrides);
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
            boolean extractionIncomplete = false;

            try (ExtractionLedger ledger = new ExtractionLedger()) {
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
                    break;
                }

                // Commit all extractions atomically
                if (!ledger.commit(network, player)) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.craft_failed", "Extraction commit failed"));
                    break;
                }

                // Craft the final recipe — use assemble() for CraftingRecipe
                // so NBT from inputs (backpack contents, blade stats, etc.)
                // carries forward to the output. getResultItem() returns a
                // bare template that silently discards all stored data.
                ItemStack result;
                if (recipe instanceof CraftingRecipe cr) {
                    // Reconstruct the actual crafting grid layout from the
                    // extracted pool — allExtracted is grouped/deduped, not
                    // slot-aligned. Build a pool copy and split(1) from it so
                    // the 3×3 / 2×2 container matches the recipe pattern.
                    List<ItemStack> pool = new ArrayList<>();
                    for (ItemStack s : allExtracted) pool.add(s.copy());

                    List<Ingredient> ingredients = cr.getIngredients();
                    ItemStack[] consumed = new ItemStack[ingredients.size()];
                    for (int i = 0; i < ingredients.size(); i++) {
                        Ingredient ing = ingredients.get(i);
                        if (ing.isEmpty()) {
                            consumed[i] = ItemStack.EMPTY;
                            continue;
                        }
                        consumed[i] = ItemStack.EMPTY;
                        for (ItemStack p : pool) {
                            if (!p.isEmpty() && ing.test(p)) {
                                consumed[i] = p.split(1);
                                break;
                            }
                        }
                    }
                    result = CraftPacketUtils.assembleCraftingOutput(cr, consumed, player);
                } else {
                    result = RecipeIndex.tryGetResultItem(recipe, player.serverLevel().registryAccess());
                }
                if (result.isEmpty()) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Generic] Result unavailable for {} ({})",
                            recipeId, recipe.getClass().getSimpleName());
                }

                if (!result.isEmpty()) {
                    if (network != null) {
                        // Stamp the storage tracker BEFORE inserting so the crafted
                        // output surfaces at the top of RS's "recently modified" sort
                        // (matches RS's own extract/insert flow). Without this the new
                        // item has no timestamp and the player must hunt for it among
                        // identical stacks.
                        var tracker = network.getItemStorageTracker();
                        if (tracker != null) tracker.changed(player, result.copy());
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
                    // Return crafting remainders (CT .reuse()/.transformDamage(),
                    // and NBT-dependent remainders like Goety's Totem of Souls which
                    // drains charge instead of being consumed). Must feed the ACTUAL
                    // consumed stacks (with NBT) — the no-arg overload uses bare
                    // template items, so a charged totem would come back as a spent
                    // one and the player's charged totem would be silently eaten.
                    //
                    // allExtracted is grouped/deduped (e.g. [gold_block x8, totem x1]),
                    // not slot-aligned. Rebuild a per-slot array of size ingredients()
                    // so getRemainingItems() computes one remainder per real slot.
                    if (recipe instanceof CraftingRecipe cr && network != null) {
                        List<Ingredient> ings = cr.getIngredients();
                        ItemStack[] slotAligned = new ItemStack[ings.size()];
                        List<ItemStack> pool = new ArrayList<>();
                        for (ItemStack s : allExtracted) pool.add(s.copy());
                        for (int i = 0; i < ings.size(); i++) {
                            Ingredient ing = ings.get(i);
                            if (ing.isEmpty()) continue;
                            for (ItemStack p : pool) {
                                if (!p.isEmpty() && ing.test(p)) {
                                    slotAligned[i] = p.copyWithCount(1);
                                    p.shrink(1);
                                    break;
                                }
                            }
                        }
                        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(cr, slotAligned)) {
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
    }

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

    /**
     * Cache-discriminator for the JEI-clicked output stack. The Arcane Iterator's
     * per-level enchant recipes all share one recipeId but produce different books
     * (Curse III vs V), so the clicked output's item+NBT must enter the plan cache
     * key — otherwise previewing V then III returns the stale V tree. Item-only
     * outputs collapse to "0" so non-WR recipes keep their prior cache behavior.
     */
    private static String clickedOutputCacheToken(@Nullable ItemStack clicked) {
        if (clicked == null || clicked.isEmpty()) return "0";
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(clicked.getItem()).toString();
        CompoundTag tag = clicked.getTag();
        return tag != null ? key + "#" + tag : key;
    }

    private static void tryBuildPlan(ServerPlayer player, ResourceLocation recipeId,
                                      Map<String, String> forcedRecipes,
                                      @Nullable ResourceLocation dim,
                                      @Nullable net.minecraft.core.BlockPos pos,
                                      int repeatCount,
                                      @Nullable ItemStack baseItem,
                                      @Nullable ItemStack clickedOutput) {
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

        // Arcane Iterator per-level chain: when the player clicks a level-N enchant
        // book (N>=2), the machine reaches it by leveling a book one step per craft
        // (plain → I → ... → N). levelBooks[k] holds the book at level k+1; a non-null
        // array here drives the target-step block to emit N chained PlanSteps so the
        // plan tree shows the full leveling chain. Null for every other recipe.
        ItemStack[] levelBooks = null;
        // Highest matching intermediate book already present in RS/player storage.
        // Zero means the chain must start from a plain book.
        int iteratorStartLevel = 0;

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
                    for (int i = 0; i < CraftPacketUtils.mulCount(spec.count(), repeatCount); i++) expanded.add(spec.ingredient());
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
            boolean crockCategory = CrockPotRecipeHandler.hasCategoryConstraints(recipe);
            if (crockCategory) {
                // For any category-constrained recipe (pure or mixed), run
                // the food-value-aware selection so the plan preview shows
                // exactly the items the batch delegate will place — both
                // specific MustContain ingredients and filler items.
                net.minecraft.resources.ResourceKey<Level> cpDim = dim != null
                        ? net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, dim)
                        : player.serverLevel().dimension();
                net.minecraft.core.BlockPos cpPos = pos != null ? pos : player.blockPosition();
                INetwork cpNetwork = CraftPacketUtils.resolveNetworkForCraft(player, cpDim, cpPos);
                specs = CrockPotBatchDelegate.buildCategoryPlanIngredients(
                        recipe, cpNetwork, player.serverLevel(), cpPos);
                if (specs == null || specs.isEmpty()) {
                    sendPlanError(player, Component.translatable(
                            "rsi.crockpot.error.food_values").getString());
                    return;
                }
            } else if (specs == null || specs.isEmpty()) {
                sendPlanError(player, Component.translatable(
                        "rsi.generic.error.no_ingredients").getString());
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
                    && !recipeModType.id().startsWith(ModIds.AETHER + "_")
                    && !ModIds.ID_YHK_KETTLE.equals(recipeModType.id())
                    && !ModIds.ID_YHK_FERMENT.equals(recipeModType.id())
                    && !ModIds.ID_FR_KETTLE.equals(recipeModType.id())) {
                sendPlanError(player, Component.translatable("rsi.generic.error.unsupported_machine", recipe.getClass().getSimpleName()).getString());
                return;
            }
        }

        // ── Arcane Iterator per-level chain detection ──
        // All levels of one enchant share this recipeId and declare no static output,
        // so tryGetResultItem above returned the level-I book. If the player clicked a
        // higher level (Curse V), rebuild targetOutput to that level and record the
        // book at every level 1..N so the target-step block can emit N chained steps
        // (each level-k book crafted from the level-(k-1) book + one side set). This
        // makes PlanTreeModel auto-nest the full leveling chain. Guards: WR must be
        // present (buildEnchantedBookOutput returns EMPTY otherwise), the clicked stack
        // must resolve to N>=2, and the recipe must actually be an enchant recipe
        // (arcanum_lens etc. return EMPTY at level 1 → no chain).
        if (clickedOutput != null && !clickedOutput.isEmpty()
                && recipe.getClass().getName().endsWith("ArcaneIteratorRecipe")) {
            int targetLevel = WRRecipeHandler.inferTargetLevel(recipe, clickedOutput);
            if (targetLevel >= 2) {
                ItemStack[] books = new ItemStack[targetLevel];
                boolean ok = true;
                for (int lvl = 1; lvl <= targetLevel; lvl++) {
                    ItemStack book = WRRecipeHandler.buildEnchantedBookOutput(recipe, lvl);
                    if (book.isEmpty()) { ok = false; break; }
                    books[lvl - 1] = book;
                }
                if (ok) {
                    levelBooks = books;
                    targetOutput = books[targetLevel - 1].copy();
                    RSIntegrationMod.LOGGER.debug("[RSI-tryBuildPlan] ArcaneIterator per-level chain: recipeId={} targetLevel={}",
                            recipeId, targetLevel);
                }
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
                + repeatCount + ":" + clickedOutputCacheToken(clickedOutput);
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

        // Arcane Iterator plans should start from the highest matching enchanted
        // book the player already owns, rather than always rebuilding from a plain
        // book. Use the NBT-aware availability snapshot so a different enchantment
        // (or a different level) can never satisfy this shortcut.
        if (levelBooks != null) {
            for (int lvl = levelBooks.length - 1; lvl >= 1; lvl--) {
                ItemStack wanted = levelBooks[lvl - 1];
                int matchingCount = available.entrySet().stream()
                        .filter(e -> ItemStack.isSameItemSameTags(e.getKey().toStack(), wanted))
                        .mapToInt(Map.Entry::getValue).sum();
                if (matchingCount >= repeatCount) {
                    iteratorStartLevel = lvl;
                    break;
                }
            }
            RSIntegrationMod.LOGGER.debug("[RSI-tryBuildPlan] ArcaneIterator start level: {}/{}",
                    iteratorStartLevel, levelBooks.length);
        }

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

        // Aggregate every run of a recipe into one step, keeping first-seen order.
        // Quantity reaches us two ways: the target's ingredients are pre-expanded
        // ×repeatCount (many entries, executions=1 each) and deeper intermediates use
        // inner batching (one entry, executions=N). Summing executions per recipe
        // captures both; counting bare occurrences would drop inner-batched runs and
        // under-scale that sub-recipe — both its tree children (amount = inputCount ×
        // parent.batches) and its material draw. Distinct recipes keep first-seen
        // order; hierarchy comes from the depth pass below (item graph, not list
        // position), and each recipe maps to one output item → one depth, so folding
        // duplicates never crosses depth boundaries.
        List<PlanStep> steps = new ArrayList<>();

        List<ResourceLocation> mergedStepIds = new ArrayList<>();
        List<Integer> mergedBatchCounts = new ArrayList<>();
        List<ResolutionStep> mergedRepresentatives = new ArrayList<>();
        Map<String, Integer> mergeIndex = new HashMap<>();
        int mergeSrcCount = usedTypedResolver ? resolutionSteps.size() : stepIds.size();
        for (int mi = 0; mi < mergeSrcCount; mi++) {
            ResourceLocation id;
            int runs;
            if (usedTypedResolver) {
                ResolutionStep rs = resolutionSteps.get(mi);
                id = rs.recipeId();
                runs = Math.max(1, rs.executions());
            } else {
                id = stepIds.get(mi);
                runs = 1;
            }
            ResolutionStep representative = usedTypedResolver ? resolutionSteps.get(mi) : null;
            String mergeKey = id.toString();
            if (representative != null && representative.syntheticInput() != null
                    && representative.syntheticOutput() != null) {
                mergeKey += "|" + IngredientKey.of(representative.syntheticInput()).hashCode()
                        + "|" + IngredientKey.of(representative.syntheticOutput()).hashCode();
            }
            Integer idx = mergeIndex.get(mergeKey);
            if (idx != null) {
                mergedBatchCounts.set(idx, mergedBatchCounts.get(idx) + runs);
            } else {
                mergeIndex.put(mergeKey, mergedStepIds.size());
                mergedStepIds.add(id);
                mergedBatchCounts.add(runs);
                mergedRepresentatives.add(representative);
            }
        }

        for (int si = 0; si < mergedStepIds.size(); si++) {
            ResourceLocation stepId = mergedStepIds.get(si);
            int batches = mergedBatchCounts.get(si);
            ResolutionStep mergedRs = si < mergedRepresentatives.size() ? mergedRepresentatives.get(si) : null;
            if (mergedRs != null && mergedRs.syntheticInput() != null && mergedRs.syntheticOutput() != null) {
                steps.add(new PlanStep(stepId, mergedRs.syntheticOutput().copy(), batches,
                        List.of(mergedRs.syntheticInput().copy()), Collections.emptyList(), mergedRs.modType(),
                        0, false, 0, 0, Collections.emptyList(), Collections.emptyList()));
                continue;
            }
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
                        // Fallback: some recipes (e.g. BlastingRecipe) may have
                        // their ingredients cached as null; retry via specs path.
                        if (altIngs == null) {
                            List<IngredientSpec> fallback = CraftPacketUtils.extractIngredientSpecs(altRecipe);
                            if (fallback != null) {
                                altIngs = new ArrayList<>();
                                for (IngredientSpec spec : fallback) {
                                    if (!spec.isEmpty()) altIngs.add(spec.ingredient());
                                }
                            }
                        }
                    }
                    boolean hasMats = altIngs != null && recipeHasSomeMaterials(altIngs, itemAvailable, recipeIndex);
                    // Machine gating must match the resolver's own candidate check
                    // (CandidateEngine.isMachineAvailable → hasBindingForRecipe).
                    // hasBindingForRecipe re-classifies the recipe, so vanilla
                    // furnace/blast/smoker/stonecutter recipes — which classifyRecipe
                    // treats as null/GENERIC — are always available and need no bound
                    // machine. The old hasAnyBindingForType(vanilla_furnace) check
                    // wrongly hid those alternatives (e.g. the blast-furnace path for
                    // refined_beeswax_bar), leaving only the stonecutter step with no
                    // switch button even though the resolver could use either.
                    boolean hasMachine = hasMats && AltarBindingRegistry.hasBindingForRecipe(player, altRecipe);
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
                    // Gate by machine binding too — same rule the resolver and the
                    // intermediate-step alternatives use (hasBindingForRecipe: vanilla
                    // machines are always available, mod machines need a binding). Without
                    // this the target offered recipes for unbound machines (cooking pot,
                    // forge ritual, spirit crucible…) that the player can't actually run.
                    if (altIngs != null && recipeHasSomeMaterials(altIngs, itemAvailable, recipeIndex)
                            && AltarBindingRegistry.hasBindingForRecipe(player, e.recipe())) {
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

            // Arcane Iterator per-level chain: rewrite the target step's center
            // input (index 0, the plain book prepended by filterWRCrystal) to the
            // level-(N-1) book, and snapshot the side materials so the synthesized
            // lower-level steps below reuse the identical side set.
            List<ItemStack> iteratorSideDisplays = null;
            if (levelBooks != null && !targetInputs.isEmpty()) {
                iteratorSideDisplays = new ArrayList<>();
                for (int i = 1; i < targetInputs.size(); i++) {
                    iteratorSideDisplays.add(targetInputs.get(i).copy());
                }
                targetInputs.set(0, levelBooks[levelBooks.length - 2].copy());
            }

            steps.add(new PlanStep(recipeId, targetOutput, repeatCount, targetInputs,
                    targetAlts, recipeModType, targetDepth, !targetAlts.isEmpty(),
                    targetW, targetH, targetAltModTypes, Collections.emptyList()));

            // Emit only the still-required levels. If a matching level-S book is
            // already available, it is a leaf input and the chain begins at S+1;
            // otherwise S=0 and level I starts from a plain book.
            if (levelBooks != null && iteratorSideDisplays != null) {
                for (int k = levelBooks.length - 1; k > iteratorStartLevel; k--) {
                    List<ItemStack> lvlInputs = new ArrayList<>();
                    lvlInputs.add(k >= 2 ? levelBooks[k - 2].copy() : new ItemStack(Items.BOOK));
                    for (ItemStack side : iteratorSideDisplays) lvlInputs.add(side.copy());
                    steps.add(new PlanStep(recipeId, levelBooks[k - 1].copy(), repeatCount,
                            lvlInputs, Collections.emptyList(), recipeModType,
                            targetDepth + (levelBooks.length - k), false,
                            0, 0, Collections.emptyList(), Collections.emptyList()));
                }
            }

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
            if (stepRecipe instanceof CraftingRecipe scr) {
                // Vanilla crafting: ingredient-per-slot preserves grid layout
                for (Ingredient ing : scr.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    ItemStack matched = matchAndConsume(ing, matAvailable);
                    if (matched != null) {
                        neededCounts.merge(matched.getItem(), step.batches(), Integer::sum);
                        itemSource.putIfAbsent(matched.getItem(), ing);
                    }
                }
            } else {
                // Mod recipes: extractIngredientSpecs preserves per-ingredient
                // counts via registered ModRecipeHandler (extractIngredients
                // returns a flat list that drops per-craft quantities, causing
                // the material strip to under-report vs. the tree display).
                List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(stepRecipe);
                if (specs != null) {
                    for (IngredientSpec spec : specs) {
                        if (spec.isEmpty()) continue;
                        ItemStack matched = matchAndConsume(spec.ingredient(), matAvailable);
                        if (matched != null) {
                            neededCounts.merge(matched.getItem(),
                                    CraftPacketUtils.mulCount(spec.count(), step.batches()), Integer::sum);
                            itemSource.putIfAbsent(matched.getItem(), spec.ingredient());
                        }
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

        Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
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
                materials.put(IngredientKey.of(new ItemStack(displayItem)), new PlanResponse.Availability(totalNeeded, totalHave));
            } else {
                // NBT-strict ingredients (e.g. a charged Totem of Souls declared
                // as .withTag({Souls:80000})) must count only stored items that
                // actually carry the required NBT — the Item-keyed itemAvailable map
                // drops NBT and would lump a bare, empty totem in as "available",
                // falsely turning the plan green even though extraction can't use it.
                int have = (source != null && isNbtStrict(source))
                        ? countNbtMatching(source, available)
                        : itemAvailable.getOrDefault(displayItem, 0);
                ItemStack materialDisplay = new ItemStack(displayItem);
                if (source != null && isNbtStrict(source) && source.getItems().length > 0) {
                    materialDisplay = source.getItems()[0].copyWithCount(1);
                }
                materials.put(IngredientKey.of(materialDisplay), new PlanResponse.Availability(needed, have));
            }
        }

        boolean feasible = missing.isEmpty() && materials.values().stream().allMatch(PlanResponse.Availability::isEnough);

        // Overproduction from integer batch rounding — items produced beyond what
        // the plan consumes surface as negative neededCounts. The target output is
        // intentionally negative (it's the goal, not surplus) so it's excluded.
        Map<IngredientKey, Integer> leftovers = new LinkedHashMap<>();
        for (var entry : neededCounts.entrySet()) {
            if (entry.getValue() >= 0) continue;
            if (entry.getKey() == targetOutput.getItem()) continue;
            leftovers.put(IngredientKey.of(new ItemStack(entry.getKey())), -entry.getValue());
        }

        // 总需求条 = 树显示的毛需求（从零开始，忽略库存与 resolver 批次封顶）。上面的 neededCounts
        // 用的是 step.batches()（净/封顶批次），逐支路会偏小、且和库存交互后偶尔偏大；这里用客户端
        // 同一套 PlanTreeModel 重算毛需求并覆盖 materials 的 needed，保证「总需求条 == 树」永不漂移。
        // available 仍是 RS 网络实际库存；feasible（上面按净需求判定）不动，不影响启动门控。
        PlanResponse treeSource = new PlanResponse(true, "", targetOutput, steps,
                Collections.emptyMap(), Collections.emptyList(), "",
                null, null, 0, 0, 0, Collections.emptyList(), repeatCount);
        Map<IngredientKey, Integer> grossDemand = PlanTreeModel.grossDemandByKey(PlanTreeModel.from(treeSource));
        // Re-key the UI material bill from the NBT-aware tree, not merely replace
        // counts on the old Item-keyed entries. This is what preserves an existing
        // level-IV enchanted book as the leaf material for a level-V target.
        Map<IngredientKey, PlanResponse.Availability> displayMaterials = new LinkedHashMap<>();
        for (Map.Entry<IngredientKey, Integer> entry : grossDemand.entrySet()) {
            IngredientKey key = entry.getKey();
            ItemStack display = key.stack(1);
            int have;
            if (display.hasTag()) {
                have = available.entrySet().stream()
                        .filter(e -> ItemStack.isSameItemSameTags(e.getKey().toStack(), display))
                        .mapToInt(Map.Entry::getValue).sum();
            } else {
                have = itemAvailable.getOrDefault(key.item(), 0);
            }
            displayMaterials.put(key, new PlanResponse.Availability(entry.getValue(), have));
        }
        // Keep non-tree requirements injected by machine integrations (fuel,
        // catalysts, etc.); tree identities take precedence for recipe inputs.
        for (Map.Entry<IngredientKey, PlanResponse.Availability> entry : materials.entrySet()) {
            displayMaterials.putIfAbsent(entry.getKey(), entry.getValue());
        }
        materials = displayMaterials;

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

        // Inject aspectus catalysts into the material map at count 1 (not
        // multiplied by repeatCount). Catalysts are placed once per craft and
        // recycled; they are not consumed. Without this, the plan would show
        // zero aspectus requirement, making feasibility checks inaccurate.
        if (embersInfo.code() != null
                && ModIds.ID_EMBERS_ALCHEMY.equals(recipeModType != null ? recipeModType.id() : null)) {
            try {
                var af = Reflect.findField(recipe.getClass(), "aspects");
                if (af.isPresent()) {
                    af.get().setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var aspects = (List<Ingredient>) af.get().get(recipe);
                    if (aspects != null) {
                        for (int ci : embersInfo.code()) {
                            if (ci < 0 || ci >= aspects.size()) continue;
                            Ingredient aspectIng = aspects.get(ci);
                            for (ItemStack opt : aspectIng.getItems()) {
                                if (opt.isEmpty()) continue;
                                IngredientKey key = IngredientKey.of(opt);
                                materials.merge(key,
                                        new PlanResponse.Availability(1, itemAvailable.getOrDefault(key.item(), 0)),
                                        (old, neu) -> new PlanResponse.Availability(
                                                old.needed(), Math.max(old.available(), neu.available())));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Embers] Catalyst injection into plan materials failed", e);
            }
            // Reassess feasibility after injecting catalyst items — the player
            // may be missing aspectus items that are required for execution.
            feasible = feasible && materials.values().stream().allMatch(PlanResponse.Availability::isEnough);
        }

        boolean executionMachineSupportsGui = false;
        if (dim != null && pos != null) {
            ServerLevel execLevel = player.getServer().getLevel(
                    ResourceKey.create(Registries.DIMENSION, dim));
            if (execLevel != null) {
                executionMachineSupportsGui = BindingEventHandler
                        .supportsGuiAt(execLevel, pos);
            }
        }

        // v3.4 availability passport: collect modTypes the player has bound machines for.
        Set<String> boundMachineTypes = new java.util.LinkedHashSet<>();
        // Vanilla furnace/blast/smoker/stonecutter recipes (all classified as
        // vanilla_furnace, classifyRecipe → null) need no bound machine — they're
        // always usable. Seed the passport so their alternatives render as normal
        // selectable badges instead of grayed/locked in the tree dropdown.
        boundMachineTypes.add("vanilla_furnace");
        for (ModType mt : ModType.values()) {
            if (AltarBindingRegistry.hasAnyBindingForType(player, mt)) {
                boundMachineTypes.add(mt.id());
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
                embersInfo.code() != null ? 0 : embersInfo.seed(),
                embersInfo.canInfer(),
                embersInfo.codeFromCache(),
                executionMachineSupportsGui,
                baseItem,
                boundMachineTypes,
                leftovers,
                clickedOutput
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
     * True when an ingredient matches by strict NBT (Forge {@link StrictNBTIngredient}
     * or every candidate carries NBT). Such ingredients — e.g. a charged Totem of Souls
     * declared as {@code .withTag({Souls:80000})} — must not count a bare, NBT-less item
     * as "available", or the plan falsely turns green.
     */
    private static boolean isNbtStrict(Ingredient ingredient) {
        if (ingredient instanceof StrictNBTIngredient) return true;
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0) return false;
        for (ItemStack s : items) {
            if (s.isEmpty() || !s.hasTag()) return false;
        }
        return true;
    }

    /**
     * Count how many stored items actually satisfy an NBT-strict ingredient, using the
     * NBT-carrying {@link StackKey} availability map instead of the Item-keyed one (which
     * drops NBT and would lump charged + empty totems together).
     */
    private static int countNbtMatching(Ingredient ingredient, Map<StackKey, Integer> available) {
        int total = 0;
        for (var e : available.entrySet()) {
            if (com.huanghuang.rsintegration.crafting.IngredientMatcher.test(ingredient, e.getKey().toStack())) total += e.getValue();
        }
        return total;
    }

    /**
     * Returns the best ItemStack from {@code ingredient.getItems()} — the one with
     * the highest available count in player inventory + RS network.
     * Preserves NBT so TACZ/Applied Armorer items display with correct attachments.
     * Strips BlockEntityTag/BlockId to avoid purple-black block entity rendering.
     */
    private static ItemStack matchBestAvailable(Ingredient ingredient, Map<Item, Integer> itemAvailable) {        ItemStack best = null;
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
