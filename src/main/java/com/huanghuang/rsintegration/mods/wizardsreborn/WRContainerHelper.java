package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;

/**
 * Shared container-reflection helpers for WR machine blocks.
 * Used by both {@link WRBatchDelegate} and {@link WRWandCraftPacket}.
 */
public final class WRContainerHelper {

    private WRContainerHelper() {}

    // ── Forge capability ─────────────────────────────────────────

    @Nullable
    public static IItemHandler getForgeItemHandler(Object be) {
        if (be instanceof BlockEntity blockEntity) {
            return blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
        }
        return null;
    }

    // ── SimpleContainer field walk ────────────────────────────────

    @Nullable
    public static net.minecraft.world.SimpleContainer getLiveSimpleContainer(Object be) {
        Class<?> clazz = be.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (net.minecraft.world.SimpleContainer.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        return (net.minecraft.world.SimpleContainer) field.get(be);
                    } catch (Exception ignored) {
                        RSIntegrationMod.LOGGER.debug("[RSI-WR] getLiveSimpleContainer field access failed: {}", ignored.toString());
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    @Nullable
    public static net.minecraft.world.SimpleContainer getSimpleContainer(Object be) {
        Class<?> clazz = be.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                java.lang.reflect.Method m = clazz.getDeclaredMethod("createItemHandler");
                m.setAccessible(true);
                return (net.minecraft.world.SimpleContainer) m.invoke(be);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-WR] Container reflection failed: {}", e.toString());
                return null;
            }
        }
        return null;
    }

    // ── Container size ────────────────────────────────────────────

    /** Returns the slot count of a WR block entity, or -1 if unresolvable. */
    public static int getContainerSize(Object be) {
        Class<?> bc = be.getClass();
        String beName = bc.getName();
        java.lang.reflect.Method m;

        // 1. WR BlockSimpleInventory.inventorySize() (no "get" prefix)
        m = Reflect.findMethod(bc, "inventorySize", new Class<?>[0]);
        if (m != null) { try { return (int) m.invoke(be); } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-WR] inventorySize() invoke failed for {}: {}", beName, e.toString());
        }}
        // 2. Alternative: getInventorySize()
        m = Reflect.findMethod(bc, "getInventorySize", new Class<?>[0]);
        if (m != null) { try { return (int) m.invoke(be); } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-WR] getInventorySize() invoke failed for {}: {}", beName, e.toString());
        }}
        // 3. Vanilla: getContainerSize() (BaseContainerBlockEntity)
        m = Reflect.findMethod(bc, "getContainerSize", new Class<?>[0]);
        if (m != null) { try { return (int) m.invoke(be); } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-WR] getContainerSize() invoke failed for {}: {}", beName, e.toString());
        }}
        // 4. getItemHandler() → IItemHandler.getSlots() or Container.getContainerSize()
        m = Reflect.findMethod(bc, "getItemHandler", new Class<?>[0]);
        if (m != null) {
            try {
                Object h = m.invoke(be);
                if (h != null) {
                    java.lang.reflect.Method gm = Reflect.findMethod(h.getClass(), "getSlots", new Class<?>[0]);
                    if (gm != null) return (int) gm.invoke(h);
                    gm = Reflect.findMethod(h.getClass(), "getContainerSize", new Class<?>[0]);
                    if (gm != null) return (int) gm.invoke(h);
                    gm = Reflect.findMethod(h.getClass(), "m_6643_", new Class<?>[0]);
                    if (gm != null) return (int) gm.invoke(h);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-WR] getItemHandler() invoke failed for {}: {}", beName, e.toString());
            }
        }
        // 5. Forge capabilities
        IItemHandler cap = getForgeItemHandler(be);
        if (cap != null) return cap.getSlots();
        // 6. Live SimpleContainer field on BE
        net.minecraft.world.SimpleContainer live = getLiveSimpleContainer(be);
        if (live != null) return live.getContainerSize();
        // 7. SRG name
        m = Reflect.findMethod(bc, "m_6643_", new Class<?>[0]);
        if (m != null) { try { return (int) m.invoke(be); } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-WR] m_6643_() invoke failed for {}: {}", beName, e.toString());
        }}
        // 8. createItemHandler() factory (last resort)
        net.minecraft.world.SimpleContainer sc = getSimpleContainer(be);
        if (sc != null) return sc.getContainerSize();

        RSIntegrationMod.LOGGER.warn("[RSI-WR] getContainerSize failed for {} — superclass={}, fields: {}",
                beName, bc.getSuperclass() != null ? bc.getSuperclass().getName() : "<none>",
                getFieldTypes(bc));
        return -1;
    }

    // ── Container item access ─────────────────────────────────────

    public static ItemStack getContainerItem(Object be, int slot) {
        Class<?> bc = be.getClass();
        java.lang.reflect.Method m = Reflect.findMethod(bc, "getItem", new Class<?>[]{int.class});
        if (m != null) { try { return (ItemStack) m.invoke(be, slot); } catch (Exception ignored) {
            RSIntegrationMod.LOGGER.debug("[RSI-WR] getItem S1 failed: {}", ignored.toString());
        }}
        m = Reflect.findMethod(bc, "getItemHandler", new Class<?>[0]);
        if (m != null) {
            try {
                Object h = m.invoke(be);
                if (h != null) {
                    java.lang.reflect.Method gm = Reflect.findMethod(h.getClass(), "getStackInSlot", new Class<?>[]{int.class});
                    if (gm != null) return (ItemStack) gm.invoke(h, slot);
                    gm = Reflect.findMethod(h.getClass(), "getItem", new Class<?>[]{int.class});
                    if (gm != null) return (ItemStack) gm.invoke(h, slot);
                    gm = Reflect.findMethod(h.getClass(), "m_8020_", new Class<?>[]{int.class});
                    if (gm != null) return (ItemStack) gm.invoke(h, slot);
                }
            } catch (Exception ignored) {
                RSIntegrationMod.LOGGER.debug("[RSI-WR] getItem handler probe failed: {}", ignored.toString());
            }
        }
        IItemHandler cap = getForgeItemHandler(be);
        if (cap != null) return cap.getStackInSlot(slot);
        net.minecraft.world.SimpleContainer live = getLiveSimpleContainer(be);
        if (live != null) return live.getItem(slot);
        m = Reflect.findMethod(bc, "m_8020_", new Class<?>[]{int.class});
        if (m != null) { try { return (ItemStack) m.invoke(be, slot); } catch (Exception ignored) {
            RSIntegrationMod.LOGGER.debug("[RSI-WR] getItem SRG failed: {}", ignored.toString());
        }}
        net.minecraft.world.SimpleContainer sc = getSimpleContainer(be);
        if (sc != null) return sc.getItem(slot);
        return ItemStack.EMPTY;
    }

    public static void setContainerItem(Object be, int slot, ItemStack stack) {
        Class<?> bc = be.getClass();
        java.lang.reflect.Method m = Reflect.findMethod(bc, "setItem", new Class<?>[]{int.class, ItemStack.class});
        if (m != null) { try { m.invoke(be, slot, stack); return; } catch (Exception ignored) {
            RSIntegrationMod.LOGGER.debug("[RSI-WR] setItem S1 failed: {}", ignored.toString());
        }}
        m = Reflect.findMethod(bc, "getItemHandler", new Class<?>[0]);
        if (m != null) {
            try {
                Object h = m.invoke(be);
                if (h != null) {
                    java.lang.reflect.Method sm = Reflect.findMethod(h.getClass(), "setStackInSlot", new Class<?>[]{int.class, ItemStack.class});
                    if (sm != null) { sm.invoke(h, slot, stack); return; }
                    sm = Reflect.findMethod(h.getClass(), "setItem", new Class<?>[]{int.class, ItemStack.class});
                    if (sm != null) { sm.invoke(h, slot, stack); return; }
                    sm = Reflect.findMethod(h.getClass(), "m_6836_", new Class<?>[]{int.class, ItemStack.class});
                    if (sm != null) { sm.invoke(h, slot, stack); return; }
                }
            } catch (Exception ignored) {
                RSIntegrationMod.LOGGER.debug("[RSI-WR] getItemHandler probe failed: {}", ignored.toString());
            }
        }
        IItemHandler cap = getForgeItemHandler(be);
        if (cap != null) {
            if (cap instanceof ItemStackHandler handler) {
                handler.setStackInSlot(slot, stack);
                return;
            }
            ItemStack old = cap.extractItem(slot, cap.getStackInSlot(slot).getCount(), false);
            ItemStack leftover = cap.insertItem(slot, stack, false);
            if (!leftover.isEmpty()) {
                if (!old.isEmpty()) cap.insertItem(slot, old, false);
            } else {
                return;
            }
        }
        net.minecraft.world.SimpleContainer live = getLiveSimpleContainer(be);
        if (live != null) { live.setItem(slot, stack); return; }
        m = Reflect.findMethod(bc, "m_6836_", new Class<?>[]{int.class, ItemStack.class});
        if (m != null) { try { m.invoke(be, slot, stack); return; } catch (Exception ignored) {
            RSIntegrationMod.LOGGER.debug("[RSI-WR] setItem SRG failed: {}", ignored.toString());
        }}
        net.minecraft.world.SimpleContainer sc = getSimpleContainer(be);
        if (sc != null) { sc.setItem(slot, stack); return; }
        RSIntegrationMod.LOGGER.warn("[RSI-WR] Failed to set container item for {}", be.getClass().getName());
    }

    // ── Client sync ────────────────────────────────────────────────

    public static void syncBlockEntity(Object be) {
        if (!(be instanceof BlockEntity blockEntity)) return;
        var level = blockEntity.getLevel();
        if (level == null) return;
        try {
            Class<?> updateClass = Class.forName(
                    "mod.maxbogomol.fluffy_fur.common.network.BlockEntityUpdate");
            Reflect.getMethodOrThrow(updateClass, "packet", "packet", BlockEntity.class).invoke(null, blockEntity);
        } catch (Exception e) {
            blockEntity.setChanged();
            level.sendBlockUpdated(blockEntity.getBlockPos(),
                    blockEntity.getBlockState(), blockEntity.getBlockState(), 3);
        }
    }

    // ── Internal ───────────────────────────────────────────────────

    private static String getFieldTypes(Class<?> bc) {
        StringBuilder sb = new StringBuilder();
        Class<?> clazz = bc;
        int count = 0;
        while (clazz != null && clazz != Object.class && count < 3) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(field.getName()).append(':').append(field.getType().getSimpleName());
            }
            clazz = clazz.getSuperclass();
            count++;
        }
        return sb.toString();
    }
}
