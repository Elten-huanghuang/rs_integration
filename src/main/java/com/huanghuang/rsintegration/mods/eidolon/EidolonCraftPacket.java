package com.huanghuang.rsintegration.mods.eidolon;

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
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.huanghuang.rsintegration.util.Reflect;
import com.huanghuang.rsintegration.reflection.probes.EidolonReflection;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.Supplier;

public final class EidolonCraftPacket {

    private final ResourceLocation recipeId;
    private final ResourceLocation dim;
    private final BlockPos pos;

    // ── Shared class refs (resolved from probe) ─────────────────
    private static volatile java.lang.reflect.Field boilingField;
    private static volatile java.lang.reflect.Field stepsField;

    static {
        if (EidolonReflection.crucibleTileEntityClass != null) {
            try {
                boilingField = EidolonReflection.crucibleTileEntityClass.getDeclaredField("boiling");
                boilingField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Eidolon] boiling field not found");
            }
            try {
                stepsField = EidolonReflection.crucibleTileEntityClass.getDeclaredField("steps");
                stepsField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Eidolon] steps field not found");
            }
        }
    }

    // ── packet ─────────────────────────────────────────────────

    public EidolonCraftPacket(ResourceLocation recipeId, @Nullable ResourceLocation dim, BlockPos pos) {
        this.recipeId = recipeId;
        this.dim = dim;
        this.pos = pos;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(recipeId);
        buf.writeBoolean(dim != null);
        if (dim != null) buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
    }

    public static EidolonCraftPacket decode(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        ResourceLocation d = buf.readBoolean() ? buf.readResourceLocation() : null;
        return new EidolonCraftPacket(id, d, buf.readBlockPos());
    }

    public static void handle(EidolonCraftPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null || player instanceof net.minecraftforge.common.util.FakePlayer) {
            context.setPacketHandled(true);
            return;
        }        context.enqueueWork(() -> {
            try {
                tryCraft(player, packet.recipeId, packet.dim, packet.pos);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Crucible craft failed for {}:", packet.recipeId, e);
                player.sendSystemMessage(Component.translatable("rsi.eidolon.error.craft_failed"));
            }
        });
        context.setPacketHandled(true);
    }

    // ── main logic ─────────────────────────────────────────────

    private static void tryCraft(ServerPlayer player, ResourceLocation recipeId,
                                  ResourceLocation dim, BlockPos pos) {
        ServerLevel level = resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return;
        }

        Recipe<?> recipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return;
        }
        if (!EidolonReflection.crucibleRecipeClass.isInstance(recipe)) {
            player.sendSystemMessage(Component.literal("§c" + Component.translatable("rsi.generic.error.wrong_recipe_type").getString()
                    + " [" + recipeId + " expected=CrucibleRecipe got=" + recipe.getClass().getSimpleName() + "]"));
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
        if (be == null || !EidolonReflection.crucibleTileEntityClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.error.crucible_not_found"));
            return;
        }

        ResourceKey<Level> altarDim = level.dimension();

        // Validate crucible state
        boolean hasWater;
        try { java.lang.reflect.Field hwField = be.getClass().getDeclaredField("hasWater"); hwField.setAccessible(true); hasWater = hwField.getBoolean(be); } catch (Exception e) { hasWater = false; }

        boolean boiling = false;
        try {
            if (boilingField != null) boiling = boilingField.getBoolean(be);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        boolean stepsEmpty = true;
        try {
            if (stepsField != null) {
                List<?> steps = (List<?>) stepsField.get(be);
                stepsEmpty = steps == null || steps.isEmpty();
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        if (!hasWater) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.warn.needs_water"));
            // Try auto-fill
            try {
                be.getClass().getMethod("fill").invoke(be);
                hasWater = true;
                player.sendSystemMessage(Component.translatable("rsi.eidolon.info.auto_filled"));
            } catch (Exception ex) {
                player.sendSystemMessage(Component.translatable("rsi.eidolon.error.fill_failed"));
                return;
            }
        }

        if (!boiling) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.warn.needs_heat"));
            return;
        }

        if (!stepsEmpty) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.warn.steps_not_empty"));
            return;
        }

        // Collect needed items from recipe
        List<CrucibleStepInput> stepInputs = collectSteps(recipe);
        if (stepInputs.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.eidolon.error.no_steps"));
            return;
        }

        List<Ingredient> allIngredients = new ArrayList<>();
        for (CrucibleStepInput si : stepInputs) {
            allIngredients.addAll(si.ingredients);
        }

        // Count available
        INetwork network = CraftPacketUtils.resolveNetworkForCraft(player, altarDim, pos);
        Map<StackKey, Integer> available = MaterialSources.listAllAvailable(player, network);

        // Auto-craft missing intermediates
        if (RSIntegrationConfig.ENABLE_AUTO_CRAFTING.get()) {
            List<String> missing = new ArrayList<>();
            List<ResourceLocation> autoSteps = CraftingResolver.resolveStepsForIngredients(
                    allIngredients,
                    available.entrySet().stream().map(e -> {
                        ItemStack s = new ItemStack(e.getKey().item(), e.getValue());
                        if (e.getKey().tag() != null) {
                            try { s.setTag(net.minecraft.nbt.TagParser.parseTag(e.getKey().tag())); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] NBT parse failed for key {}", e.getKey(), ex); }
                        }
                        return s;
                    }).toList(),
                    level,
                    missing);

            if (!missing.isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials", CraftPacketUtils.formatMissingSummary(missing)));
                return;
            }

            if (!autoSteps.isEmpty() && network != null) {
                player.sendSystemMessage(Component.translatable("rsi.generic.info.auto_crafting", autoSteps.size()));
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

        // Phase 1: reserve all ingredients via ledger (no physical extraction yet)
        // Preserve the binding-resolved network from earlier; only fall back
        // to player-inventory scan if no binding was found.
        if (network == null) {
            network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        }
        List<Object> crucibleSteps = new ArrayList<>();
        List<ItemStack> allExtracted = new ArrayList<>(); // actual items extracted, for refund

        try (ExtractionLedger ledger = new ExtractionLedger()) {
            for (CrucibleStepInput si : stepInputs) {
                List<ItemStack> stepItems = new ArrayList<>();
                for (Ingredient ing : si.ingredients) {
                    if (ing.isEmpty()) continue;
                    ItemStack stack = ensureMaterialAvailable(player, altarDim, pos, ing, 1, ledger);
                    if (stack.isEmpty()) {
                        player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials", CraftPacketUtils.describeIngredient(ing)));
                        return;
                    }
                    stepItems.add(stack);
                    allExtracted.add(stack.copy());
                }

                Constructor<?> ctor = EidolonReflection.crucibleStepInnerClass.getConstructor(int.class, List.class);
                Object step = ctor.newInstance(si.stirs, stepItems);
                crucibleSteps.add(step);
            }

            java.lang.reflect.Method matchesMethod = Reflect.findMethod(
                    recipe.getClass(), "matches", new Class<?>[]{List.class});
            if (matchesMethod == null) throw new NoSuchMethodException(recipe.getClass().getName() + ".matches");
            boolean matches = (boolean) matchesMethod.invoke(recipe, crucibleSteps);
            if (!matches) {
                player.sendSystemMessage(Component.translatable("rsi.eidolon.error.match_failed"));
                return;
            }

            // Phase 2: commit all extractions atomically
            if (!ledger.commit(network, player)) {
                RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Ledger commit failed for recipe {}", recipeId);
                player.sendSystemMessage(Component.translatable("rsi.eidolon.error.craft_failed", "commit failed"));
                return;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Extraction/step creation failed for {}:", recipeId, e);
            player.sendSystemMessage(Component.translatable("rsi.generic.error.prepare_failed"));
            return;
        }

        // Get result first — validate before consuming resources
        try {
            java.lang.reflect.Method getResultMethod = Reflect.findMethod(
                    recipe.getClass(), "getResult", new Class<?>[0]);
            if (getResultMethod == null) throw new NoSuchMethodException(recipe.getClass().getName() + ".getResult");
            ItemStack result = ((ItemStack) getResultMethod.invoke(recipe)).copy();
            double x = pos.getX() + 0.5;
            double y = pos.getY() + 0.75;
            double z = pos.getZ() + 0.5;
            ItemEntity itemEntity = new ItemEntity(level, x, y, z, result);
            itemEntity.setPickUpDelay(10);
            double angle = level.random.nextDouble() * Math.PI * 2;
            itemEntity.setDeltaMovement(
                    Math.sin(angle) * 0.125,
                    0.25,
                    Math.cos(angle) * 0.125);
            level.addFreshEntity(itemEntity);

            player.displayClientMessage(Component.translatable("rsi.eidolon.info.craft_complete", result.getDisplayName()), true);
            RSIntegrationMod.LOGGER.debug("[RSI-Eidolon] Player {} completed crucible recipe '{}'",
                    player.getName().getString(), recipeId);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Failed to spawn result for {}:", recipeId, e);
            player.sendSystemMessage(Component.translatable("rsi.eidolon.error.result_failed"));
            // Refund committed items — use actual extracted items, not ingredient.getItems()[0]
            for (ItemStack refundStack : allExtracted) {
                if (refundStack.isEmpty()) continue;
                if (network != null) {
                    ItemStack leftover = network.insertItem(refundStack.copy(), refundStack.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    if (!leftover.isEmpty()) {
                        ItemHandlerHelper.giveItemToPlayer(player, leftover);
                    }
                } else {
                    ItemHandlerHelper.giveItemToPlayer(player, refundStack.copy());
                }
            }
            return;
        }

        // Drain water, stop boiling, clear steps (consume resources)
        try {
            java.lang.reflect.Field tankField = be.getClass().getDeclaredField("tank");
            tankField.setAccessible(true);
            Object tank = tankField.get(be);
            tank.getClass()
                    .getMethod("drain", int.class, IFluidHandler.FluidAction.class)
                    .invoke(tank, EidolonBatchDelegate.readWaterAmountStatic(recipe), IFluidHandler.FluidAction.EXECUTE);
            java.lang.reflect.Field hwField2 = be.getClass().getDeclaredField("hasWater");
            hwField2.setAccessible(true);
            hwField2.set(be, false);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        try {
            if (stepsField != null) stepsField.set(be, new ArrayList<>());
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        be.setChanged();
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
    }

    // ── helpers ────────────────────────────────────────────────

    private static List<CrucibleStepInput> collectSteps(Recipe<?> recipe) {
        List<CrucibleStepInput> result = new ArrayList<>();
        try {
            List<?> steps = (List<?>) recipe.getClass().getMethod("getSteps").invoke(recipe);
            if (steps != null) {
                for (Object step : steps) {
                    int stirs = step.getClass().getField("stirs").getInt(step);
                    @SuppressWarnings("unchecked")
                    List<Ingredient> matches = (List<Ingredient>) step.getClass().getField("matches").get(step);
                    List<Ingredient> ingredients = new ArrayList<>(matches);
                    result.add(new CrucibleStepInput(stirs, ingredients));
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Eidolon] Failed to collect steps", e);
        }
        return result;
    }

    private static ItemStack ensureMaterialAvailable(ServerPlayer player, ResourceKey<Level> altarDim,
                                                      BlockPos altarPos, Ingredient ingredient, int count,
                                                      ExtractionLedger ledger) {
        return CraftPacketUtils.ensureMaterialAvailable(player, altarDim, altarPos, ingredient, count, ledger);
    }

    private static ServerLevel resolveLevel(MinecraftServer server, ResourceLocation dim,
                                            ServerPlayer player) {
        return CraftPacketUtils.resolveLevel(server, dim, player);
    }

    // ── inner types ────────────────────────────────────────────

    private static final class CrucibleStepInput {
        final int stirs;
        final List<Ingredient> ingredients;

        CrucibleStepInput(int stirs, List<Ingredient> ingredients) {
            this.stirs = stirs;
            this.ingredients = ingredients;
        }
    }
}
