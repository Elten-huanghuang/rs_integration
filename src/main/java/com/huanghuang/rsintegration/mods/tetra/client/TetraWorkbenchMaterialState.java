package com.huanghuang.rsintegration.mods.tetra.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.jei.TetraJeiItemBridge;
import com.huanghuang.rsintegration.mods.jei.TetraWorkbenchJeiFilterRefreshRegistry;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mirrors Tetra's current workbench material candidates into JEI as item stacks. */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class TetraWorkbenchMaterialState {
    private static final String WORKBENCH_MENU =
            "se.mickelus.tetra.blocks.workbench.WorkbenchContainer";
    private static final String SCHEMATIC_REGISTRY =
            "se.mickelus.tetra.module.SchematicRegistry";
    private static final String DATA_MANAGER = "se.mickelus.tetra.data.DataManager";

    private static boolean active;
    private static Map<String, ItemStack> candidates = Map.of();
    private static Map<String, TetraMaterialSortData> candidateSortData = Map.of();
    private static TetraMaterialSortMode primarySortMode = TetraMaterialSortMode.DEFAULT;
    private static TetraMaterialSortMode secondarySortMode = TetraMaterialSortMode.DEFAULT;
    private static Boolean primaryAscendingOverride;
    private static Boolean secondaryAscendingOverride;
    private static String lastContext;
    private static String lastFailure;
    private static String lastPreviewFailure;
    private static int lastDataHash;
    private static long candidateVersion;
    private static boolean tickLogged;
    private static boolean controlPressArmed = true;
    private static String lastJeiPreviewKey;
    private static String lastJeiPreviewContext;
    private static String lastJeiPreviewIngredientKey;
    private static ItemStack lastJeiPreviewResult = ItemStack.EMPTY;

    private TetraWorkbenchMaterialState() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!tickLogged) {
            tickLogged = true;
            RSIntegrationMod.LOGGER.info("[RSI-Tetra] Client workbench material polling started");
        }
        refresh(Minecraft.getInstance());
    }

    public static void afterWorkbenchRender(Object screen) {
        if (screen instanceof AbstractContainerScreen<?> containerScreen
                && WORKBENCH_MENU.equals(containerScreen.getMenu().getClass().getName())) {
            boolean controlDown = Screen.hasControlDown();
            if (!controlDown) controlPressArmed = true;
            restorePlacedMaterialPreview(containerScreen);
            if (controlDown && controlPressArmed) {
                controlPressArmed = false;
                updateJeiHoverPreview(containerScreen);
            } else {
                expireUncachedJeiPreview(containerScreen);
            }
        } else {
            controlPressArmed = true;
            clearJeiHoverPreview(null);
        }
    }

    public static void refresh(Minecraft minecraft) {
        try {
            update(minecraft);
            lastFailure = null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            lastContext = null;
            String message = failure.getClass().getName() + ": " + failure.getMessage();
            if (!message.equals(lastFailure)) {
                lastFailure = message;
                RSIntegrationMod.LOGGER.warn("[RSI-Tetra] Failed to read workbench materials: {}", message);
            }
            setState(false, List.of());
        }
    }

    private static void update(Minecraft minecraft) throws ReflectiveOperationException {
        if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)
                || !WORKBENCH_MENU.equals(screen.getMenu().getClass().getName())) {
            lastContext = null;
            setState(false, List.of());
            clearJeiHoverPreview(null);
            return;
        }

        Object menu = screen.getMenu();
        Object tile = invoke(menu, "getTileEntity");
        if (!(tile instanceof BlockEntity blockEntity) || blockEntity.isRemoved()) {
            lastContext = null;
            setState(false, List.of());
            return;
        }
        ItemStack target = (ItemStack) invoke(tile, "getTargetItemStack");
        if (target == null || target.isEmpty()
                || Boolean.TRUE.equals(invoke(tile, "isTargetPlaceholder"))) {
            lastContext = null;
            setState(false, List.of());
            return;
        }

        String slot = stringValue(readField(screen, "selectedSlot"));
        if (slot == null || slot.isBlank()) slot = stringValue(invoke(tile, "getCurrentSlot"));
        if (slot == null || slot.isBlank()) {
            lastContext = null;
            setState(false, List.of());
            return;
        }

        Object selected = readField(screen, "currentSchematic");
        String context = contextKey(blockEntity, target, slot, selected, tile);
        Collection<?> materialData = materialDataValues(tile);
        int dataHash = materialData.hashCode();
        if (context.equals(lastContext) && dataHash == lastDataHash) return;
        lastContext = context;
        lastDataHash = dataHash;

        List<Object> schematics = new ArrayList<>();
        if (selected != null) {
            schematics.add(selected);
        } else {
            addSchematics(schematics, tile, blockEntity, target, slot, minecraft);
        }
        if (schematics.isEmpty()) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Tetra] Workbench slot '{}' has no available schematics for target {}",
                    slot, target.getItem());
            setState(true, List.of());
            return;
        }

        List<ItemStack> resolved = new ArrayList<>();
        Map<String, TetraMaterialSortData> resolvedSortData = new LinkedHashMap<>();
        for (Object materialDataEntry : materialData) {
            Object entry = materialDataEntry;
            if (isHidden(entry)) continue;
            Object outcome = readField(entry, "material");
            if (outcome == null) continue;
            Object rawStacks = invoke(outcome, "getApplicableItemStacks");
            for (int stackIndex = 0; stackIndex < Array.getLength(rawStacks); stackIndex++) {
                Object value = Array.get(rawStacks, stackIndex);
                if (!(value instanceof ItemStack material) || material.isEmpty()) continue;
                if (!acceptedByAny(schematics, target, slot, material)) continue;
                ItemStack normalized = material.copyWithCount(1);
                resolved.add(normalized);
                resolvedSortData.putIfAbsent(JeiNetworkInventoryPacket.key(normalized),
                        TetraMaterialSortData.from(entry));
            }
        }
        RSIntegrationMod.LOGGER.info(
                "[RSI-Tetra] Workbench slot '{}' schematic '{}' resolved {} material stacks",
                slot, selected == null ? "<slot candidates>" : invoke(selected, "getKey"),
                resolved.size());
        setState(true, resolved, resolvedSortData);
    }

    private static String contextKey(BlockEntity blockEntity, ItemStack target,
                                     String slot, @Nullable Object selected,
                                     Object tile) throws ReflectiveOperationException {
        Object unlocked = invoke(tile, "getUnlockedSchematics");
        String schematicKey = selected == null ? "<none>"
                : String.valueOf(invoke(selected, "getKey")) + "@"
                + System.identityHashCode(selected);
        return blockEntity.getBlockPos() + "|" + JeiNetworkInventoryPacket.key(target)
                + "|" + slot + "|" + schematicKey
                + "|" + Arrays.deepToString((Object[]) unlocked);
    }

    private static void addSchematics(List<Object> result, Object tile,
                                      BlockEntity blockEntity, ItemStack target,
                                      String slot, Minecraft minecraft)
            throws ReflectiveOperationException {
        if (minecraft.player == null || minecraft.level == null) return;
        ClassLoader loader = tile.getClass().getClassLoader();
        Class<?> registry = loader.loadClass(SCHEMATIC_REGISTRY);
        Method getSchematics = registry.getMethod("getSchematics", ItemStack.class,
                String.class, Player.class, Level.class, BlockPos.class,
                BlockState.class, ResourceLocation[].class);
        Object schematics = getSchematics.invoke(null, target, slot, minecraft.player,
                minecraft.level, blockEntity.getBlockPos(), blockEntity.getBlockState(),
                invoke(tile, "getUnlockedSchematics"));
        for (int i = 0; i < Array.getLength(schematics); i++) {
            Object schematic = Array.get(schematics, i);
            if (schematic != null) result.add(schematic);
        }
    }

    private static Collection<?> materialDataValues(Object tile)
            throws ReflectiveOperationException {
        Class<?> managerClass = tile.getClass().getClassLoader().loadClass(DATA_MANAGER);
        Object manager = managerClass.getField("instance").get(null);
        if (manager == null) return List.of();
        Object store = readField(manager, "materialData");
        if (store == null) return List.of();
        Object data = invoke(store, "getData");
        if (!(data instanceof Map<?, ?> map)) return List.of();
        return new ArrayList<>(map.values());
    }

    private static boolean isHidden(Object materialData) throws ReflectiveOperationException {
        Field hidden = materialData.getClass().getField("hidden");
        return hidden.getBoolean(materialData);
    }

    private static boolean acceptedByAny(List<Object> schematics, ItemStack target,
                                         String slot, ItemStack material)
            throws ReflectiveOperationException {
        for (Object schematic : schematics) {
            Method accepts = findAcceptsMethod(schematic.getClass());
            int slotCount = ((Number) invoke(schematic, "getNumMaterialSlots")).intValue();
            for (int index = 0; index < Math.max(1, slotCount); index++) {
                Object accepted = accepts.invoke(schematic, target, slot, index, material);
                if (Boolean.TRUE.equals(accepted)) return true;
            }
        }
        return false;
    }

    private static Method findAcceptsMethod(Class<?> type) throws NoSuchMethodException {
        for (Class<?> iface : type.getInterfaces()) {
            for (Method method : iface.getMethods()) {
                if (method.getName().equals("acceptsMaterial")
                        && method.getParameterCount() == 4) return method;
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getName().equals("acceptsMaterial") && method.getParameterCount() == 4) {
                return method;
            }
        }
        throw new NoSuchMethodException("UpgradeSchematic.acceptsMaterial");
    }

    private static int requiredMaterialQuantity(Object schematic, ItemStack target,
                                                int materialSlot, ItemStack material)
            throws ReflectiveOperationException {
        for (Method method : schematic.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("getRequiredQuantity") && parameters.length == 3
                    && parameters[0] == ItemStack.class && parameters[1] == int.class
                    && parameters[2] == ItemStack.class) {
                return ((Number) method.invoke(schematic, target, materialSlot, material)).intValue();
            }
        }
        throw new NoSuchMethodException("UpgradeSchematic.getRequiredQuantity");
    }

    private static void setState(boolean nextActive, Collection<ItemStack> nextCandidates) {
        setState(nextActive, nextCandidates, Map.of());
    }

    private static void setState(boolean nextActive, Collection<ItemStack> nextCandidates,
                                 Map<String, TetraMaterialSortData> nextSortData) {
        Map<String, ItemStack> indexed = new LinkedHashMap<>();
        for (ItemStack stack : nextCandidates) {
            if (stack != null && !stack.isEmpty()) {
                ItemStack normalized = stack.copyWithCount(1);
                indexed.putIfAbsent(JeiNetworkInventoryPacket.key(normalized), normalized);
            }
        }
        Map<String, TetraMaterialSortData> indexedSortData = new LinkedHashMap<>();
        for (String key : indexed.keySet()) {
            indexedSortData.put(key, nextSortData.getOrDefault(key, TetraMaterialSortData.empty()));
        }
        boolean changed = active != nextActive || !indexed.keySet().equals(candidates.keySet())
                || !indexedSortData.equals(candidateSortData);
        if (changed) {
            candidateVersion++;
            RSIntegrationMod.LOGGER.info("[RSI-Tetra] JEI material filter changed: active={}, candidates={}",
                    nextActive, indexed.size());
        }
        active = nextActive;
        candidates = Map.copyOf(indexed);
        candidateSortData = Map.copyOf(indexedSortData);
        if (changed) {
            TetraJeiItemBridge.sync(active ? candidates.values() : List.of());
            TetraWorkbenchJeiFilterRefreshRegistry.refresh();
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static long getCandidateVersion() {
        return candidateVersion;
    }

    public static boolean matches(ItemStack stack) {
        return !active || candidates.containsKey(JeiNetworkInventoryPacket.key(stack));
    }

    public static TetraMaterialSortMode getSortMode() {
        return primarySortMode;
    }

    public static void cycleSortMode() {
        setSortMode(TetraMaterialSortMode.next(primarySortMode));
    }

    public static void setSortMode(TetraMaterialSortMode nextMode) {
        if (nextMode == null || nextMode == primarySortMode) return;
        primarySortMode = nextMode;
        primaryAscendingOverride = null;
        if (secondarySortMode == nextMode) {
            secondarySortMode = TetraMaterialSortMode.DEFAULT;
            secondaryAscendingOverride = null;
        }
        candidateVersion++;
        TetraWorkbenchJeiFilterRefreshRegistry.refresh();
    }

    public static TetraMaterialSortMode getPrimarySortMode() {
        return primarySortMode;
    }

    public static TetraMaterialSortMode getSecondarySortMode() {
        return secondarySortMode;
    }

    public static boolean isPrimaryAscendingSelected() {
        return primaryAscendingOverride != null && primaryAscendingOverride;
    }

    public static boolean isPrimaryDescendingSelected() {
        return primaryAscendingOverride != null && !primaryAscendingOverride;
    }

    public static boolean isSecondaryAscendingSelected() {
        return secondaryAscendingOverride != null && secondaryAscendingOverride;
    }

    public static boolean isSecondaryDescendingSelected() {
        return secondaryAscendingOverride != null && !secondaryAscendingOverride;
    }

    public static boolean isSortFieldSelected(TetraMaterialSortMode mode) {
        return mode != null && mode != TetraMaterialSortMode.DEFAULT
                && (mode == primarySortMode || mode == secondarySortMode);
    }

    public static void toggleSortField(TetraMaterialSortMode mode) {
        if (mode == null || mode == TetraMaterialSortMode.DEFAULT) return;
        if (mode == primarySortMode) {
            if (secondarySortMode != TetraMaterialSortMode.DEFAULT) {
                primarySortMode = secondarySortMode;
                primaryAscendingOverride = secondaryAscendingOverride;
                secondarySortMode = TetraMaterialSortMode.DEFAULT;
                secondaryAscendingOverride = null;
            } else {
                primarySortMode = TetraMaterialSortMode.DEFAULT;
                primaryAscendingOverride = null;
            }
        } else if (mode == secondarySortMode) {
            secondarySortMode = TetraMaterialSortMode.DEFAULT;
            secondaryAscendingOverride = null;
        } else if (primarySortMode == TetraMaterialSortMode.DEFAULT) {
            primarySortMode = mode;
            primaryAscendingOverride = null;
        } else if (secondarySortMode == TetraMaterialSortMode.DEFAULT) {
            secondarySortMode = mode;
            secondaryAscendingOverride = null;
        } else {
            return;
        }
        candidateVersion++;
        TetraWorkbenchJeiFilterRefreshRegistry.refresh();
    }

    public static void toggleSortDirection(TetraMaterialSortMode mode, boolean ascending) {
        if (!isSortFieldSelected(mode)) return;
        if (mode == primarySortMode) {
            primaryAscendingOverride = primaryAscendingOverride != null
                    && primaryAscendingOverride == ascending ? null : ascending;
        } else {
            secondaryAscendingOverride = secondaryAscendingOverride != null
                    && secondaryAscendingOverride == ascending ? null : ascending;
        }
        candidateVersion++;
        TetraWorkbenchJeiFilterRefreshRegistry.refresh();
    }

    public static int compareForJei(ItemStack left, ItemStack right) {
        TetraMaterialSortData leftData = candidateSortData.getOrDefault(
                JeiNetworkInventoryPacket.key(left), TetraMaterialSortData.empty());
        TetraMaterialSortData rightData = candidateSortData.getOrDefault(
                JeiNetworkInventoryPacket.key(right), TetraMaterialSortData.empty());
        int primary = compareMode(primarySortMode, leftData, rightData,
                primaryAscendingOverride == null
                        ? defaultAscending(primarySortMode) : primaryAscendingOverride);
        if (primary != 0 || secondarySortMode == TetraMaterialSortMode.DEFAULT
                || secondarySortMode == primarySortMode) {
            return primary;
        }
        return compareMode(secondarySortMode, leftData, rightData,
                secondaryAscendingOverride == null
                        ? defaultAscending(secondarySortMode) : secondaryAscendingOverride);
    }

    private static int compareMode(TetraMaterialSortMode mode,
                                   TetraMaterialSortData left,
                                   TetraMaterialSortData right,
                                   boolean ascending) {
        if (mode == TetraMaterialSortMode.DEFAULT) return 0;
        if (mode == TetraMaterialSortMode.CATEGORY) {
            int result = left.categoryLabel().compareToIgnoreCase(right.categoryLabel());
            return ascending ? result : -result;
        }
        double leftValue;
        double rightValue;
        switch (mode) {
            case HARDNESS -> { leftValue = left.hardness(); rightValue = right.hardness(); }
            case DENSITY -> { leftValue = left.density(); rightValue = right.density(); }
            case FLEXIBILITY -> { leftValue = left.flexibility(); rightValue = right.flexibility(); }
            case DURABILITY -> { leftValue = left.durability(); rightValue = right.durability(); }
            case TOOL_LEVEL -> { leftValue = left.toolLevel(); rightValue = right.toolLevel(); }
            case TOOL_EFFICIENCY -> { leftValue = left.toolEfficiency(); rightValue = right.toolEfficiency(); }
            case INTEGRITY_GAIN -> { leftValue = left.integrityGain(); rightValue = right.integrityGain(); }
            case INTEGRITY_COST -> { leftValue = left.integrityCost(); rightValue = right.integrityCost(); }
            case MAGIC_CAPACITY -> { leftValue = left.magicCapacity(); rightValue = right.magicCapacity(); }
            default -> { return 0; }
        }
        return compareNumber(leftValue, rightValue, ascending);
    }

    private static int compareNumber(double left, double right, boolean ascending) {
        boolean leftMissing = Double.isNaN(left);
        boolean rightMissing = Double.isNaN(right);
        if (leftMissing || rightMissing) {
            if (leftMissing == rightMissing) return 0;
            return leftMissing ? 1 : -1;
        }
        int result = Double.compare(left, right);
        return ascending ? result : -result;
    }

    private static boolean defaultAscending(TetraMaterialSortMode mode) {
        return mode == TetraMaterialSortMode.CATEGORY
                || mode == TetraMaterialSortMode.INTEGRITY_COST
                || mode == TetraMaterialSortMode.DEFAULT;
    }

    public static void refreshForJei() {
        lastContext = null;
        refresh(Minecraft.getInstance());
        TetraJeiItemBridge.sync(active ? candidates.values() : List.of());
        TetraWorkbenchJeiFilterRefreshRegistry.refresh();
    }

    private static void updateJeiHoverPreview(AbstractContainerScreen<?> screen) {
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) {
            clearJeiHoverPreview(screen);
            return;
        }

        try {
            Object tile = invoke(screen.getMenu(), "getTileEntity");
            ItemStack target = (ItemStack) invoke(tile, "getTargetItemStack");
            Object schematic = readField(screen, "currentSchematic");
            String slot = stringValue(readField(screen, "selectedSlot"));
            if (slot == null || slot.isBlank()) slot = stringValue(invoke(tile, "getCurrentSlot"));
            if (target == null || target.isEmpty() || schematic == null
                    || slot == null || slot.isBlank()) {
                clearJeiHoverPreview(screen);
                return;
            }

            String previewContext = JeiNetworkInventoryPacket.key(target) + "|" + slot + "|"
                    + System.identityHashCode(schematic);
            if (lastJeiPreviewContext != null
                    && !lastJeiPreviewContext.equals(previewContext)) {
                clearJeiHoverPreview(screen);
            }

            ItemStack[] currentMaterials = (ItemStack[]) invoke(tile, "getMaterials");
            boolean hasPlacedMaterial = Arrays.stream(currentMaterials)
                    .anyMatch(material -> material != null && !material.isEmpty());
            if (hasPlacedMaterial) {
                if (lastJeiPreviewKey != null || lastJeiPreviewContext != null) {
                    clearJeiHoverPreview(screen);
                }
                return;
            }

            ItemStack hovered = active ? runtime.getIngredientListOverlay()
                    .getIngredientUnderMouse(VanillaTypes.ITEM_STACK) : null;
            if (hovered == null || hovered.isEmpty() || !matches(hovered)) {
                if (RSIntegrationConfig.TETRA_JEI_PREVIEW_CACHE.get()
                        && lastJeiPreviewResult != null && !lastJeiPreviewResult.isEmpty()
                        && previewContext.equals(lastJeiPreviewContext)) {
                    invoke(screen, "updateItemDisplay", target, lastJeiPreviewResult);
                } else {
                    clearJeiHoverPreview(screen);
                }
                return;
            }

            String previewKey = previewContext + "|"
                    + JeiNetworkInventoryPacket.key(hovered);
            if (previewKey.equals(lastJeiPreviewKey)) {
                invoke(screen, "updateItemDisplay", target, lastJeiPreviewResult);
                return;
            }

            Method buildPreview = findPreviewMethod(screen.getClass(), schematic);
            int materialSlotCount = Math.max(1,
                    ((Number) invoke(schematic, "getNumMaterialSlots")).intValue());
            ItemStack[] previewMaterials = new ItemStack[materialSlotCount];
            for (int index = 0; index < previewMaterials.length; index++) {
                if (previewMaterials[index] == null) previewMaterials[index] = ItemStack.EMPTY;
            }

            Method acceptsMaterial = findAcceptsMethod(schematic.getClass());
            int previewMaterialCount = 0;
            for (int index = 0; index < materialSlotCount; index++) {
                if (!Boolean.TRUE.equals(acceptsMaterial.invoke(
                        schematic, target, slot, index, hovered))) continue;
                previewMaterialCount = requiredMaterialQuantity(
                        schematic, target, index, hovered);
                if (previewMaterialCount <= 0) continue;
                previewMaterials[index] = hovered.copyWithCount(previewMaterialCount);
                break;
            }
            ItemStack preview = (ItemStack) buildPreview.invoke(screen, schematic, target, slot,
                    previewMaterials);
            invoke(screen, "updateItemDisplay", target, preview);
            lastJeiPreviewKey = previewKey;
            lastJeiPreviewContext = previewContext;
            lastJeiPreviewIngredientKey = JeiNetworkInventoryPacket.key(hovered);
            lastJeiPreviewResult = preview == null ? ItemStack.EMPTY : preview.copy();
            boolean validPreview = preview != null && !preview.isEmpty();
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Tetra] JEI hover preview material {}: inputCount={}, valid={}, result={}, "
                            + "targetTagHash={}, previewTagHash={}",
                    hovered.getItem(), previewMaterialCount, validPreview,
                    validPreview ? preview.getItem() : "<empty>",
                    target.getTag() == null ? 0 : target.getTag().hashCode(),
                    validPreview && preview.getTag() != null ? preview.getTag().hashCode() : 0);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            if (!message.equals(lastPreviewFailure)) {
                lastPreviewFailure = message;
                RSIntegrationMod.LOGGER.warn("[RSI-Tetra] Failed to preview JEI material: {}", message);
            }
            clearJeiHoverPreview(screen);
        }
    }

    private static void restorePlacedMaterialPreview(AbstractContainerScreen<?> screen) {
        if (lastJeiPreviewKey == null && lastJeiPreviewContext == null) return;
        try {
            Object tile = invoke(screen.getMenu(), "getTileEntity");
            ItemStack[] materials = (ItemStack[]) invoke(tile, "getMaterials");
            if (Arrays.stream(materials).anyMatch(material -> material != null && !material.isEmpty())) {
                clearJeiHoverPreview(screen);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Tetra owns material-slot state; reflection failures must not affect the screen.
        }
    }

    private static void expireUncachedJeiPreview(AbstractContainerScreen<?> screen) {
        if (RSIntegrationConfig.TETRA_JEI_PREVIEW_CACHE.get()
                || lastJeiPreviewKey == null) return;
        IJeiRuntime runtime = RSJeiPlugin.getRuntime();
        if (runtime == null) {
            clearJeiHoverPreview(screen);
            return;
        }
        ItemStack hovered = runtime.getIngredientListOverlay()
                .getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        if (hovered == null || hovered.isEmpty()
                || !JeiNetworkInventoryPacket.key(hovered).equals(lastJeiPreviewIngredientKey)) {
            clearJeiHoverPreview(screen);
        }
    }

    private static void clearJeiHoverPreview(@Nullable AbstractContainerScreen<?> screen) {
        if (lastJeiPreviewKey == null && lastJeiPreviewContext == null) return;
        lastJeiPreviewKey = null;
        lastJeiPreviewContext = null;
        lastJeiPreviewIngredientKey = null;
        lastJeiPreviewResult = ItemStack.EMPTY;
        if (screen == null) return;
        try {
            Field previewMaterialSlot = findField(screen.getClass(), "previewMaterialSlot");
            previewMaterialSlot.setInt(screen, -2);
            invoke(screen, "updateMaterialHoverPreview");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Tetra owns the normal preview state; failure to restore it must not affect JEI.
        }
    }

    @Nullable
    static Object readField(Object instance, String name) {
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object invoke(Object instance, String name) throws ReflectiveOperationException {
        Method method = instance.getClass().getMethod(name);
        return method.invoke(instance);
    }

    private static Object invoke(Object instance, String name, Object... arguments)
            throws ReflectiveOperationException {
        Class<?>[] parameterTypes = Arrays.stream(arguments).map(argument -> {
            if (argument instanceof ItemStack[]) return ItemStack[].class;
            return argument.getClass();
        }).toArray(Class<?>[]::new);
        return findMethod(instance.getClass(), name, parameterTypes).invoke(instance, arguments);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Method findPreviewMethod(Class<?> screenType, Object schematic)
            throws NoSuchMethodException {
        Class<?> current = screenType;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("buildPreviewStack") && parameters.length == 4
                        && parameters[0].isInstance(schematic)
                        && parameters[1] == ItemStack.class
                        && parameters[2] == String.class
                        && parameters[3] == ItemStack[].class) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(screenType.getName() + ".buildPreviewStack");
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    @Nullable
    private static String stringValue(@Nullable Object value) {
        return value instanceof String string ? string : null;
    }
}
