package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoritesSavedData;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmAltarStructure;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.mods.youkaishomecoming.YoukaiRegistryIds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BindingEventHandler {

    private static final List<MachineBindingTarget> TARGETS = new ArrayList<>();
    private static final Object BINDING_LOCK = new Object();
    private static final ThreadLocal<Boolean> EXPLICIT_BIND_REQUEST =
            ThreadLocal.withInitial(() -> false);

    private BindingEventHandler() {}

    public static void registerTarget(MachineBindingTarget target) {
        TARGETS.add(target);
        for (String className : target.blockClassNames) {
            CLASS_TARGET_MAP.put(className, target);
        }
        String prefix = target.blockKeyPrefix != null ? target.blockKeyPrefix : "";
        PREFIX_GUI_MAP.merge(prefix, target.supportsGui, (old, cur) -> old || cur);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!RSIntegrationConfig.ENABLE_BINDING.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.isShiftKeyDown() && !EXPLICIT_BIND_REQUEST.get()) return;

        Block block = event.getLevel().getBlockState(event.getPos()).getBlock();
        String className = block.getClass().getName();

        MachineBindingTarget matched = findEnabledTarget(block);

        if (matched == null) {
            RSIntegrationMod.LOGGER.debug("[RSI-Bind] No match: class={} regName={}",
                    className,
                    ForgeRegistries.BLOCKS.getKey(block));
        }

        if (matched == null) {
            ResourceLocation regName = ForgeRegistries.BLOCKS.getKey(block);
            if (regName == null) return;
            boolean inCustomList = RSIntegrationConfig.CUSTOM_GUI_MACHINE_MODS.get().stream()
                    .anyMatch(regName.getNamespace()::equals);
            if (!inCustomList) return;
            BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
            boolean hasMenu = be instanceof MenuProvider;
            matched = new MachineBindingTarget(regName.getNamespace(), ModType.byId("custom_gui"),
                    RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS, List.of(), null, hasMenu);
        }

        ItemStack held = player.getItemInHand(event.getHand());
        Optional<IBindingHook> hook = AltarBindingRegistry.findHook(held);
        if (hook.isEmpty()) {
            return;
        }
        // In a BD-only installation, crouch-right-click belongs to BD's
        // native terminal action. RSI has no legacy RS binding gesture to
        // preserve there, so machine binding must always come from the
        // explicit configurable Alt+right-click packet.
        if (!ModList.get().isLoaded(com.huanghuang.rsintegration.util.ModIds.REFINED_STORAGE)
                && !EXPLICIT_BIND_REQUEST.get()) {
            return;
        }
        Optional<AltarBinding> selectedBinding = hook.get().createBinding(held);
        if (selectedBinding.isEmpty()) return;
        ResourceLocation bindingType = selectedBinding.orElseThrow().type();
        // BD's portable terminal has its own native crouch-right-click action
        // for binding the terminal to a BD network. RSI machine bindings for
        // that terminal are deliberately a separate, explicit client action
        // (the configurable Alt+right-click packet). Without this guard the
        // same physical click can enter both paths and toggle the RSI machine
        // binding twice, producing an immediate bind/unbind pair.
        if (AltarBinding.BD_NETWORK.equals(bindingType)
                && !EXPLICIT_BIND_REQUEST.get()) {
            // BD terminals use crouch-right-click for their own native network
            // binding. RSI machine binding is intentionally available only via
            // the explicit configurable Alt+right-click action, so a normal
            // crouch-right-click must never toggle an RSI machine binding.
            return;
        }

        BlockPos clickedPos = event.getPos();
        BlockPos pos = clickedPos;
        BlockPos bindingPos = resolveRootPos(event.getLevel(), pos, block, className);
        if ("goety_cursed_infuser".equals(matched.modType.id())) {
            Component problem = com.huanghuang.rsintegration.mods.goety
                    .GoetyInfuserMachineSupport.bindingProblem(event.getLevel(), bindingPos);
            if (problem != null) {
                player.displayClientMessage(problem, true);
                event.setCanceled(true);
                return;
            }
        }
        if ("forbidden_arcanus_clibano".equals(matched.modType.id())
                && bindingPos.equals(pos)
                && !className.equals("com.stal111.forbidden_arcanus.common.block.ClibanoMainPartBlock")) {
            player.displayClientMessage(Component.translatable("rsi.clibano.error.structure_missing"), true);
            event.setCanceled(true);
            return;
        }

        // If root resolution moved us to the master block, recompute identity
        // from the root position so blockKey/blockRegKey/displayStack reflect
        // the master, not the slave half that was clicked.
        if (!bindingPos.equals(pos)) {
            BlockState rootState = event.getLevel().getBlockState(bindingPos);
            Block rootBlock = rootState.getBlock();
            block = rootBlock;
            className = rootBlock.getClass().getName();
            MachineBindingTarget rootTarget = findTarget(rootBlock);
            if (rootTarget != null) matched = rootTarget;
            pos = bindingPos;
        }

        ResourceLocation dim = event.getLevel().dimension().location();
        BlockState state = event.getLevel().getBlockState(pos);
        net.minecraft.world.level.block.entity.BlockEntity be = event.getLevel().getBlockEntity(pos);
        String blockKey = matched.blockKey(block);
        if ("ironfurnaces_furnace".equals(matched.modType.id())
                && be != null && isIronFurnace(be)) {
            blockKey = ironFurnacePrefix(be) + "||" + block.getDescriptionId();
        }
        String blockRegKey = ForgeRegistries.BLOCKS.getKey(block).toString();

        // Extract displayStack BEFORE resolveBlockName so the NBT-bearing
        // stack (with BlockEntityTag/BlockId) is available for name resolution.
        // Previously this was after the call, so displayStack was always null.
        ItemStack displayStack = state.getBlock().getCloneItemStack(event.getLevel(), pos, state);
        if (be != null && !displayStack.isEmpty()) {
            // TACZ gun workbenches need the full BlockEntityTag for BEWLR
            // rendering and getName() / getHoverName().  Other BEs (YHK
            // fermentation tanks, etc.) must NOT have their full data stored
            // — large fluid tank / recipe progress NBT bloats the binding
            // item and breaks RS Addons network item detection.
            boolean isTacz = be.getClass().getName().contains("GunSmithTable");
            if (isTacz) {
                net.minecraft.nbt.CompoundTag beData = be.saveWithoutMetadata();
                if (!beData.isEmpty()) {
                    beData.remove("Items");
                    beData.remove("Inventory");
                    beData.remove("inventory");
                    beData.remove("Energy");
                    displayStack.getOrCreateTag().put("BlockEntityTag", beData);
                    if (beData.contains("BlockId", net.minecraft.nbt.Tag.TAG_STRING)) {
                        displayStack.getOrCreateTag().putString("BlockId", beData.getString("BlockId"));
                    }
                }
            }
        }

        Component blockName = resolveBlockName(blockKey, blockRegKey, displayStack);

        synchronized (BINDING_LOCK) {
            // Migrate bindings created before a multi-block part was taught to
            // resolve to its root. This prevents duplicate part/core entries.
            if (!clickedPos.equals(bindingPos)
                    && BindingStorage.hasBinding(held, dim, clickedPos)) {
                BindingStorage.removeBinding(held, dim, clickedPos);
                AltarBindingRegistry.unbind(
                        player.getUUID(), event.getLevel().dimension(), clickedPos,
                        bindingType);
                MachineFavoritesSavedData.get(player.server).removeAt(
                        player.getUUID(), dim, clickedPos);
            }
            if (BindingStorage.hasBinding(held, dim, bindingPos)) {
                BindingStorage.removeBinding(held, dim, bindingPos);
                AltarBindingRegistry.unbind(player.getUUID(), event.getLevel().dimension(),
                        bindingPos, bindingType);
                MachineFavoritesSavedData.get(player.server).removeAt(
                        player.getUUID(), dim, bindingPos);
                AltarBindingRegistry.invalidateScanCache();
                RSIntegrationNetwork.invalidateNetworkResolution(player.getUUID());
                player.displayClientMessage(
                        Component.translatable("gui.rs_integration.altar.unbound", blockName),
                        false);
                sendBindingRefresh(player);
            } else {
                Optional<AltarBinding> binding = selectedBinding;
                if (binding.isPresent()) {
                    AltarBindingRegistry.bind(player.getUUID(), event.getLevel().dimension(),
                            bindingPos, binding.get());
                    BindingStorage.addBinding(held, dim, bindingPos, blockKey, blockRegKey, displayStack);
                    AltarBindingRegistry.invalidateScanCache();
                    RSIntegrationNetwork.invalidateNetworkResolution(player.getUUID());
                    Component dimensionName = Component.translatable(
                            "dimension." + dim.getNamespace() + "." + dim.getPath())
                            .withStyle(ChatFormatting.YELLOW);
                    Component coloredBlockName = blockName.copy().withStyle(ChatFormatting.GOLD);
                    Component coloredPosition = Component.literal(bindingPos.toShortString())
                            .withStyle(ChatFormatting.AQUA);
                    player.displayClientMessage(
                            Component.translatable("gui.rs_integration.altar.bound",
                                    coloredBlockName, dimensionName, coloredPosition),
                            false);
                    sendBindingRefresh(player);
                }
            }
        }
        event.setCanceled(true);
    }

    /** Reuses the normal binding path for client-configurable mouse chords. */
    public static void handleExplicitBind(ServerPlayer player, BlockPos pos,
                                          net.minecraft.world.InteractionHand hand) {
        if (player == null || pos == null || hand == null) return;
        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(pos),
                net.minecraft.core.Direction.UP, pos, false);
        PlayerInteractEvent.RightClickBlock event =
                new PlayerInteractEvent.RightClickBlock(player, hand, pos, hit);
        EXPLICIT_BIND_REQUEST.set(true);
        try {
            onRightClickBlock(event);
        } finally {
            EXPLICIT_BIND_REQUEST.remove();
        }
    }

    static boolean isPotentialNearbyTarget(Block block) {
        if (isEnabledRegisteredTarget(block)) return true;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        return id != null && RSIntegrationConfig.CUSTOM_GUI_MACHINE_MODS.get().stream()
                .anyMatch(id.getNamespace()::equals);
    }

    static boolean isEnabledRegisteredTarget(Block block) {
        return findEnabledTarget(block) != null;
    }

    @Nullable
    static NearbyTarget prepareNearbyTarget(net.minecraft.server.level.ServerLevel level,
                                            BlockPos clickedPos) {
        Block block = level.getBlockState(clickedPos).getBlock();
        String className = block.getClass().getName();
        MachineBindingTarget target = findEnabledTarget(block);
        if (target == null) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            if (id == null || !RSIntegrationConfig.CUSTOM_GUI_MACHINE_MODS.get().stream()
                    .anyMatch(id.getNamespace()::equals)) {
                return null;
            }
            BlockEntity be = level.getBlockEntity(clickedPos);
            if (!(be instanceof MenuProvider)) return null;
            target = new MachineBindingTarget(id.getNamespace(), ModType.byId("custom_gui"),
                    RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS, List.of(), null,
                    true);
        }

        BlockPos rootPos = resolveRootPos(level, clickedPos, block, className);
        if ("goety_cursed_infuser".equals(target.modType.id())
                && com.huanghuang.rsintegration.mods.goety.GoetyInfuserMachineSupport
                .bindingProblem(level, rootPos) != null) {
            return null;
        }
        if ("forbidden_arcanus_clibano".equals(target.modType.id())
                && rootPos.equals(clickedPos)
                && !className.equals(
                "com.stal111.forbidden_arcanus.common.block.ClibanoMainPartBlock")) {
            return null;
        }

        if (!rootPos.equals(clickedPos)) {
            MachineBindingTarget rootTarget = findEnabledTarget(
                    level.getBlockState(rootPos).getBlock());
            if (rootTarget != null) target = rootTarget;
        }
        return new NearbyTarget(rootPos.immutable(), target);
    }

    static NearbyBindResult bindNearbyTarget(ServerPlayer player,
                                             net.minecraft.server.level.ServerLevel level,
                                             ItemStack connector,
                                             AltarBinding networkBinding,
                                             NearbyTarget nearbyTarget,
                                             Set<BlockPos> knownPlayerBindings) {
        BlockPos pos = nearbyTarget.rootPos();
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        MachineBindingTarget target = nearbyTarget.target();
        MachineBindingTarget currentTarget = findEnabledTarget(block);
        if (currentTarget != null) target = currentTarget;

        BlockEntity be = level.getBlockEntity(pos);
        String blockKey = target.blockKey(block);
        if ("ironfurnaces_furnace".equals(target.modType.id())
                && be != null && isIronFurnace(be)) {
            blockKey = ironFurnacePrefix(be) + "||" + block.getDescriptionId();
        }
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
        String blockRegKey = blockId != null ? blockId.toString() : null;
        ItemStack displayStack = createDisplayStack(level, pos, state, be);
        ResourceLocation dim = level.dimension().location();

        synchronized (BINDING_LOCK) {
            if (knownPlayerBindings.contains(pos)
                    || BindingStorage.hasBinding(connector, dim, pos)
                    || AltarBindingRegistry.isBound(level.dimension(), pos, player)) {
                return NearbyBindResult.ALREADY_BOUND;
            }
            if (!BindingStorage.addBinding(
                    connector, dim, pos, blockKey, blockRegKey, displayStack)) {
                return NearbyBindResult.ALREADY_BOUND;
            }
            AltarBindingRegistry.bind(player.getUUID(), level.dimension(), pos,
                    new AltarBinding(networkBinding.type(), networkBinding.displayName(),
                            networkBinding.data().copy()));
            knownPlayerBindings.add(pos);
            return NearbyBindResult.BOUND;
        }
    }

    private static MachineBindingTarget findEnabledTarget(Block block) {
        String className = block.getClass().getName();
        for (MachineBindingTarget target : TARGETS) {
            if (!target.configFlag.get()) continue;
            if (!target.modId.equals("minecraft") && !ModList.get().isLoaded(target.modId)) continue;
            if (target.matches(block, className)) return target;
        }
        return null;
    }

    private static ItemStack createDisplayStack(Level level, BlockPos pos, BlockState state,
                                                 @Nullable BlockEntity be) {
        ItemStack displayStack = state.getBlock().getCloneItemStack(level, pos, state);
        if (be == null || displayStack.isEmpty()
                || !be.getClass().getName().contains("GunSmithTable")) {
            return displayStack;
        }
        net.minecraft.nbt.CompoundTag beData = be.saveWithoutMetadata();
        if (beData.isEmpty()) return displayStack;
        beData.remove("Items");
        beData.remove("Inventory");
        beData.remove("inventory");
        beData.remove("Energy");
        displayStack.getOrCreateTag().put("BlockEntityTag", beData);
        if (beData.contains("BlockId", net.minecraft.nbt.Tag.TAG_STRING)) {
            displayStack.getOrCreateTag().putString("BlockId", beData.getString("BlockId"));
        }
        return displayStack;
    }

    record NearbyTarget(BlockPos rootPos, MachineBindingTarget target) {}

    enum NearbyBindResult { BOUND, ALREADY_BOUND }

    private static boolean isIronFurnace(BlockEntity be) {
        Class<?> type = be.getClass();
        while (type != null) {
            if ("ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase".equals(type.getName())) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    private static String ironFurnacePrefix(BlockEntity be) {
        try {
            Object recipeType = be.getClass().getField("recipeType").get(be);
            ResourceLocation id = recipeType instanceof net.minecraft.world.item.crafting.RecipeType<?> type
                    ? ForgeRegistries.RECIPE_TYPES.getKey(type) : null;
            if (id != null && "blasting".equals(id.getPath())) return "ironfurnaces_blast_furnace";
            if (id != null && "smoking".equals(id.getPath())) return "ironfurnaces_smoker";
        } catch (ReflectiveOperationException exception) {
            RSIntegrationMod.LOGGER.debug("[RSI-Bind] Iron Furnaces recipe type probe failed", exception);
        }
        return "ironfurnaces_furnace";
    }

    public static final class MachineBindingTarget {
        final String modId;
        final ModType modType;
        final ForgeConfigSpec.BooleanValue configFlag;
        private final List<String> blockClassNames;
        @Nullable
        private final List<String> blockRegistryKeys;
        @Nullable
        private final String blockKeyPrefix;
        public final boolean supportsGui;

        public MachineBindingTarget(String modId, ModType modType, ForgeConfigSpec.BooleanValue configFlag,
                                     List<String> blockClassNames, @Nullable String blockKeyPrefix) {
            this(modId, modType, configFlag, blockClassNames, null, blockKeyPrefix, true);
        }

        public MachineBindingTarget(String modId, ModType modType, ForgeConfigSpec.BooleanValue configFlag,
                                     List<String> blockClassNames, @Nullable String blockKeyPrefix,
                                     boolean supportsGui) {
            this(modId, modType, configFlag, blockClassNames, null, blockKeyPrefix, supportsGui);
        }

        public MachineBindingTarget(String modId, ModType modType, ForgeConfigSpec.BooleanValue configFlag,
                                     List<String> blockClassNames, @Nullable List<String> blockRegistryKeys,
                                     @Nullable String blockKeyPrefix, boolean supportsGui) {
            this.modId = modId;
            this.modType = modType;
            this.configFlag = configFlag;
            this.blockClassNames = blockClassNames;
            this.blockRegistryKeys = blockRegistryKeys;
            this.blockKeyPrefix = blockKeyPrefix;
            this.supportsGui = supportsGui;
        }

        public ModType modType() { return modType; }

        boolean matches(Block block, String className) {
            // PMMO's salvage block belongs to the active world's server config,
            // which is not available when common setup registers targets.
            if (com.huanghuang.rsintegration.mods.pmmo.PmmoRSModule.TYPE_ID.equals(modType.id())
                    && com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageStructure
                    .isBindingBlock(block)) {
                return true;
            }
            for (String name : blockClassNames) {
                if (className.equals(name)) return true;
            }
            // Walk superclass hierarchy — Apotheosis and other mods may
            // replace vanilla blocks with subclasses (e.g. ApothAnvilBlock
            // extends AnvilBlock), so exact leaf-name matching is not enough.
            Class<?> clazz = block.getClass().getSuperclass();
            while (clazz != null) {
                for (String name : blockClassNames) {
                    if (clazz.getName().equals(name)) return true;
                }
                clazz = clazz.getSuperclass();
            }
            // Try registry key matching for blocks with generic classes
            // (e.g. L2ModularBlock DelegateBlock used by Youkai's Homecoming).
            if (blockRegistryKeys != null) {
                ResourceLocation regKey = ForgeRegistries.BLOCKS.getKey(block);
                if (regKey != null) {
                    String regStr = regKey.toString();
                    for (String key : blockRegistryKeys) {
                        if (regStr.equals(key)) return true;
                    }
                }
            }
            return false;
        }

        String blockKey(Block block) {
            if (blockKeyPrefix != null) {
                return blockKeyPrefix + "||" + block.getDescriptionId();
            }
            return block.getDescriptionId();
        }
    }

    public static Component resolveBlockName(String blockKey) {
        int sep = blockKey.indexOf("||");
        String descId = (sep >= 0 && sep < blockKey.length() - 2) ? blockKey.substring(sep + 2) : blockKey;
        String regKey = descIdToRegKey(descId);
        return resolveBlockName(blockKey, regKey, null);
    }

    private static String descIdToRegKey(String descId) {
        if (!descId.startsWith("block.")) return null;
        String rest = descId.substring(6);
        int dot = rest.indexOf('.');
        if (dot <= 0) return null;
        return rest.substring(0, dot) + ":" + rest.substring(dot + 1);
    }

    public static Component resolveBlockName(String blockKey, @Nullable String blockRegKey) {
        return resolveBlockName(blockKey, blockRegKey, null);
    }

    public static Component resolveBlockName(String blockKey, @Nullable String blockRegKey,
                                             @Nullable ItemStack displayStack) {
        if (blockKey != null && blockKey.startsWith("pmmo_salvage||")) {
            return pmmoSalvageDisplayName();
        }
        int sep = blockKey.indexOf("||");
        String descId = (sep >= 0 && sep < blockKey.length() - 2) ? blockKey.substring(sep + 2) : blockKey;

        if (displayStack != null && !displayStack.isEmpty() && displayStack.hasTag()) {
            String realBlockId = null;
            var tag = displayStack.getTag();
            if (tag.contains("BlockId", net.minecraft.nbt.Tag.TAG_STRING)) {
                realBlockId = tag.getString("BlockId");
            } else if (tag.contains("BlockEntityTag")) {
                var beTag = tag.getCompound("BlockEntityTag");
                if (beTag.contains("BlockId", net.minecraft.nbt.Tag.TAG_STRING)) {
                    realBlockId = beTag.getString("BlockId");
                }
            }
            if (realBlockId != null && !realBlockId.isEmpty()) {
                // Redirect multi-block parts to the assembled machine so
                // names resolve to an actual translation key (e.g.
                // workbench_a → gun_smith_table → "枪械工作台").
                String mapped = MULTI_PART_ROOT_MAP.get(realBlockId);
                String resolveId = mapped != null ? mapped : realBlockId;
                var rl = ResourceLocation.tryParse(resolveId);
                if (rl != null) {
                    var realBlock = ForgeRegistries.BLOCKS.getValue(rl);
                    if (realBlock != null && realBlock != net.minecraft.world.level.block.Blocks.AIR) {
                        return Component.translatable(realBlock.getDescriptionId());
                    }
                }
                // Gun-pack workbenches are not registered in Forge — their
                // BlockId lives in TACZ's block index.  If the BlockId was
                // nested in BlockEntityTag (existing bindings), temporarily
                // lift it to the root level on a COPY so that
                // GunSmithTableItem.getName() can see it.
                String rootBlockId = tag.contains("BlockId", net.minecraft.nbt.Tag.TAG_STRING)
                        ? tag.getString("BlockId") : null;
                if (rootBlockId == null) {
                    ItemStack copy = displayStack.copy();
                    copy.getOrCreateTag().putString("BlockId", realBlockId);
                    Component hoverName = copy.getHoverName();
                    if (hoverName != null && !hoverName.getString().isEmpty()) {
                        return hoverName;
                    }
                }
            }
            Component hoverName = displayStack.getHoverName();
            if (hoverName != null && !hoverName.getString().isEmpty()) {
                return hoverName;
            }
        }

        if (blockRegKey != null) {
            String rootKey = MULTI_PART_ROOT_MAP.get(blockRegKey);
            if (rootKey != null) {
                var rl = ResourceLocation.tryParse(rootKey);
                if (rl != null) {
                    var rootBlock = ForgeRegistries.BLOCKS.getValue(rl);
                    if (rootBlock != null) return Component.translatable(rootBlock.getDescriptionId());
                }
            }
        }

        String rootKeyFromDesc = MULTI_PART_ROOT_MAP.get(descId);
        if (rootKeyFromDesc != null) {
            var rl = ResourceLocation.tryParse(rootKeyFromDesc);
            if (rl != null) {
                var rootBlock = ForgeRegistries.BLOCKS.getValue(rl);
                if (rootBlock != null) return Component.translatable(rootBlock.getDescriptionId());
            }
        }

        return Component.translatable(descId);
    }

    private static Component pmmoSalvageDisplayName() {
        return Component.translatable("rsi.batch.mod.pmmo_salvage");
    }

    public static final Map<String, MachineBindingTarget> CLASS_TARGET_MAP = new LinkedHashMap<>();

    @Nullable
    public static MachineBindingTarget findTargetByClass(String className) {
        MachineBindingTarget exact = CLASS_TARGET_MAP.get(className);
        if (exact != null) return exact;
        try {
            Class<?> blockClass = Class.forName(className, false, BindingEventHandler.class.getClassLoader());
            for (MachineBindingTarget target : TARGETS) {
                for (String targetName : target.blockClassNames) {
                    Class<?> targetClass = Class.forName(targetName, false,
                            BindingEventHandler.class.getClassLoader());
                    if (targetClass.isAssignableFrom(blockClass)) return target;
                }
            }
        } catch (ClassNotFoundException | LinkageError ignored) {}
        return null;
    }

    private static final Map<String, Boolean> PREFIX_GUI_MAP = new LinkedHashMap<>();

    private static final Map<String, ItemStack> ICON_CACHE = new ConcurrentHashMap<>();

    // Multi-block part → assembled machine item for icon and name resolution.
    // All parts (including the root) redirect to the item that has proper
    // item models and translations, since individual block items often lack
    // both.  Root-position resolution (resolveRootPos) uses class-name
    // reflection and is unaffected by this map.
    public static final Map<String, String> MULTI_PART_ROOT_MAP = Map.ofEntries(
            Map.entry("tacz:workbench_a", "tacz:gun_smith_table"),
            Map.entry("tacz:workbench_b", "tacz:gun_smith_table"),
            Map.entry("tacz:workbench_c", "tacz:gun_smith_table"),
            Map.entry("block.tacz.workbench_a", "tacz:gun_smith_table"),
            Map.entry("block.tacz.workbench_b", "tacz:gun_smith_table"),
            Map.entry("block.tacz.workbench_c", "tacz:gun_smith_table"),
            Map.entry("forbidden_arcanus:clibano_main_part", "forbidden_arcanus:clibano_core"),
            Map.entry("block.forbidden_arcanus.clibano_main_part", "forbidden_arcanus:clibano_core")
    );

    public static boolean supportsGuiByBlockKey(String blockKey) {
        if (blockKey == null || blockKey.isEmpty()) return false;
        int sep = blockKey.indexOf("||");
        String prefix = sep >= 0 ? blockKey.substring(0, sep) : "";
        return PREFIX_GUI_MAP.getOrDefault(prefix, prefix.isEmpty());
    }

    public static boolean supportsGuiByInfo(BindingInfo info) {
        if (!supportsGuiByBlockKey(info.blockKey())) return false;
        String regKey = info.blockRegKey();
        if (regKey != null) {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(regKey);
            if (rl != null) {
                var block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(rl);
                if (block != null) {
                    MachineBindingTarget target = findTarget(block);
                    if (target != null && !target.supportsGui) return false;
                }
            }
        }
        return true;
    }

    public static boolean supportsGuiAt(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (level == null || pos == null) return false;
        MachineBindingTarget target = findTarget(level.getBlockState(pos).getBlock());
        return target != null && target.supportsGui;
    }

    /** Client-side read-only target lookup shared by the binding HUD. */
    @Nullable
    public static BlockPos bindingTargetPos(net.minecraft.world.level.Level level, BlockPos clickedPos) {
        if (level == null || clickedPos == null) return null;
        Block block = level.getBlockState(clickedPos).getBlock();
        MachineBindingTarget target = findTarget(block);
        if (target == null) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            if (id == null || !RSIntegrationConfig.CUSTOM_GUI_MACHINE_MODS.get().contains(id.getNamespace())) {
                return null;
            }
        }
        return resolveRootPos(level, clickedPos, block, block.getClass().getName());
    }

    @Nullable
    private static MachineBindingTarget findTarget(Block block) {
        String className = block.getClass().getName();
        MachineBindingTarget exact = CLASS_TARGET_MAP.get(className);
        if (exact != null) return exact;
        for (MachineBindingTarget target : TARGETS) {
            if (target.matches(block, className)) return target;
        }
        return null;
    }

    private static String extractBlockId(net.minecraft.nbt.CompoundTag tag) {
        if (tag.contains("BlockId", net.minecraft.nbt.Tag.TAG_STRING)) {
            return tag.getString("BlockId");
        }
        if (tag.contains("BlockEntityTag")) {
            var beTag = tag.getCompound("BlockEntityTag");
            if (beTag.contains("BlockId", net.minecraft.nbt.Tag.TAG_STRING)) {
                return beTag.getString("BlockId");
            }
        }
        return null;
    }

    public static ItemStack resolveBlockIcon(@Nullable String blockRegKey, String blockKey,
                                              @Nullable ItemStack displayStack) {
        if (displayStack != null && !displayStack.isEmpty()) {
            ResourceLocation itemRl = ForgeRegistries.ITEMS.getKey(displayStack.getItem());

            if (itemRl != null && itemRl.getPath().equals("air")) {
                displayStack = null;
            } else if (displayStack.hasTag()) {
                // Resolve the best item for this multi-block part: walk
                // BlockId / BlockEntityTag → MULTI_PART_ROOT_MAP, then
                // build a new stack with the correct item + original NBT.
                // The NBT (BlockEntityTag) is required by BEWLR
                // (builtin/entity) to render the block model.
                String realBlockId = extractBlockId(displayStack.getTag());
                ResourceLocation iconItemId = null;
                if (realBlockId != null) {
                    String mapped = MULTI_PART_ROOT_MAP.get(realBlockId);
                    iconItemId = ResourceLocation.tryParse(mapped != null ? mapped : realBlockId);
                }
                if (iconItemId == null && blockRegKey != null) {
                    String mapped = MULTI_PART_ROOT_MAP.get(blockRegKey);
                    if (mapped != null) iconItemId = ResourceLocation.tryParse(mapped);
                }
                if (iconItemId == null && itemRl != null) {
                    String mapped = MULTI_PART_ROOT_MAP.get(itemRl.toString());
                    if (mapped != null) iconItemId = ResourceLocation.tryParse(mapped);
                }
                if (iconItemId != null) {
                    net.minecraft.world.item.Item iconItem = ForgeRegistries.ITEMS.getValue(iconItemId);
                    if (iconItem != null && iconItem != Items.AIR) {
                        ItemStack result = new ItemStack(iconItem);
                        result.setTag(displayStack.getTag().copy());
                        return result;
                    }
                }
                // No redirection — return displayStack as-is.  It carries
                // BlockEntityTag so BEWLR can still render it.
                return displayStack.copy();
            } else {
                // No NBT → use displayStack directly if valid
                if (itemRl != null && !itemRl.getPath().equals("air")) {
                    return displayStack.copy();
                }
                displayStack = null;
            }
        }

        String cacheKey = (blockRegKey != null ? blockRegKey : "") + "\0" + (blockKey != null ? blockKey : "");
        ItemStack cached = ICON_CACHE.get(cacheKey);
        if (cached != null) {
            return cached.copy();
        }

        ItemStack result;
        if (blockRegKey != null) {
            // Check MULTI_PART_ROOT_MAP FIRST — dummy blocks have registry
            // entries but no textures, so direct lookup would return a
            // purple-black missingno.
            String rootKey = MULTI_PART_ROOT_MAP.get(blockRegKey);
            if (rootKey != null) {
                result = getValidItemStack(ResourceLocation.tryParse(rootKey));
                if (result != null) {
                    ICON_CACHE.put(cacheKey, result.copy());
                    return result;
                }
            } else {
                result = getValidItemStack(ResourceLocation.tryParse(blockRegKey));
                if (result != null) {
                    ICON_CACHE.put(cacheKey, result.copy());
                    return result;
                }
            }
        }

        if (blockKey == null || blockKey.isEmpty()) {
            result = new ItemStack(Items.CRAFTING_TABLE);
            ICON_CACHE.put(cacheKey, result.copy());
            return result;
        }
        int sep = blockKey.indexOf("||");
        String descId = sep >= 0 ? blockKey.substring(sep + 2) : blockKey;

        String rootKeyFromDesc = MULTI_PART_ROOT_MAP.get(descId);
        if (rootKeyFromDesc != null) {
            result = getValidItemStack(ResourceLocation.tryParse(rootKeyFromDesc));
            if (result != null) { ICON_CACHE.put(cacheKey, result.copy()); return result; }
        }

        if (descId.startsWith("block.")) {
            String rest = descId.substring(6);
            int dot = rest.indexOf('.');
            if (dot > 0) {
                String namespace = rest.substring(0, dot);
                String registryKey = namespace + ":" + rest.substring(dot + 1);
                result = getValidItemStack(ResourceLocation.tryParse(registryKey));
                if (result != null) { ICON_CACHE.put(cacheKey, result.copy()); return result; }

                for (var block : ForgeRegistries.BLOCKS) {
                    var key = ForgeRegistries.BLOCKS.getKey(block);
                    if (key != null && namespace.equals(key.getNamespace())
                            && descId.equals(block.getDescriptionId())) {
                        var item = block.asItem();
                        if (item != Items.AIR) {
                            result = new ItemStack(item);
                            ICON_CACHE.put(cacheKey, result.copy());
                            return result;
                        }
                    }
                }
            }
        }

        result = new ItemStack(Items.CRAFTING_TABLE);
        ICON_CACHE.put(cacheKey, result.copy());
        return result;
    }

    private static ItemStack getValidItemStack(ResourceLocation rl) {
        if (rl == null) return null;
        var item = ForgeRegistries.ITEMS.getValue(rl);
        if (item != null && item != Items.AIR) return new ItemStack(item);
        var block = ForgeRegistries.BLOCKS.getValue(rl);
        if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
            var blockItem = block.asItem();
            if (blockItem != Items.AIR) return new ItemStack(blockItem);
        }
        return null;
    }

    static void sendBindingRefresh(ServerPlayer player) {
        // The binding handler is shared by RS and BD-only installations.  The
        // side-panel bridge has an optional RS API type in its method table,
        // so never link it when Refined Storage is absent.
        if (!ModList.get().isLoaded(ModIds.REFINED_STORAGE)) return;
        try {
            RSSidePanelNetworkHandler.sendBindingSync(player);
        } catch (Exception | LinkageError e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Bind] Failed to send binding sync", e);
        }
    }

    static BlockPos resolveRootPos(Level level, BlockPos pos, Block block, String className) {
        // CrockPot's birdcage is a two-block-tall machine. Its block entity only
        // exists in the lower half, so normalize upper-half clicks before binding.
        if (className.equals("com.sihenzhang.crockpot.block.BirdcageBlock")) {
            for (var property : level.getBlockState(pos).getProperties()) {
                if ("half".equals(property.getName())
                        && "upper".equals(String.valueOf(level.getBlockState(pos).getValue(property)))) {
                    return pos.below();
                }
            }
        }
        if (net.minecraftforge.fml.ModList.get().isLoaded(ModIds.PMMO)) {
            BlockPos pmmoRoot = com.huanghuang.rsintegration.mods.pmmo.PmmoSalvageStructure
                    .resolveRoot(level, pos);
            if (pmmoRoot != null) return pmmoRoot;
        }
        // Forbidden & Arcanus Clibano: visible shell parts resolve the hidden
        // main-part POI through the mod's own public structure contract.
        if (className.equals("com.stal111.forbidden_arcanus.common.block.ClibanoCenterBlock")
                || className.equals("com.stal111.forbidden_arcanus.common.block.ClibanoCornerBlock")
                || className.equals("com.stal111.forbidden_arcanus.common.block.ClibanoHorizontalSideBlock")
                || className.equals("com.stal111.forbidden_arcanus.common.block.ClibanoVerticalSideBlock")) {
            try {
                java.lang.reflect.Method findMainPos = block.getClass()
                        .getMethod("findMainPos", Level.class, BlockPos.class);
                Object resolved = findMainPos.invoke(block, level, pos);
                if (resolved instanceof Optional<?> optional
                        && optional.orElse(null) instanceof BlockPos mainPos) {
                    BlockEntity main = level.getBlockEntity(mainPos);
                    if (main != null && !main.isRemoved()
                            && main.getClass().getName().equals(
                            "com.stal111.forbidden_arcanus.common.block.entity.clibano.ClibanoMainBlockEntity")) {
                        return mainPos;
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Bind] Clibano main-pos resolution failed at {}", pos, e);
            }
            // ClibanoPart.findMainPos() is server-only: its implementation
            // returns Optional.empty() for an ordinary client Level. The HUD
            // still needs the canonical root in order to compare the looked-at
            // shell block with the root position stored by the server binding.
            BlockPos nearbyMain = findNearbyClibanoMainPart(level, pos);
            if (nearbyMain != null) return nearbyMain;
            return pos;
        }

        // TACZ: 1×2 gun workbench — getRootPos is on the block, not the BE
        if (className.contains("GunSmithTableBlock")) {
            try {
                java.lang.reflect.Method getRootPos = block.getClass()
                        .getMethod("getRootPos", BlockPos.class, net.minecraft.world.level.block.state.BlockState.class);
                BlockPos root = (BlockPos) getRootPos.invoke(block, pos, level.getBlockState(pos));
                if (root != null && !root.equals(pos)) {
                    return root;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Bind] TACZ root-pos resolution failed", e);
            }
            return pos;
        }

        // TLM: 3×8×6 altar — every block is BlockAltar with a BE.
        // Compute the canonical centre so all clicks on the same altar
        // resolve to one binding, preventing duplicates.
        // Malum Spirit Crucible: the visible component occupies the block
        // above the core. Both halves must resolve to one binding position.
        ResourceLocation blockKey = ForgeRegistries.BLOCKS.getKey(block);
        if (blockKey != null && blockKey.toString().equals("ars_nouveau:scribes_table")) {
            BlockEntity clickedTile = level.getBlockEntity(pos);
            if (clickedTile != null) {
                Object logic = com.huanghuang.rsintegration.util.Reflect
                        .invoke(clickedTile, "getLogicTile").orElse(null);
                if (logic instanceof BlockEntity logicTile && !logicTile.isRemoved()) {
                    return logicTile.getBlockPos();
                }
            }
        }
        if (blockKey != null && blockKey.toString().equals("malum:spirit_crucible_component")) {
            BlockPos corePos = pos.below();
            ResourceLocation coreKey = ForgeRegistries.BLOCKS.getKey(
                    level.getBlockState(corePos).getBlock());
            if (coreKey != null && coreKey.toString().equals("malum:spirit_crucible")) {
                return corePos;
            }
        }

        if (className.equals("com.github.tartaricacid.touhoulittlemaid.block.BlockAltar")) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                BlockPos centre = resolveTlmAltarCentre(be, pos);
                if (centre != null) return centre;
            }
        }

        if (className.equals("io.github.poisonsheep.wishingfountain.block.WFBlock")) {
            BlockPos core = com.huanghuang.rsintegration.mods.wishingfountain
                    .WishingFountainStructure.resolveCorePosition(level, pos);
            if (core != null) return core;
        }

        // Youkai's Homecoming: Steamer multiblock (pot + racks + lid).
        // Blocks use DelegateBlock / DelegateBlockImpl / DelegateEntityBlockImpl;
        // the pot is always at the bottom.
        if (isL2ModularBlock(block)) {
            ResourceLocation regKey = ForgeRegistries.BLOCKS.getKey(block);
            if (regKey != null) {
                String namespace = regKey.getNamespace();
                if (YoukaiRegistryIds.isSupportedNamespace(namespace)
                        && ("steamer_rack".equals(regKey.getPath())
                        || "steamer_lid".equals(regKey.getPath()))) {
                    // Walk downward to find the steamer pot
                    BlockPos.MutableBlockPos cursor = pos.mutable();
                    for (int i = 0; i < 16; i++) {
                        cursor.move(net.minecraft.core.Direction.DOWN);
                        BlockState below = level.getBlockState(cursor);
                        ResourceLocation belowKey = ForgeRegistries.BLOCKS.getKey(below.getBlock());
                        if (belowKey != null
                                && namespace.equals(belowKey.getNamespace())
                                && "steamer_pot".equals(belowKey.getPath())) {
                            return cursor.immutable();
                        }
                    }
                }
            }
        }

        return pos;
    }

    @Nullable
    static BlockPos findNearbyClibanoMainPart(Level level, BlockPos origin) {
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(
                origin.offset(-2, -2, -2), origin.offset(2, 2, 2))) {
            BlockEntity blockEntity = level.getBlockEntity(candidate);
            if (blockEntity == null || blockEntity.isRemoved()
                    || !blockEntity.getClass().getName().equals(
                    "com.stal111.forbidden_arcanus.common.block.entity.clibano.ClibanoMainBlockEntity")) {
                continue;
            }
            double distance = candidate.distSqr(origin);
            if (distance < nearestDistance) {
                nearest = candidate.immutable();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static boolean isL2ModularBlock(Block block) {
        Class<?> clazz = block.getClass();
        while (clazz != null) {
            String name = clazz.getName();
            if (name.equals("dev.xkmc.l2modularblock.DelegateBlock")
                    || name.equals("dev.xkmc.l2modularblock.DelegateBlockImpl")
                    || name.equals("dev.xkmc.l2modularblock.DelegateEntityBlockImpl")) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    /**
     * Returns a canonical position for a TLM altar multiblock.
     * Every BlockAltar in the structure carries the same {@code blockPosList},
     * so we use the first entry — it is always inside the altar and consistent
     * across all clicks on the same multiblock.
     */
    private static BlockPos resolveTlmAltarCentre(BlockEntity be, BlockPos pos) {
        if (be.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            TlmAltarStructure.Resolved resolved = TlmAltarStructure.resolve(serverLevel, be);
            if (resolved != null && !resolved.mainPos().equals(pos)) return resolved.mainPos();
        }

        // The client cannot run the server-side render/main resolver. Use the
        // structure's stable first position there; the HUD also compares the
        // clicked target against every stored binding entry, including server
        // bindings that use the render/main position.
        try {
            Object data = be.getClass().getMethod("getBlockPosList").invoke(be);
            if (data == null) return null;
            Object raw = data.getClass().getMethod("getData").invoke(data);
            if (!(raw instanceof List<?> positions) || positions.isEmpty()) return null;
            for (Object value : positions) {
                if (value instanceof BlockPos candidatePos) return candidatePos.immutable();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            RSIntegrationMod.LOGGER.debug("[RSI-Bind] TLM structure root-pos resolution failed at {}", pos);
        }
        return null;
    }
}
