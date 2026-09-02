package com.huanghuang.rsintegration.compat.emi;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionRequestPacket;
import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionSnapshot;
import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionTargetIds;
import com.huanghuang.rsintegration.compat.jei.JeiMachineCategoryPolicy;
import com.huanghuang.rsintegration.compat.jei.JeiRecipeIdNormalizer;
import com.huanghuang.rsintegration.compat.jei.SophisticatedStorageRecipeIdResolver;
import com.huanghuang.rsintegration.compat.jei.StandardRecipeIdResolver;
import com.huanghuang.rsintegration.compat.jei.WishingFountainRecipeIdResolver;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.machine.BeyondDimensionsOpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.mods.goety.GoetyBindingRules;
import com.huanghuang.rsintegration.mods.goety.GoetyRitualPolicy;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.client.BindingBackendResolver;
import com.huanghuang.rsintegration.sidepanel.client.GuiNavStack;
import com.huanghuang.rsintegration.sidepanel.network.OpenBoundMachineGuiPacket;
import com.huanghuang.rsintegration.util.CuriosAccess;
import com.huanghuang.rsintegration.util.ModIds;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

final class EmiCraftButtonResolver {
    private static final ResourceLocation SMELTING = id("minecraft", "smelting");
    private static final ResourceLocation BLASTING = id("minecraft", "blasting");
    private static final ResourceLocation SMOKING = id("minecraft", "smoking");
    private static final ResourceLocation CAMPFIRE = id("minecraft", "campfire");
    private static final ResourceLocation STONECUTTING = id("minecraft", "stonecutting");
    private static final ResourceLocation SMITHING = id("minecraft", "smithing");
    private static final ResourceLocation ANVIL = id("minecraft", "anvil");
    private static final ResourceLocation CRAFTING = id("minecraft", "crafting");
    private static final ResourceLocation CRAB_TRAP = id("crabbersdelight", "crab_trap_loot");

    private EmiCraftButtonResolver() {}

    static Optional<EmiCraftButtonSpec> resolve(EmiRecipe emiRecipe) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return Optional.empty();

        Object displayRecipe = jemiDisplayRecipe(emiRecipe);
        Recipe<?> backingRecipe = safeBackingRecipe(emiRecipe);
        Object sourceRecipe = displayRecipe != null ? displayRecipe : backingRecipe;
        if (sourceRecipe == null) sourceRecipe = recipeFromManager(emiRecipe.getId());

        if (sourceRecipe instanceof QuestSubmissionSnapshot quest) {
            ResourceLocation recipeId = QuestSubmissionTargetIds.of(quest.questId());
            Runnable action = () -> BatchCraftNetworkHandler.CHANNEL.sendToServer(
                    new QuestSubmissionRequestPacket(quest.questId()));
            return Optional.of(new EmiCraftButtonSpec(recipeId, null,
                    "gui.rs_integration.jei.ftb_quest_submit", action, null));
        }
        if (sourceRecipe == null) return Optional.empty();

        ResourceLocation categoryId = originalCategoryId(emiRecipe);
        ResourceLocation recipeId = resolveRecipeId(emiRecipe, sourceRecipe, backingRecipe);
        if (recipeId == null) return Optional.empty();

        String filter = resolveFilter(sourceRecipe, backingRecipe, categoryId);
        boolean generic = isCraftingRecipe(sourceRecipe, backingRecipe, categoryId);
        if (filter == null && generic) filter = "generic";
        if (filter == null) return Optional.empty();

        ModType recipeModType = resolveModType(sourceRecipe, backingRecipe);
        boolean virtual = recipeModType != null && recipeModType.isVirtual();
        if (isUnsupportedGoetyRitual(sourceRecipe)) return Optional.empty();

        ResourceLocation dimension;
        BlockPos machinePos;
        String blockKey = null;
        String blockRegistryKey = null;
        if (generic || virtual) {
            dimension = minecraft.level.dimension().location();
            machinePos = minecraft.player.blockPosition();
        } else {
            BindingStorage.BindingEntry binding = findBinding(filter, sourceRecipe);
            if (binding == null) return Optional.empty();
            dimension = binding.dim();
            machinePos = binding.pos();
            blockKey = binding.blockKey();
            blockRegistryKey = binding.blockRegKey();
        }

        ModType filteredType = ModType.findById(filter);
        ModType modType = filteredType != null ? filteredType : recipeModType;
        ItemStack baseItem = smithingBase(emiRecipe, categoryId, recipeId, filter);
        ItemStack targetOutput = dynamicTargetOutput(emiRecipe, sourceRecipe, recipeId);
        Runnable craftAction = createCraftAction(recipeId, dimension, machinePos, filter,
                baseItem, targetOutput);

        Runnable machineAction = null;
        if (!generic && !virtual && modType != null
                && (ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                    || BindingBackendResolver.isBeyondDimensionsBinding(dimension, machinePos))
                && supportsGui(blockKey, blockRegistryKey)) {
            ResourceLocation actionDimension = dimension;
            BlockPos actionPos = machinePos;
            ItemStack actionBase = baseItem.isEmpty() ? null : baseItem.copy();
            machineAction = () -> openMachine(actionDimension, actionPos, recipeId, actionBase);
        }

        return Optional.of(new EmiCraftButtonSpec(recipeId, modType,
                tooltipKey(sourceRecipe, filter, modType), craftAction, machineAction));
    }

    @Nullable
    private static Object jemiDisplayRecipe(EmiRecipe recipe) {
        if (!"dev.emi.emi.jemi.JemiRecipe".equals(recipe.getClass().getName())) return null;
        return readField(recipe, "recipe");
    }

    @Nullable
    private static Recipe<?> safeBackingRecipe(EmiRecipe recipe) {
        try {
            return recipe.getBackingRecipe();
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-EMI] Failed to read backing recipe for {}", recipe.getId(), exception);
            return null;
        }
    }

    @Nullable
    private static Recipe<?> recipeFromManager(@Nullable ResourceLocation id) {
        if (id == null || isJemiSyntheticId(id) || Minecraft.getInstance().level == null) return null;
        return Minecraft.getInstance().level.getRecipeManager().byKey(id).orElse(null);
    }

    @Nullable
    private static ResourceLocation resolveRecipeId(EmiRecipe emiRecipe, Object source,
                                                      @Nullable Recipe<?> backing) {
        ResourceLocation wishingFountain = WishingFountainRecipeIdResolver.resolve(source);
        if (wishingFountain != null) return wishingFountain;
        ResourceLocation grouped = SophisticatedStorageRecipeIdResolver.resolve(source);
        if (grouped != null) return grouped;
        if (source instanceof com.huanghuang.rsintegration.mods.pmmo.client.PmmoSalvageRecipe salvage) {
            return salvage.recipeId();
        }
        if (backing != null) return backing.getId();

        ResourceLocation standard = StandardRecipeIdResolver.resolve(source);
        if (standard != null) return JeiRecipeIdNormalizer.normalize(standard);

        Object original = readField(emiRecipe, "originalId");
        if (original instanceof ResourceLocation id) return JeiRecipeIdNormalizer.normalize(id);

        ResourceLocation emiId = emiRecipe.getId();
        return emiId == null || isJemiSyntheticId(emiId) ? null : emiId;
    }

    @Nullable
    private static ResourceLocation originalCategoryId(EmiRecipe recipe) {
        Object jeiCategory = readField(recipe, "category");
        if (jeiCategory != null) {
            try {
                Object recipeType = jeiCategory.getClass().getMethod("getRecipeType").invoke(jeiCategory);
                Object uid = recipeType.getClass().getMethod("getUid").invoke(recipeType);
                if (uid instanceof ResourceLocation id) return id;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                RSIntegrationMod.LOGGER.debug("[RSI-EMI] Failed to read JEMI category", exception);
            }
        }
        return recipe.getCategory() == null ? null : recipe.getCategory().getId();
    }

    @Nullable
    private static String resolveFilter(Object source, @Nullable Recipe<?> backing,
                                        @Nullable ResourceLocation categoryId) {
        if (source instanceof com.huanghuang.rsintegration.mods.apotheosis.ApotheosisGemCuttingRecipe) {
            return "apotheosis_gem_cutting";
        }
        if (source instanceof com.huanghuang.rsintegration.mods.pmmo.client.PmmoSalvageRecipe
                && ModList.get().isLoaded(ModIds.PMMO) && RSIntegrationConfig.ENABLE_PMMO.get()) {
            return com.huanghuang.rsintegration.mods.pmmo.PmmoRSModule.TYPE_ID;
        }

        String className = source.getClass().getName();
        if (isGoetyRitual(className)) return GoetyBindingRules.ALTAR_FILTER;
        if ("com.Polarice3.Goety.common.crafting.BrazierRecipe".equals(className)) {
            return GoetyBindingRules.BRAZIER_FILTER;
        }

        String yhk = classifyYhkCooking(source);
        if (yhk != null) return yhk;

        String categoryFilter = categoryId == null ? null : ModType.filterForJeiUid(categoryId.toString());
        if (!JeiMachineCategoryPolicy.allowClassFallback(categoryId, className, categoryFilter)) return null;
        if (categoryFilter != null) return categoryFilter;

        if (SMITHING.equals(categoryId)) {
            if (className.startsWith("com.stal111.forbidden_arcanus.")) return "hephaestus_forge";
            if (className.startsWith("committee.nova.mods.avaritia.")) return ModIds.ID_AVARITIA_SMITHING;
            return "block.minecraft.smithing_table";
        }
        if (SMELTING.equals(categoryId)) return "block.minecraft.furnace";
        if (BLASTING.equals(categoryId)) return "block.minecraft.blast_furnace";
        if (SMOKING.equals(categoryId)) return "block.minecraft.smoker";
        if (CAMPFIRE.equals(categoryId)) return "block.minecraft.campfire";
        if (STONECUTTING.equals(categoryId)) return "block.minecraft.stonecutter";
        if (ANVIL.equals(categoryId)) return "block.minecraft.anvil";
        if (CRAB_TRAP.equals(categoryId)) return "crabbersdelight";

        if (className.startsWith("committee.nova.mods.avaritia.common.crafting.recipe.")) {
            if (className.endsWith("CompressorRecipe")) return null;
            return className.endsWith("ExtremeSmithingRecipe")
                    ? ModIds.ID_AVARITIA_SMITHING : ModIds.ID_AVARITIA_CRAFTING;
        }

        String classFilter = ModType.filterForRecipeClass(className);
        if (classFilter != null) return classFilter;
        if (className.startsWith("alabaster.crabbersdelight.")) return "crabbersdelight";

        Recipe<?> nativeRecipe = source instanceof Recipe<?> recipe ? recipe : backing;
        if (nativeRecipe != null
                && com.huanghuang.rsintegration.mods.arsnouveau.ArsRecipeClassifier.isGlyph(
                    com.huanghuang.rsintegration.mods.arsnouveau.ArsTileAccess.recipeTypeId(nativeRecipe))) {
            return ModIds.ID_ARS_SCRIBES_TABLE;
        }
        return null;
    }

    private static boolean isCraftingRecipe(Object source, @Nullable Recipe<?> backing,
                                             @Nullable ResourceLocation categoryId) {
        return source instanceof CraftingRecipe || backing instanceof CraftingRecipe
                || CRAFTING.equals(categoryId);
    }

    private static ModType resolveModType(Object source, @Nullable Recipe<?> backing) {
        if (source instanceof com.huanghuang.rsintegration.mods.pmmo.client.PmmoSalvageRecipe) {
            return ModType.byId(com.huanghuang.rsintegration.mods.pmmo.PmmoRSModule.TYPE_ID);
        }
        String className = source.getClass().getName();
        if (className.startsWith("net.blay09.mods.farmingforblockheads.")) {
            return ModType.byId("farmingforblockheads");
        }
        Recipe<?> nativeRecipe = source instanceof Recipe<?> recipe ? recipe : backing;
        if (nativeRecipe != null) {
            ModType type = ModType.classifyRecipe(nativeRecipe);
            if (type != null) return type;
        }
        ModType type = ModType.findByRecipeClass(className);
        return type != null ? type : ModType.GENERIC;
    }

    @Nullable
    private static BindingStorage.BindingEntry findBinding(String filter, Object recipe) {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        for (ItemStack stack : player.getInventory().items) {
            BindingStorage.BindingEntry found = findBinding(stack, filter, recipe);
            if (found != null) return found;
        }
        for (ItemStack stack : player.getInventory().offhand) {
            BindingStorage.BindingEntry found = findBinding(stack, filter, recipe);
            if (found != null) return found;
        }
        for (ItemStack stack : CuriosAccess.stacks(player)) {
            BindingStorage.BindingEntry found = findBinding(stack, filter, recipe);
            if (found != null) return found;
        }
        return null;
    }

    @Nullable
    private static BindingStorage.BindingEntry findBinding(ItemStack stack, String filter, Object recipe) {
        for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
            if (bindingMatchesFilter(entry, filter) && bindingMatchesRecipe(entry, filter, recipe)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean bindingMatchesFilter(BindingStorage.BindingEntry entry, String filter) {
        String blockKey = entry.blockKey();
        if (blockKey == null) return false;
        if (GoetyBindingRules.isGoetyMachineFilter(filter)) {
            return GoetyBindingRules.matches(blockKey, entry.blockRegKey(), filter);
        }
        if (blockKey.contains(filter)) return true;
        if ("pmmo_salvage".equals(filter)) {
            ResourceLocation configured = com.huanghuang.rsintegration.mods.pmmo.client
                    .PmmoSalvageAccess.salvageBlockId();
            return configured != null && configured.toString().equals(entry.blockRegKey());
        }
        int separator = blockKey.indexOf("||");
        if (separator < 0) return false;
        String prefix = blockKey.substring(0, separator);
        return switch (filter) {
            case "vanilla_furnace" -> "ironfurnaces_furnace".equals(prefix);
            case "vanilla_blast_furnace" -> "ironfurnaces_blast_furnace".equals(prefix);
            case "vanilla_smoker" -> "ironfurnaces_smoker".equals(prefix);
            default -> false;
        };
    }

    private static boolean bindingMatchesRecipe(BindingStorage.BindingEntry entry,
                                                 String filter, Object recipe) {
        if (!ModIds.ID_AVARITIA_CRAFTING.equals(filter) || !(recipe instanceof Recipe<?> nativeRecipe)) {
            return true;
        }
        int requiredTier = com.huanghuang.rsintegration.mods.avaritia.CraftingTableBatchDelegate
                .recipeTier(nativeRecipe);
        if (requiredTier <= 0) return true;
        String blockId = entry.blockRegKey();
        if (blockId == null || blockId.isBlank()) blockId = entry.blockKey();
        int machineTier = com.huanghuang.rsintegration.mods.avaritia.CraftingTableBatchDelegate
                .machineTier(ResourceLocation.tryParse(blockId));
        return machineTier <= 0 || machineTier == requiredTier;
    }

    private static Runnable createCraftAction(ResourceLocation recipeId, ResourceLocation dimension,
                                               BlockPos machinePos, String filter, ItemStack base,
                                               ItemStack targetOutput) {
        if ("generic".equals(filter)) {
            return () -> BatchCraftNetworkHandler.CHANNEL.sendToServer(new GenericCraftPacket(recipeId, true));
        }
        if ("block.minecraft.anvil".equals(filter)) {
            return () -> openMachine(dimension, machinePos, recipeId, null);
        }
        ItemStack capturedBase = base.isEmpty() ? null : base.copy();
        ItemStack capturedOutput = targetOutput.isEmpty() ? null : targetOutput.copy();
        return () -> BatchCraftNetworkHandler.CHANNEL.sendToServer(
                new GenericCraftPacket(recipeId, true, dimension, machinePos, 1, false,
                        capturedBase, capturedOutput));
    }

    private static void openMachine(ResourceLocation dimension, BlockPos position,
                                    ResourceLocation recipeId, @Nullable ItemStack baseItem) {
        GuiNavStack.pushCurrent();
        if (BindingBackendResolver.isBeyondDimensionsBinding(dimension, position)) {
            NetworkHandler.CHANNEL.sendToServer(
                    new BeyondDimensionsOpenBoundMachineGuiPacket(dimension, position));
        } else {
            RSSidePanelNetworkHandler.CHANNEL.sendToServer(
                    new OpenBoundMachineGuiPacket(dimension, position, recipeId.toString(), recipeId, baseItem));
        }
    }

    private static boolean supportsGui(@Nullable String blockKey, @Nullable String blockRegistryKey) {
        if (blockKey == null || !BindingEventHandler.supportsGuiByBlockKey(blockKey)) return false;
        if (blockRegistryKey == null || blockRegistryKey.isBlank()) return true;
        ResourceLocation id = ResourceLocation.tryParse(blockRegistryKey);
        if (id == null) return true;
        var block = ForgeRegistries.BLOCKS.getValue(id);
        if (block == null) return true;
        var target = BindingEventHandler.CLASS_TARGET_MAP.get(block.getClass().getName());
        return target == null || target.supportsGui;
    }

    private static ItemStack smithingBase(EmiRecipe recipe, @Nullable ResourceLocation categoryId,
                                          ResourceLocation recipeId, String filter) {
        boolean smithing = "block.minecraft.smithing_table".equals(filter)
                || "hephaestus_forge".equals(filter);
        if (!smithing || !ModIds.FORBIDDEN_ARCANUS.equals(recipeId.getNamespace())) return ItemStack.EMPTY;
        int index = SMITHING.equals(categoryId) ? 1 : 0;
        return ingredientStack(recipe.getInputs(), index);
    }

    private static ItemStack dynamicTargetOutput(EmiRecipe recipe, Object source,
                                                  ResourceLocation recipeId) {
        String className = source.getClass().getName();
        boolean dynamic = className.startsWith("io.redspace.ironsspellbooks.jei.")
                || (ModIds.WIZARDS_REBORN.equals(recipeId.getNamespace())
                    && recipeId.getPath().startsWith("arcane_iterator/"))
                || className.equals("com.hollingsworth.arsnouveau.api.enchanting_apparatus.EnchantmentRecipe")
                || className.equals("com.hollingsworth.arsnouveau.api.enchanting_apparatus.ArmorUpgradeRecipe")
                || isGoetyRitual(className);
        if (!dynamic || recipe.getOutputs().isEmpty()) return ItemStack.EMPTY;
        return recipe.getOutputs().get(0).getItemStack().copy();
    }

    private static ItemStack ingredientStack(List<EmiIngredient> ingredients, int index) {
        if (index < 0 || index >= ingredients.size()) return ItemStack.EMPTY;
        List<EmiStack> stacks = ingredients.get(index).getEmiStacks();
        if (stacks.isEmpty()) return ItemStack.EMPTY;
        return stacks.get(0).getItemStack().copy();
    }

    private static String tooltipKey(Object recipe, String filter, @Nullable ModType modType) {
        if (recipe instanceof com.huanghuang.rsintegration.mods.pmmo.client.PmmoSalvageRecipe) {
            return "gui.rs_integration.jei.pmmo_salvage_craft";
        }
        String className = recipe.getClass().getName();
        if (isGoetyRitual(className)) return "gui.rs_integration.jei.altar_craft";
        if ("com.Polarice3.Goety.common.crafting.BrazierRecipe".equals(className)) {
            return "gui.rs_integration.jei.goety_brazier_craft";
        }
        if (modType != null && modType.jeiTooltipKey() != null) return modType.jeiTooltipKey();
        if (filter.startsWith("block.minecraft.")) return "gui.rs_integration.jei.vanilla_machine_craft";
        if ("generic".equals(filter)) return "gui.rs_integration.jei.rs_auto_craft";
        return switch (filter) {
            case "spirit_altar" -> "gui.rs_integration.jei.malum_spirit_craft";
            case "spirit_crucible" -> "gui.rs_integration.jei.malum_crucible_craft";
            case "crystal_ritual" -> "gui.rs_integration.jei.wr_crystal_craft";
            case "hephaestus_forge" -> "gui.rs_integration.jei.fa_ritual_craft";
            case "crucible" -> "gui.rs_integration.jei.eidolon_crucible_craft";
            case "worktable" -> "gui.rs_integration.jei.eidolon_worktable_craft";
            case "ritual" -> "gui.rs_integration.jei.eidolon_ritual_craft";
            case ModIds.TOUHOU_LITTLE_MAID -> "gui.rs_integration.jei.tlm_maid_altar_craft";
            case ModIds.EMBERS -> "gui.rs_integration.jei.embers_alchemy_craft";
            case ModIds.ID_AVARITIA_CRAFTING -> "gui.rs_integration.jei.avaritia_crafting";
            case ModIds.ID_AVARITIA_SMITHING -> "gui.rs_integration.jei.avaritia_smithing";
            case "crabbersdelight" -> "gui.rs_integration.jei.crabbersdelight_trap";
            default -> "gui.rs_integration.jei.wr_remote_craft";
        };
    }

    private static boolean isUnsupportedGoetyRitual(Object recipe) {
        if (!isGoetyRitual(recipe.getClass().getName())) return false;
        try {
            Object ritual = recipe.getClass().getMethod("getRitual").invoke(recipe);
            return GoetyRitualPolicy.classify(recipe, ritual) == GoetyRitualPolicy.Execution.UNSUPPORTED;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return true;
        }
    }

    private static boolean isGoetyRitual(String className) {
        return ModList.get().isLoaded(ModIds.GOETY)
                && "com.Polarice3.Goety.common.crafting.RitualRecipe".equals(className);
    }

    @Nullable
    private static String classifyYhkCooking(Object recipe) {
        if (!recipe.getClass().getName().startsWith("dev.xkmc.youkaishomecoming.content.pot.cooking.")) {
            return null;
        }
        try {
            ItemStack result = (ItemStack) recipe.getClass().getMethod("getResult").invoke(recipe);
            if (!result.isEmpty() && result.hasCraftingRemainingItem()) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(result.getCraftingRemainingItem().getItem());
                if (id != null) {
                    return switch (id.getPath()) {
                        case "short_iron_pot" -> "youkaishomecoming_cooking_short";
                        case "stockpot" -> "youkaishomecoming_cooking_large";
                        default -> "youkaishomecoming_cooking_small";
                    };
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-EMI] Failed to classify YHK cooking recipe", exception);
        }
        return "youkaishomecoming_cooking_small";
    }

    @Nullable
    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }
        return null;
    }

    private static boolean isJemiSyntheticId(ResourceLocation id) {
        return "emi".equals(id.getNamespace()) && id.getPath().startsWith("jei/");
    }

    private static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }
}
