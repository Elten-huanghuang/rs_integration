package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BindingEventHandler {

    private static final List<MachineBindingTarget> TARGETS = new ArrayList<>();
    private static final Object BINDING_LOCK = new Object();

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
        if (!player.isShiftKeyDown()) return;

        Block block = event.getLevel().getBlockState(event.getPos()).getBlock();
        String className = block.getClass().getName();

        MachineBindingTarget matched = null;
        for (MachineBindingTarget target : TARGETS) {
            if (!target.configFlag.get()) continue;
            if (!target.modId.equals("minecraft") && !ModList.get().isLoaded(target.modId)) continue;
            if (!target.matches(block, className)) continue;
            matched = target;
            break;
        }

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

        BlockPos pos = event.getPos();
        BlockPos bindingPos = resolveRootPos(event.getLevel(), pos, block, className);

        // If root resolution moved us to the master block, recompute identity
        // from the root position so blockKey/blockRegKey/displayStack reflect
        // the master, not the slave half that was clicked.
        if (!bindingPos.equals(pos)) {
            BlockState rootState = event.getLevel().getBlockState(bindingPos);
            Block rootBlock = rootState.getBlock();
            block = rootBlock;
            className = rootBlock.getClass().getName();
            pos = bindingPos;
        }

        ResourceLocation dim = event.getLevel().dimension().location();
        BlockState state = event.getLevel().getBlockState(pos);
        String blockKey = matched.blockKey(block);
        String blockRegKey = ForgeRegistries.BLOCKS.getKey(block).toString();

        // Extract displayStack BEFORE resolveBlockName so the NBT-bearing
        // stack (with BlockEntityTag/BlockId) is available for name resolution.
        // Previously this was after the call, so displayStack was always null.
        ItemStack displayStack = state.getBlock().getCloneItemStack(event.getLevel(), pos, state);
        net.minecraft.world.level.block.entity.BlockEntity be = event.getLevel().getBlockEntity(pos);
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
            if (BindingStorage.hasBinding(held, dim, bindingPos)) {
                BindingStorage.removeBinding(held, dim, bindingPos);
                AltarBindingRegistry.unbind(event.getLevel().dimension(), bindingPos, AltarBinding.RS_NETWORK);
                player.displayClientMessage(
                        Component.translatable("gui.rs_integration.altar.unbound", blockName),
                        true);
                sendBindingRefresh(player);
            } else {
                Optional<AltarBinding> binding = hook.get().createBinding(held);
                if (binding.isPresent()) {
                    AltarBindingRegistry.bind(event.getLevel().dimension(), bindingPos, binding.get());
                    BindingStorage.addBinding(held, dim, bindingPos, blockKey, blockRegKey, displayStack);
                    player.displayClientMessage(
                            Component.translatable("gui.rs_integration.altar.bound",
                                    blockName, binding.get().displayName()),
                            true);
                    sendBindingRefresh(player);
                }
            }
        }
        event.setCanceled(true);
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

    @Nullable
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

    public static final Map<String, MachineBindingTarget> CLASS_TARGET_MAP = new LinkedHashMap<>();

    @Nullable
    public static MachineBindingTarget findTargetByClass(String className) {
        return CLASS_TARGET_MAP.get(className);
    }

    private static final Map<String, Boolean> PREFIX_GUI_MAP = new LinkedHashMap<>();

    private static final Map<String, ItemStack> ICON_CACHE = new ConcurrentHashMap<>();

    // Multi-block part → assembled machine item for icon and name resolution.
    // All parts (including the root) redirect to the item that has proper
    // item models and translations, since individual block items often lack
    // both.  Root-position resolution (resolveRootPos) uses class-name
    // reflection and is unaffected by this map.
    public static final Map<String, String> MULTI_PART_ROOT_MAP = Map.of(
            "tacz:workbench_a", "tacz:gun_smith_table",
            "tacz:workbench_b", "tacz:gun_smith_table",
            "tacz:workbench_c", "tacz:gun_smith_table",
            "block.tacz.workbench_a", "tacz:gun_smith_table",
            "block.tacz.workbench_b", "tacz:gun_smith_table",
            "block.tacz.workbench_c", "tacz:gun_smith_table"
    );

    public static boolean supportsGuiByBlockKey(String blockKey) {
        if (blockKey == null || blockKey.isEmpty()) return false;
        int sep = blockKey.indexOf("||");
        String prefix = sep >= 0 ? blockKey.substring(0, sep) : "";
        return PREFIX_GUI_MAP.getOrDefault(prefix, prefix.isEmpty());
    }

    public static boolean supportsGuiByInfo(com.huanghuang.rsintegration.sidepanel.data.BindingInfo info) {
        if (!supportsGuiByBlockKey(info.blockKey())) return false;
        String regKey = info.blockRegKey();
        if (regKey != null) {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(regKey);
            if (rl != null) {
                var block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(rl);
                if (block != null) {
                    MachineBindingTarget target = CLASS_TARGET_MAP.get(block.getClass().getName());
                    if (target != null && !target.supportsGui) return false;
                }
            }
        }
        return true;
    }

    public static boolean supportsGuiAt(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (level == null || pos == null) return false;
        String className = level.getBlockState(pos).getBlock().getClass().getName();
        MachineBindingTarget target = CLASS_TARGET_MAP.get(className);
        return target != null && target.supportsGui;
    }

    @Nullable
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

    @Nullable
    private static ItemStack getValidItemStack(@Nullable ResourceLocation rl) {
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

    private static void sendBindingRefresh(ServerPlayer player) {
        try {
            com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler.sendBindingSync(player);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Bind] Failed to send binding sync", e);
        }
    }

    private static BlockPos resolveRootPos(Level level, BlockPos pos, Block block, String className) {
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
        if (className.equals("com.github.tartaricacid.touhoulittlemaid.block.BlockAltar")) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                BlockPos centre = resolveTlmAltarCentre(be, pos);
                if (centre != null) return centre;
            }
        }

        // Youkai's Homecoming: Steamer multiblock (pot + racks + lid).
        // Blocks use DelegateBlock / DelegateBlockImpl / DelegateEntityBlockImpl;
        // the pot is always at the bottom.
        if (isL2ModularBlock(block)) {
            ResourceLocation regKey = ForgeRegistries.BLOCKS.getKey(block);
            if (regKey != null) {
                String rk = regKey.toString();
                if (rk.equals("youkaishomecoming:steamer_rack")
                        || rk.equals("youkaishomecoming:steamer_lid")) {
                    // Walk downward to find the steamer pot
                    BlockPos.MutableBlockPos cursor = pos.mutable();
                    for (int i = 0; i < 16; i++) {
                        cursor.move(net.minecraft.core.Direction.DOWN);
                        BlockState below = level.getBlockState(cursor);
                        ResourceLocation belowKey = ForgeRegistries.BLOCKS.getKey(below.getBlock());
                        if (belowKey != null
                                && belowKey.toString().equals("youkaishomecoming:steamer_pot")) {
                            return cursor.immutable();
                        }
                    }
                }
            }
        }

        return pos;
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
    @Nullable
    private static BlockPos resolveTlmAltarCentre(BlockEntity be, BlockPos pos) {
        try {
            java.lang.reflect.Method getBlockPosList = be.getClass().getMethod("getBlockPosList");
            Object posListData = getBlockPosList.invoke(be);
            if (posListData == null) return null;

            java.lang.reflect.Method getData = posListData.getClass().getMethod("getData");
            Object data = getData.invoke(posListData);
            if (!(data instanceof List<?> list) || list.isEmpty()) return null;

            BlockPos canonical = (BlockPos) list.get(0);
            if (!canonical.equals(pos)) return canonical;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Bind] TLM altar centre resolution failed", e);
        }
        return null;
    }
}
