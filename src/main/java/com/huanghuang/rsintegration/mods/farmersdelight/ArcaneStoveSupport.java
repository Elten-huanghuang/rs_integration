package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Optional reflection bridge for irons_spells_delight's Arcane Stove. */
final class ArcaneStoveSupport {
    interface FuelAccess {
        ItemStack extract(ItemStack template);
        void refund(ItemStack stack);
    }
    private static final String BLOCK_ENTITY_CLASS =
            "com.inolia_zaicek.irons_spells_delight.blocks.ArcaneStoveBlockEntity";
    private static final String COOKING_POT_BLOCK_ENTITY_CLASS =
            "com.inolia_zaicek.irons_spells_delight.blocks.ArcaneCookingPotBlockEntity";
    private static final String BLOCK_CLASS =
            "com.inolia_zaicek.irons_spells_delight.blocks.ArcaneStoveBlock";
    private static final ResourceLocation ARCANE_ESSENCE =
            new ResourceLocation("irons_spellbooks", "arcane_essence");
    private static final ResourceLocation CINDER_ESSENCE =
            new ResourceLocation("irons_spellbooks", "cinder_essence");

    private static volatile Class<?> blockEntityClass;
    private static volatile Class<?> cookingPotBlockEntityClass;
    private static volatile Method useMethod;
    private static volatile boolean probed;

    private ArcaneStoveSupport() {}

    static boolean isArcaneStove(@Nullable BlockEntity blockEntity) {
        if (blockEntity == null) return false;
        probe();
        return blockEntityClass != null && blockEntityClass.isInstance(blockEntity);
    }

    static boolean isArcaneCookingPot(@Nullable BlockEntity blockEntity) {
        if (blockEntity == null) return false;
        probe();
        return cookingPotBlockEntityClass != null
                && cookingPotBlockEntityClass.isInstance(blockEntity);
    }

    @Nullable
    static ItemStackHandler items(BlockEntity blockEntity) {
        if (!isArcaneStove(blockEntity)) return null;
        try {
            Object value = blockEntity.getClass().getMethod("getItems").invoke(blockEntity);
            return value instanceof ItemStackHandler handler ? handler : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            RSIntegrationMod.LOGGER.debug("[RSI-ArcaneStove] getItems probe failed", e);
            return null;
        }
    }

    static int remainingBurnTicks(BlockEntity blockEntity) {
        return intMethod(blockEntity, "getRemainingBurnTicks", 0);
    }

    static int maxBurnTicks(BlockEntity blockEntity) {
        return staticIntMethod(blockEntity, "getMaxBurnTicks", 0);
    }

    static boolean placeFood(BlockEntity blockEntity, Player player, ItemStack input, int cookTicks) {
        try {
            Method method = blockEntity.getClass().getMethod("placeFood",
                    net.minecraft.world.entity.Entity.class, ItemStack.class, int.class);
            return Boolean.TRUE.equals(method.invoke(blockEntity, player, input, cookTicks));
        } catch (ReflectiveOperationException | RuntimeException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-ArcaneStove] placeFood probe failed", e);
            return false;
        }
    }

    static boolean permanent(BlockEntity blockEntity) {
        return booleanMethod(blockEntity, "isPermanent", false);
    }

    /**
     * Ensures enough burn time for one campfire recipe. Mana is preferred so
     * normal recursive orders do not consume stored essence unnecessarily.
     */
    static boolean ensureBurning(ServerPlayer player, BlockEntity blockEntity,
                                 int requiredTicks, FuelAccess fuelAccess) {
        if (permanent(blockEntity) || remainingBurnTicks(blockEntity) >= requiredTicks) {
            return true;
        }

        boolean manaAccepted = invokeUse(
                blockEntity, player, InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        if (manaAccepted
                && (permanent(blockEntity) || remainingBurnTicks(blockEntity) >= requiredTicks)) {
            return true;
        }

        // A successful native interaction records a same-tick cooldown. If it
        // added mana burn time but custom config still makes that too short for
        // this recipe, do not treat the spent mana as unused or try another fuel.
        if (manaAccepted) {
            sendFuelTooShort(player, blockEntity, requiredTicks);
            return false;
        }

        ItemStack arcane = essence(fuelAccess, ARCANE_ESSENCE);
        if (!arcane.isEmpty()) {
            boolean accepted = invokeUse(blockEntity, player, InteractionHand.MAIN_HAND, arcane);
            if (accepted && (permanent(blockEntity) || remainingBurnTicks(blockEntity) >= requiredTicks)) {
                return true;
            }
            if (accepted) {
                sendFuelTooShort(player, blockEntity, requiredTicks);
                return false;
            }
            fuelAccess.refund(arcane);
        }

        ItemStack cinder = essence(fuelAccess, CINDER_ESSENCE);
        if (!cinder.isEmpty()) {
            boolean accepted = invokeUse(blockEntity, player, InteractionHand.MAIN_HAND, cinder);
            if (accepted && permanent(blockEntity)) return true;
            if (!accepted) fuelAccess.refund(cinder);
        }

        player.sendSystemMessage(Component.translatable(
                "rsi.farmersdelight.arcane_stove.fuel_missing"));
        return false;
    }

    private static void sendFuelTooShort(ServerPlayer player, BlockEntity blockEntity,
                                         int requiredTicks) {
        player.sendSystemMessage(Component.translatable(
                "rsi.farmersdelight.arcane_stove.fuel_too_short",
                Math.max(1, (requiredTicks + 19) / 20),
                Math.max(0, remainingBurnTicks(blockEntity) / 20)));
    }

    @Nullable
    static ItemStack essenceItem(ResourceLocation id) {
        var item = ForgeRegistries.ITEMS.getValue(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static ItemStack essence(FuelAccess fuelAccess, ResourceLocation id) {
        ItemStack template = essenceItem(id);
        return template.isEmpty()
                ? ItemStack.EMPTY
                : fuelAccess.extract(template);
    }

    /** Calls the mod's native interaction while preserving the player's hand. */
    private static boolean invokeUse(BlockEntity blockEntity, ServerPlayer player,
                                      InteractionHand hand, ItemStack temporary) {
        probe();
        if (useMethod == null) return false;
        BlockState state = blockEntity.getBlockState();
        Block block = state.getBlock();
        ItemStack original = player.getItemInHand(hand);
        int before = remainingBurnTicks(blockEntity);
        boolean wasPermanent = permanent(blockEntity);
        player.setItemInHand(hand, temporary.copy());
        try {
            Object result = useMethod.invoke(block, state, blockEntity.getLevel(),
                    blockEntity.getBlockPos(), player, hand,
                    new BlockHitResult(Vec3.atCenterOf(blockEntity.getBlockPos()),
                            Direction.UP, blockEntity.getBlockPos(), false));
            if (!(result instanceof InteractionResult)) return false;
            return permanent(blockEntity) != wasPermanent || remainingBurnTicks(blockEntity) > before;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-ArcaneStove] native use invocation failed", e);
            return false;
        } finally {
            player.setItemInHand(hand, original);
        }
    }

    private static void probe() {
        if (probed) return;
        synchronized (ArcaneStoveSupport.class) {
            if (probed) return;
            probed = true;
            try {
                blockEntityClass = Class.forName(BLOCK_ENTITY_CLASS);
                cookingPotBlockEntityClass = Class.forName(COOKING_POT_BLOCK_ENTITY_CLASS);
                Class<?> blockClass = Class.forName(BLOCK_CLASS);
                for (Method method : blockClass.getMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (method.getReturnType() == InteractionResult.class
                            && parameters.length == 6
                            && BlockState.class.isAssignableFrom(parameters[0])
                            && Level.class.isAssignableFrom(parameters[1])
                            && BlockPos.class.isAssignableFrom(parameters[2])
                            && Player.class.isAssignableFrom(parameters[3])
                            && InteractionHand.class.isAssignableFrom(parameters[4])
                            && BlockHitResult.class.isAssignableFrom(parameters[5])) {
                        method.setAccessible(true);
                        useMethod = method;
                        break;
                    }
                }
            } catch (ClassNotFoundException | LinkageError e) {
                blockEntityClass = null;
                cookingPotBlockEntityClass = null;
                useMethod = null;
            }
        }
    }

    private static int intMethod(BlockEntity blockEntity, String name, int fallback) {
        try {
            Object value = blockEntity.getClass().getMethod(name).invoke(blockEntity);
            return value instanceof Number number ? number.intValue() : fallback;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }

    private static int staticIntMethod(BlockEntity blockEntity, String name, int fallback) {
        try {
            Method method = blockEntity.getClass().getMethod(name);
            Object value = method.invoke(null);
            return value instanceof Number number ? number.intValue() : fallback;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }

    private static boolean booleanMethod(BlockEntity blockEntity, String name, boolean fallback) {
        try {
            Object value = blockEntity.getClass().getMethod(name).invoke(blockEntity);
            return value instanceof Boolean result ? result : fallback;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }
}
