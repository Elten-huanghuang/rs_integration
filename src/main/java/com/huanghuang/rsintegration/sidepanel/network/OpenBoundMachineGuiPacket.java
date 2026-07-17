package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.api.ISmithingRecipeAccessor;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.gui.BlockGuiRegistry;
import com.huanghuang.rsintegration.network.gui.GuiOpenRateLimiter;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisLibraryBinding;
import com.huanghuang.rsintegration.mods.vanilla.VanillaFurnaceFuelPolicy;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Client → Server: request to open a bound machine's GUI, optionally pre-filling
 * the machine's input slot(s) with recipe ingredients from the RS network.
 */
public final class OpenBoundMachineGuiPacket {

    // Cached litTime field with SRG fallback — vanilla fields are remapped at runtime
    private static final java.lang.reflect.Field LIT_TIME = resolveLitTimeField();

    private static java.lang.reflect.Field resolveLitTimeField() {
        for (String name : new String[]{"litTime", "f_58315_"}) {
            try {
                java.lang.reflect.Field f = AbstractFurnaceBlockEntity.class.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        RSIntegrationMod.LOGGER.warn("[RSI-Prefill] litTime field not found — fuel prefill may over-supply");
        return null;
    }

    private final ResourceLocation dim;
    private final BlockPos pos;
    private final String itemKey;
    @Nullable
    private final ResourceLocation recipeId;
    @Nullable
    private final ItemStack baseItem;

    public OpenBoundMachineGuiPacket(ResourceLocation dim, BlockPos pos, String itemKey,
                                     @Nullable ResourceLocation recipeId) {
        this(dim, pos, itemKey, recipeId, null);
    }

    public OpenBoundMachineGuiPacket(ResourceLocation dim, BlockPos pos, String itemKey,
                                     @Nullable ResourceLocation recipeId,
                                     @Nullable ItemStack baseItem) {
        this.dim = dim;
        this.pos = pos;
        this.itemKey = itemKey;
        this.recipeId = recipeId;
        this.baseItem = baseItem != null ? baseItem.copy() : null;
    }

    /** Backward-compat: no recipe pre-fill. */
    public OpenBoundMachineGuiPacket(ResourceLocation dim, BlockPos pos, String itemKey) {
        this(dim, pos, itemKey, null, null);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
        buf.writeUtf(itemKey, 256);
        buf.writeBoolean(recipeId != null);
        if (recipeId != null) buf.writeResourceLocation(recipeId);
        buf.writeBoolean(baseItem != null);
        if (baseItem != null) buf.writeItem(baseItem);
    }

    public static OpenBoundMachineGuiPacket decode(FriendlyByteBuf buf) {
        ResourceLocation dim = buf.readResourceLocation();
        BlockPos pos = buf.readBlockPos();
        String key = buf.readUtf(256);
        ResourceLocation rid = buf.readBoolean() ? buf.readResourceLocation() : null;
        ItemStack base = buf.readBoolean() ? buf.readItem() : null;
        return new OpenBoundMachineGuiPacket(dim, pos, key, rid, base);
    }

    public static void handle(OpenBoundMachineGuiPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || player instanceof net.minecraftforge.common.util.FakePlayer) return;

            if (GuiOpenRateLimiter.isRateLimited(player.getUUID())) return;

            ResourceKey<Level> dimKey = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, packet.dim);

            if (!AltarBindingRegistry.isBound(dimKey, packet.pos, player)) {
                player.sendSystemMessage(
                    Component.translatable("rsi.error.not_bound"));
                return;
            }

            var bindingEntry = AltarBindingRegistry.findBindingEntry(player, packet.dim, packet.pos);
            if (bindingEntry == null) {
                player.sendSystemMessage(Component.translatable("rsi.error.not_bound"));
                return;
            }

            ResourceLocation requestedBlock = bindingEntry.blockRegKey() != null
                    ? ResourceLocation.tryParse(bindingEntry.blockRegKey())
                    : blockIdFromBindingKey(bindingEntry.blockKey());
            if (requestedBlock != null
                    && "apotheosis".equals(requestedBlock.getNamespace())
                    && !ModList.get().isLoaded("apotheosis")) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.machine.error.required_mod_missing", "Apotheosis"));
                return;
            }

            // Protection check
            PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                    player, InteractionHand.MAIN_HAND, packet.pos,
                    new BlockHitResult(new Vec3(packet.pos.getX() + 0.5,
                            packet.pos.getY() + 1.0, packet.pos.getZ() + 0.5),
                            Direction.UP, packet.pos, false));
            MinecraftForge.EVENT_BUS.post(event);
            if (event.isCanceled()) {
                player.sendSystemMessage(
                    Component.translatable("rsi.error.protected_block"));
                return;
            }

            // Determine machine type for pre-fill strategy
            net.minecraft.server.level.ServerLevel level = player.server.getLevel(dimKey);
            boolean isFurnace = false;
            boolean isStonecutter = false;
            boolean isSmithing = false;
            if (level != null) {
                ChunkUtils.loadChunk(level, packet.pos);
                ResourceLocation currentBlock = ForgeRegistries.BLOCKS.getKey(
                        level.getBlockState(packet.pos).getBlock());
                if (ApotheosisLibraryBinding.isLibrary(bindingEntry.blockRegKey())
                        && !ApotheosisLibraryBinding.matchesSavedBlock(
                                bindingEntry.blockRegKey(), currentBlock)) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.machine.error.binding_block_changed"));
                    return;
                }
                BlockEntity be = level.getBlockEntity(packet.pos);
                if (be instanceof AbstractFurnaceBlockEntity) {
                    isFurnace = true;
                } else if (be == null) {
                    String blockId = ForgeRegistries.BLOCKS
                            .getKey(level.getBlockState(packet.pos).getBlock()).toString();
                    isStonecutter = blockId.contains("stonecutter");
                    isSmithing = blockId.contains("smithing_table");
                    RSIntegrationMod.LOGGER.debug("[RSI-Prefill] Block={} stonecutter={} smithing={}",
                            blockId, isStonecutter, isSmithing);
                } else {
                    RSIntegrationMod.LOGGER.debug("[RSI-Prefill] BE present but not furnace: {}",
                            be != null ? be.getClass().getSimpleName() : "null");
                }
            }

            // Furnace: pre-fill BE slots before opening GUI (persistent storage)
            if (packet.recipeId != null && isFurnace) {
                prefillMachine(player, dimKey, packet.pos, packet.recipeId);
            }

            boolean success = BlockGuiRegistry.openGui(player, dimKey, packet.pos);

            // Stonecutter / Smithing: fill menu slots after GUI opens (menu-based, no BE)
            if (success && packet.recipeId != null && level != null) {
                if (isStonecutter) {
                    prefillStonecutterMenu(player, level, packet.recipeId);
                } else if (isSmithing) {
                    prefillSmithingTable(player, level, packet.recipeId, packet.baseItem);
                }
            }

            if (success) {
                player.sendSystemMessage(
                    Component.translatable("rsi.generic.machine_gui_opened"));
            } else {
                player.sendSystemMessage(
                    Component.translatable("rsi.generic.machine_gui_failed"));
            }
        });
        ctx.get().setPacketHandled(true);
    }

    static ResourceLocation blockIdFromBindingKey(@Nullable String blockKey) {
        if (blockKey == null || blockKey.isBlank()) return null;
        int separator = blockKey.indexOf("||");
        if (separator >= 0 && separator + 2 < blockKey.length()) {
            String descriptionId = blockKey.substring(separator + 2);
            if (descriptionId.startsWith("block.")) {
                String body = descriptionId.substring("block.".length());
                int namespaceEnd = body.indexOf('.');
                if (namespaceEnd > 0 && namespaceEnd < body.length() - 1) {
                    return ResourceLocation.tryParse(body.substring(0, namespaceEnd)
                            + ":" + body.substring(namespaceEnd + 1));
                }
            }
        }
        return ResourceLocation.tryParse(blockKey);
    }

    // ── material pre-fill ──────────────────────────────────────────

    private static void prefillMachine(ServerPlayer player, ResourceKey<Level> dimKey,
                                        BlockPos pos, ResourceLocation recipeId) {
        var levelOpt = player.server.getLevel(dimKey);
        if (levelOpt == null) return;
        ChunkUtils.loadChunk(levelOpt, pos);

        Recipe<?> recipe = levelOpt.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) {
            RSIntegrationMod.LOGGER.debug("[RSI-OpenGui] Recipe not found: {}", recipeId);
            return;
        }

        BlockEntity be = levelOpt.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) return;

        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) return;

        var ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return;

        // ── place input ingredient into slot 0 ──
        Ingredient input = ingredients.get(0);
        if (!input.isEmpty()) {
            // Check if slot 0 already has a valid item
            ItemStack existing = furnace.getItem(0);
            if (!existing.isEmpty()) {
                boolean matches = false;
                for (ItemStack match : input.getItems()) {
                    if (ItemStack.isSameItemSameTags(existing, match)) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) {
                    player.sendSystemMessage(Component.translatable("rsi.vanilla.error.furnace_occupied"));
                    return;
                }
                // Already has the right item — skip extraction
            } else {
                // Extract one unit of the input from RS
                ItemStack extracted = extractIngredientFromRS(network, input);
                if (extracted.isEmpty()) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.generic.error.missing_materials",
                            input.getItems().length > 0
                                    ? input.getItems()[0].getHoverName().getString()
                                    : "?"));
                    return;
                }
                furnace.setItem(0, extracted.copy());
            }
        }

        // ── auto-supply fuel into slot 1 ──
        int cookingTime = recipe instanceof AbstractCookingRecipe acr
                ? com.huanghuang.rsintegration.mods.vanilla.BrickFurnaceCompat.effectiveCookTicks(furnace, acr)
                : 200;
        int litTime = 0;
        if (LIT_TIME != null) {
            try {
                litTime = LIT_TIME.getInt(furnace);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-SidePanel] litTime probe failed", e);
            }
        }
        supplyFuelFromRS(network, furnace, recipe, Math.max(0, cookingTime - litTime), player);

        furnace.setChanged();
        levelOpt.sendBlockUpdated(pos,
                levelOpt.getBlockState(pos), levelOpt.getBlockState(pos), 3);
    }

    private static ItemStack extractIngredientFromRS(INetwork network, Ingredient ingredient) {
        var stacks = new ArrayList<>(network.getItemStorageCache().getList().getStacks());
        for (var entry : stacks) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty()) continue;
            if (ingredient.test(stack)) {
                ItemStack extracted = network.extractItem(
                        stack.copyWithCount(1), 1, Action.PERFORM);
                if (!extracted.isEmpty()) return extracted;
            }
        }
        return ItemStack.EMPTY;
    }

    private static void supplyFuelFromRS(INetwork network, AbstractFurnaceBlockEntity furnace,
                                          Recipe<?> recipe, int remainingCook, ServerPlayer player) {
        if (remainingCook <= 0) return;
        ItemStack existing = furnace.getItem(1);
        if (!existing.isEmpty()) {
            int burn = com.huanghuang.rsintegration.mods.vanilla.BrickFurnaceCompat.effectiveBurnTicks(
                    furnace, existing, recipeTypeFor(furnace, recipe));
            int needed = VanillaFurnaceFuelPolicy.requiredAmount(remainingCook, burn);
            if (burn <= 0 || needed > existing.getMaxStackSize()) return;
            if (existing.getCount() >= needed) return;
            int topUp = needed - existing.getCount();
            ItemStack extra = network.extractItem(existing.copyWithCount(1), topUp, Action.PERFORM);
            if (extra.getCount() < topUp) {
                if (!extra.isEmpty()) network.insertItem(extra, extra.getCount(), Action.PERFORM);
                return;
            }
            ItemStack merged = existing.copy();
            merged.grow(extra.getCount());
            furnace.setItem(1, merged);
            return;
        }

        List<ItemStack> candidates = new ArrayList<>();
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            candidates.add(entry.getStack());
        }
        VanillaFurnaceFuelPolicy.Selection selection = VanillaFurnaceFuelPolicy.select(
                candidates, RSIntegrationConfig.VANILLA_FURNACE_FUEL_PRIORITY.get(),
                remainingCook,
                stack -> com.huanghuang.rsintegration.mods.vanilla.BrickFurnaceCompat.effectiveBurnTicks(
                        furnace, stack, recipeTypeFor(furnace, recipe)));
        if (selection == null) return;
        ItemStack extracted = network.extractItem(selection.fuel().copyWithCount(1),
                selection.amount(), Action.PERFORM);
        if (extracted.getCount() < selection.amount()) {
            if (!extracted.isEmpty()) network.insertItem(extracted, extracted.getCount(), Action.PERFORM);
            return;
        }
        if (!extracted.isEmpty()) {
            furnace.setItem(1, extracted.copy());
            player.displayClientMessage(
                    Component.translatable("rsi.vanilla.info.fuel_supplied", extracted.getCount()), true);
        }
    }

    private static net.minecraft.world.item.crafting.RecipeType<?> recipeTypeFor(
            AbstractFurnaceBlockEntity furnace, Recipe<?> recipe) {
        return switch (com.huanghuang.rsintegration.mods.vanilla.CookingMachineFamily.fromBlock(
                furnace.getBlockState().getBlock())) {
            case BLAST_FURNACE -> net.minecraft.world.item.crafting.RecipeType.BLASTING;
            case SMOKER -> net.minecraft.world.item.crafting.RecipeType.SMOKING;
            default -> net.minecraft.world.item.crafting.RecipeType.SMELTING;
        };
    }

    // ── Stonecutter / Smithing menu pre-fill ──────────────────────────

    /** Fill stonecutter input slot with max quantity of the recipe's ingredient from RS. */
    private static void prefillStonecutterMenu(ServerPlayer player, ServerLevel level,
                                                ResourceLocation recipeId) {
        if (!(player.containerMenu instanceof StonecutterMenu menu)) {
            RSIntegrationMod.LOGGER.warn("[RSI-OpenGui] Expected StonecutterMenu but got {}",
                    player.containerMenu != null ? player.containerMenu.getClass().getName() : "null");
            return;
        }

        Recipe<?> recipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) return;

        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) return;

        var ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return;

        Ingredient input = ingredients.get(0);
        if (input.isEmpty()) return;

        // Determine max quantity to fill (up to item's max stack size)
        int maxStack = 64;
        ItemStack[] matching = input.getItems();
        if (matching.length > 0) {
            maxStack = matching[0].getMaxStackSize();
        }

        ItemStack existing = menu.getSlot(0).getItem();
        int existingCount = existing.isEmpty() ? 0 : existing.getCount();

        // If slot already has matching item, top up; otherwise replace
        if (!existing.isEmpty() && !input.test(existing)) {
            // Non-matching item in slot — skip pre-fill
            return;
        }

        int needed = maxStack - existingCount;
        if (needed <= 0) return;

        ItemStack extracted = extractIngredientFromRS(network, input, needed);
        if (!extracted.isEmpty()) {
            if (existing.isEmpty()) {
                menu.getSlot(0).set(extracted.copy());
            } else {
                ItemStack combined = existing.copy();
                combined.grow(extracted.getCount());
                menu.getSlot(0).set(combined);
            }
            menu.broadcastChanges();
            player.displayClientMessage(
                    Component.translatable("rsi.vanilla.info.stonecutter_filled", extracted.getCount()), true);
        }
    }

    /** Fill smithing table: template (slot 0), base (slot 1), addition (slot 2) from RS. */
    public static void prefillSmithingTable(ServerPlayer player, ServerLevel level,
                                             ResourceLocation recipeId,
                                             @Nullable ItemStack baseItem) {
        RSIntegrationMod.LOGGER.debug("[RSI-Prefill] Smithing start: recipe={} menu={}",
                recipeId, player.containerMenu != null ? player.containerMenu.getClass().getSimpleName() : "null");

        if (!(player.containerMenu instanceof SmithingMenu menu)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Prefill] Expected SmithingMenu but got {}",
                    player.containerMenu != null ? player.containerMenu.getClass().getName() : "null");
            return;
        }

        Recipe<?> recipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        // FA ApplyModifierRecipe lives under smithing/ subdirectory
        if (recipe == null && "forbidden_arcanus".equals(recipeId.getNamespace())) {
            recipe = level.getRecipeManager().byKey(
                    new ResourceLocation(recipeId.getNamespace(),
                            "smithing/" + recipeId.getPath())).orElse(null);
        }
        if (recipe == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Prefill] Recipe not found: {}", recipeId);
            return;
        }

        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network == null) return;

        // FA ApplyModifierRecipe: template (slot 0), base (slot 1 from baseItem or RS), addition (slot 2).
        if (recipe.getClass().getSimpleName().equals("ApplyModifierRecipe")) {
            prefillFaApplyModifier(player, recipe, network, menu, baseItem);
            return;
        }

        List<Ingredient> ingredients = recipe.getIngredients();
        RSIntegrationMod.LOGGER.debug("[RSI-Prefill] Smithing ingredients count={}", ingredients.size());
        // SmithingRecipe (1.20 abstract base) doesn't override getIngredients() — use reflection
        if (ingredients.isEmpty() && (recipe instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe
                || recipe instanceof net.minecraft.world.item.crafting.SmithingTrimRecipe)) {
            ingredients = extractSmithingIngredients(recipe);
            RSIntegrationMod.LOGGER.debug("[RSI-Prefill] Smithing reflection ingredients count={}", ingredients.size());
        }
        if (ingredients.size() < 3) {
            RSIntegrationMod.LOGGER.warn("[RSI-Prefill] Smithing ingredients < 3: {}", ingredients.size());
            return;
        }

        // 1.20 SmithingMenu: ingredients=[template, base, addition]
        // container slots: 0=template, 1=base, 2=addition
        int filled = 0;
        for (int slotIdx = 0; slotIdx < 3; slotIdx++) {
            Ingredient ing = ingredients.get(slotIdx);
            if (ing.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Prefill] Slot {} ingredient is empty", slotIdx);
                continue;
            }

            ItemStack existing = menu.getSlot(slotIdx).getItem();
            if (!existing.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Prefill] Container slot {} already has {} — {}", slotIdx,
                        existing.getCount(), ing.test(existing) ? "matches, skip" : "non-match, skip");
                continue;
            }

            ItemStack extracted = extractIngredientFromRS(network, ing, 1);
            if (!extracted.isEmpty()) {
                menu.getSlot(slotIdx).set(extracted.copy());
                filled++;
                RSIntegrationMod.LOGGER.debug("[RSI-Prefill] Container slot {} filled with {}", slotIdx, extracted);
            } else {
                RSIntegrationMod.LOGGER.warn("[RSI-Prefill] Container slot {} extraction failed for ingredient", slotIdx);
            }
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Prefill] Smithing done: filled={}/3", filled);
        if (filled > 0) {
            menu.broadcastChanges();
            player.displayClientMessage(
                    Component.translatable("rsi.vanilla.info.smithing_filled", filled), true);
        }
    }

    /** Fill smithing table for FA ApplyModifierRecipe.
     *  1.20 SmithingMenu: 0=template, 1=base, 2=addition. */
    private static void prefillFaApplyModifier(ServerPlayer player, Recipe<?> recipe,
                                                INetwork network, SmithingMenu menu,
                                                @Nullable ItemStack baseItem) {
        try {
            java.lang.reflect.Method getTemplate = recipe.getClass().getMethod("getTemplate");
            java.lang.reflect.Method getAddition = recipe.getClass().getMethod("getAddition");
            java.lang.reflect.Method getModifier = recipe.getClass().getMethod("getModifier");
            Ingredient template = (Ingredient) getTemplate.invoke(recipe);
            Ingredient addition = (Ingredient) getAddition.invoke(recipe);
            Object modifier = getModifier.invoke(recipe);

            int filled = 0;
            // container slot 0 = template
            if (!template.isEmpty()) {
                ItemStack existing = menu.getSlot(0).getItem();
                if (existing.isEmpty()) {
                    ItemStack extracted = extractIngredientFromRS(network, template, 1);
                    if (!extracted.isEmpty()) {
                        menu.getSlot(0).set(extracted.copy());
                        filled++;
                    }
                }
            }
            // container slot 1 = base
            if (menu.getSlot(1).getItem().isEmpty()) {
                if (baseItem != null && !baseItem.isEmpty()) {
                    ItemStack extracted = network.extractItem(baseItem.copyWithCount(1), 1,
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    if (!extracted.isEmpty()) {
                        menu.getSlot(1).set(extracted.copy());
                        filled++;
                    }
                }
            }
            // container slot 2 = addition
            if (!addition.isEmpty()) {
                ItemStack existing = menu.getSlot(2).getItem();
                if (existing.isEmpty()) {
                    ItemStack extracted = extractIngredientFromRS(network, addition, 1);
                    if (!extracted.isEmpty()) {
                        menu.getSlot(2).set(extracted.copy());
                        filled++;
                    }
                }
            }

            if (filled > 0) {
                menu.broadcastChanges();
                player.displayClientMessage(
                        Component.translatable("rsi.vanilla.info.smithing_filled", filled), true);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Prefill] FA smithing prefill failed", e);
        }
    }

    /** Extract up to {@code count} of an ingredient from RS. */
    private static ItemStack extractIngredientFromRS(INetwork network, Ingredient ingredient, int count) {
        var stacks = new ArrayList<>(network.getItemStorageCache().getList().getStacks());
        ItemStack result = ItemStack.EMPTY;
        int remaining = count;
        for (var entry : stacks) {
            if (remaining <= 0) break;
            ItemStack stack = entry.getStack();
            if (stack.isEmpty()) continue;
            if (ingredient.test(stack)) {
                int toExtract = Math.min(remaining, stack.getCount());
                ItemStack extracted = network.extractItem(
                        stack.copyWithCount(1), toExtract, Action.PERFORM);
                if (!extracted.isEmpty()) {
                    if (result.isEmpty()) {
                        result = extracted;
                    } else {
                        result.grow(extracted.getCount());
                    }
                    remaining -= extracted.getCount();
                }
            }
        }
        return result;
    }

    /** Extract template/base/addition from SmithingRecipe subclasses via accessors.
     *  Slot order is guaranteed: [0]=template, [1]=base, [2]=addition. */
    private static List<Ingredient> extractSmithingIngredients(Recipe<?> recipe) {
        List<Ingredient> result = new ArrayList<>(3);
        if (recipe instanceof ISmithingRecipeAccessor acc) {
            result.add(acc.rsi$getTemplate());
            result.add(acc.rsi$getBase());
            result.add(acc.rsi$getAddition());
        }
        return result;
    }

    // ── FA ApplyModifierRecipe: dispatch ──────────────────────────

    /** True if {@code recipe} is an FA {@code ApplyModifierRecipe}
     *  (no fixed base item — base is determined at runtime). */
    public static boolean isFaApplyModifier(Recipe<?> recipe) {
        return recipe.getClass().getSimpleName().equals("ApplyModifierRecipe");
    }

    /** Open the bound smithing table GUI and pre-fill template + addition.
     *  If baseItem is provided (from JEI), it is used for slot 1. */
    public static void openSmithingForFaModifier(ServerPlayer player, ResourceLocation recipeId,
                                                  @Nullable ItemStack baseItem) {
        ModType smithing = ModType.byId("smithing");

        List<AltarBindingRegistry.BoundMachine> machines =
                AltarBindingRegistry.getBoundMachinesForType(player, smithing);
        if (machines.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.no_bound_machine", "smithing"));
            return;
        }

        var m = machines.get(0);
        ResourceKey<Level> dimKey = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, m.dim());
        ServerLevel level = player.getServer().getLevel(dimKey);
        if (level == null) return;

        boolean success = BlockGuiRegistry.openGui(player, dimKey, m.pos());
        if (!success) {
            player.sendSystemMessage(Component.translatable("rsi.generic.machine_gui_failed"));
            return;
        }

        prefillSmithingTable(player, level, recipeId, baseItem);
    }
}
