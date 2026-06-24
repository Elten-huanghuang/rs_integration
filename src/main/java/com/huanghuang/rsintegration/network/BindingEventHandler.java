package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            if (!ModList.get().isLoaded(target.modId)) continue;
            if (!target.matches(block, className)) continue;
            matched = target;
            break;
        }
        if (matched == null) return;

        ItemStack held = player.getItemInHand(event.getHand());
        Optional<IBindingHook> hook = AltarBindingRegistry.findHook(held);
        if (hook.isEmpty()) return;

        BlockPos pos = event.getPos();
        ResourceLocation dim = event.getLevel().dimension().location();
        String blockKey = matched.blockKey(block);

        synchronized (BINDING_LOCK) {
        if (BindingStorage.hasBinding(held, dim, pos)) {
            BindingStorage.removeBinding(held, dim, pos);
            AltarBindingRegistry.unbind(event.getLevel().dimension(), pos, AltarBinding.RS_NETWORK);
            player.displayClientMessage(
                    Component.translatable("gui.rs_integration.altar.unbound",
                            Component.translatable(block.getDescriptionId())),
                    true);
        } else {
            Optional<AltarBinding> binding = hook.get().createBinding(held);
            if (binding.isPresent()) {
                AltarBindingRegistry.bind(event.getLevel().dimension(), pos, binding.get());
                BindingStorage.addBinding(held, dim, pos, blockKey);
                player.displayClientMessage(
                        Component.translatable("gui.rs_integration.altar.bound",
                                Component.translatable(blockKey), binding.get().displayName()),
                        true);
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
        private final String blockKeyPrefix;

        public MachineBindingTarget(String modId, ModType modType, ForgeConfigSpec.BooleanValue configFlag,
                                     List<String> blockClassNames, @Nullable String blockKeyPrefix) {
            this.modId = modId;
            this.modType = modType;
            this.configFlag = configFlag;
            this.blockClassNames = blockClassNames;
            this.blockKeyPrefix = blockKeyPrefix;
        }

        public ModType modType() { return modType; }

        boolean matches(Block block, String className) {
            for (String name : blockClassNames) {
                if (className.equals(name)) return true;
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

    // ── class name → MachineBindingTarget lookup ───────────────

    private static final Map<String, MachineBindingTarget> CLASS_TARGET_MAP = new LinkedHashMap<>();

    @Nullable
    public static MachineBindingTarget findTargetByClass(String className) {
        return CLASS_TARGET_MAP.get(className);
    }
}
