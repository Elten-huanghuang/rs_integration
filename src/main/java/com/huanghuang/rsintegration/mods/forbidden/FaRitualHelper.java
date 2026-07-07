package com.huanghuang.rsintegration.mods.forbidden;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.reflection.probes.FAReflection;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared reflection helpers for FA (Forbidden &amp; Arcanus) Hephaestus Forge rituals.
 * Used by both {@link FaBatchDelegate} and {@link FaCraftPacket}.
 */
public final class FaRitualHelper {

    private FaRitualHelper() {}

    private static volatile ResourceKey<?> cachedFaRitualKey;
    /** Built once from the FA ritual registry — O(1) lookups thereafter. */
    private static volatile Map<ResourceLocation, Object> cachedRitualMap;


    // ── Registry ─────────────────────────────────────────────────

    @Nullable
    static ResourceKey<?> getFARegistryKey() {
        if (cachedFaRitualKey != null) return cachedFaRitualKey;
        try {
            Class<?> faRegistries = Class.forName(
                    "com.stal111.forbidden_arcanus.core.registry.FARegistries");
            java.lang.reflect.Field field = faRegistries.getField("RITUAL");
            field.setAccessible(true);
            cachedFaRitualKey = (ResourceKey<?>) field.get(null);
        } catch (Exception ex) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Failed to get FA ritual registry key", ex);
        }
        return cachedFaRitualKey;
    }

    @Nullable
    public static Object getRitualById(ResourceLocation id, ServerLevel level) {
        ResourceKey<?> key = getFARegistryKey();
        if (key == null) return null;
        try {
            net.minecraft.core.Registry<?> registry = level.registryAccess().registryOrThrow(
                    (ResourceKey<? extends net.minecraft.core.Registry<?>>) (Object) key);

            // Build the cache once — entrySet() iteration is O(N) and this is
            // on the recipe-resolution hot path.  After the first call every
            // lookup is an O(1) HashMap.get().
            Map<ResourceLocation, Object> map = cachedRitualMap;
            if (map == null) {
                synchronized (FaRitualHelper.class) {
                    map = cachedRitualMap;
                    if (map == null) {
                        map = new ConcurrentHashMap<>();
                        for (var entry : registry.entrySet()) {
                            map.put(entry.getKey().location(), entry.getValue());
                        }
                        cachedRitualMap = map;
                    }
                }
            }

            return map.get(id);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Tier ─────────────────────────────────────────────────────

    static int getForgeTier(BlockState state, @Nullable BlockEntity be) {
        // 1. Try blockstate property (some FA versions expose tier here)
        try {
            boolean foundTier = false;
            for (net.minecraft.world.level.block.state.properties.Property<?> prop : state.getProperties()) {
                if (prop.getName().equals("tier")) {
                    foundTier = true;
                    Comparable<?> val = state.getValue(prop);
                    if (val instanceof Number n) {
                        RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier blockstate → {}", n.intValue());
                        return n.intValue();
                    } else {
                        RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier blockstate 'tier' value is not Number: {} (type={})",
                                val, val != null ? val.getClass().getName() : "null");
                    }
                }
            }
            if (!foundTier) {
                // Diagnostic: dump block identity + all properties
                StringBuilder props = new StringBuilder();
                for (net.minecraft.world.level.block.state.properties.Property<?> prop : state.getProperties()) {
                    if (props.length() > 0) props.append(", ");
                    props.append(prop.getName()).append("=").append(state.getValue(prop));
                }
                ResourceLocation blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
                RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier blockstate has NO 'tier' property. block={} props=[{}]",
                        blockId, props.toString());
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier blockstate probe failed", e);
        }

        // 2. Try BlockEntity.getTier() / getForgeTier() (older FA versions)
        if (be != null && FAReflection.hephaestusForgeBEClass != null && FAReflection.hephaestusForgeBEClass.isInstance(be)) {
            try {
                java.lang.reflect.Method getTier = Reflect.findMethod(
                        FAReflection.hephaestusForgeBEClass, "getTier", new Class<?>[0]);
                if (getTier == null) {
                    getTier = Reflect.findMethod(
                            FAReflection.hephaestusForgeBEClass, "getForgeTier", new Class<?>[0]);
                }
                if (getTier != null) {
                    Object val = getTier.invoke(be);
                    if (val instanceof Number n) {
                        RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier method → {}", n.intValue());
                        return n.intValue();
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier method probe failed", e);
            }

            // 3. Try tier / forgeTier field directly (older FA versions)
            try {
                java.lang.reflect.Field f = Reflect.findField(FAReflection.hephaestusForgeBEClass, "tier").orElse(null);
                if (f == null) {
                    f = Reflect.findField(FAReflection.hephaestusForgeBEClass, "forgeTier").orElse(null);
                }
                if (f != null) {
                    f.setAccessible(true);
                    int v = f.getInt(be);
                    RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier field → {}", v);
                    return v;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier field probe failed", e);
            }

            // 4. FA 2.2.x: ValueNotifier<HephaestusForgeLevel> forgeLevel
            try {
                java.lang.reflect.Field flField = Reflect.findField(FAReflection.hephaestusForgeBEClass, "forgeLevel").orElse(null);
                if (flField != null) {
                    flField.setAccessible(true);
                    Object notifier = flField.get(be);
                    RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier forgeLevel field found, notifier={}", notifier);
                    if (notifier != null) {
                        // ValueNotifier.get() → HephaestusForgeLevel (implements IntSupplier)
                        Object level = notifier.getClass().getMethod("get").invoke(notifier);
                        RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier ValueNotifier.get() → {}", level);
                        if (level != null) {
                            int result = (int) level.getClass().getMethod("getAsInt").invoke(level);
                            RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier HephaestusForgeLevel.getAsInt() → {}", result);
                            return result;
                        }
                    }
                } else {
                    RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier forgeLevel field NOT FOUND on {}", FAReflection.hephaestusForgeBEClass.getName());
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier forgeLevel probe failed", e);
            }
        } else {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier BE check skipped: be={} beClass={} isInstance={}",
                    be != null ? be.getClass().getName() : "null",
                    FAReflection.hephaestusForgeBEClass != null ? FAReflection.hephaestusForgeBEClass.getName() : "null",
                    be != null && FAReflection.hephaestusForgeBEClass != null ? FAReflection.hephaestusForgeBEClass.isInstance(be) : false);
        }
        RSIntegrationMod.LOGGER.debug("[RSI-FA] getForgeTier FALLBACK → 1");
        return 1;
    }

    static int getRitualRequiredTier(Object ritual) {
        try {
            Object req = invoke(ritual, "requirements");
            if (req != null) {
                return (int) Reflect.getMethodOrThrow(req.getClass(), "tier", "tier").invoke(req);
            }
        } catch (Exception e) { /* ignore */ }
        return 1;
    }

    /** Reads the exact required tier from an UpgradeTierResult. Returns -1 on failure. */
    static int readUpgradeRequiredTier(Object upgradeResult) {
        try {
            return (int) Reflect.getMethodOrThrow(FAReflection.upgradeTierResultClass, "requiredTier", "requiredTier").invoke(upgradeResult);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] readUpgradeRequiredTier method failed", e);
        }
        try {
            java.lang.reflect.Field f = Reflect.findField(FAReflection.upgradeTierResultClass, "requiredTier").orElse(null);
            if (f != null) { f.setAccessible(true); return f.getInt(upgradeResult); }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] readUpgradeRequiredTier field failed", e);
        }
        return -1;
    }

    // ── Enhancer modifiers ───────────────────────────────────────

    /**
     * Collect {@code EssenceModifier} instances from enhancers placed in the forge.
     * Mirrors what {@code RitualManager.canStartRitual} does before comparing essences.
     */
    @SuppressWarnings("unchecked")
    static List<Object> collectEnhancerModifiers(Object ritualManager, Object ritual) {
        List<Object> modifiers = new ArrayList<>();
        try {
                Set<Object> requiredDefs = new HashSet<>();
            Object requirements = invoke(ritual, "requirements");
            if (requirements != null) {
                List<?> requiredEnhancers = invokeList(requirements, "enhancers");
                if (requiredEnhancers != null) {
                    for (Object holder : requiredEnhancers) {
                        Object def = Reflect.extractHolderValue(holder);
                        if (def != null) requiredDefs.add(def);
                    }
                }
            }
            if (requiredDefs.isEmpty()) return modifiers;

            java.lang.reflect.Field accessorField = Reflect.findField(FAReflection.ritualManagerClass, "enhancerAccessor").orElse(null);
            if (accessorField == null) return modifiers;
            accessorField.setAccessible(true);
            Object enhancerAccessor = accessorField.get(ritualManager);
            if (enhancerAccessor == null) return modifiers;

            List<?> enhancers = (List<?>) Reflect.getMethodOrThrow(FAReflection.enhancerAccessorClass, "getEnhancers", "getEnhancers").invoke(enhancerAccessor);
            if (enhancers == null) return modifiers;

            for (Object enhancerDef : enhancers) {
                if (!requiredDefs.contains(enhancerDef)) continue;
                List<?> effects = (List<?>) Reflect.getMethodOrThrow(FAReflection.enhancerDefinitionClass, "effects", "effects").invoke(enhancerDef);
                if (effects == null) continue;
                for (Object effect : effects) {
                    if (FAReflection.essenceModifierClass.isInstance(effect)) {
                        modifiers.add(effect);
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] Failed to collect enhancer modifiers", e);
        }
        return modifiers;
    }

    // ── Essence validation ───────────────────────────────────────

    /**
     * Checks all 4 essence types individually and sends per-type error
     * messages so the player knows exactly what's missing.
     *
     * @return true if all required essences are available
     */
    static boolean checkEssences(ServerPlayer player, Object ritual, Object forge, Object ritualManager) {
        try {
                Object ritualEssences = Reflect.getMethodOrThrow(FAReflection.ritualClass, "essences", "essences").invoke(ritual);

            List<Object> enhancerModifiers = collectEnhancerModifiers(ritualManager, ritual);
            Object requiredEssences = ritualEssences;
            if (!enhancerModifiers.isEmpty()) {
                requiredEssences = Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "applyModifiers", "applyModifiers",
                        List.class).invoke(ritualEssences, enhancerModifiers);
            }

            int reqAureal = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "aureal", "aureal").invoke(requiredEssences);
            int reqSouls  = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "souls", "souls").invoke(requiredEssences);
            int reqBlood  = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "blood", "blood").invoke(requiredEssences);
            int reqExp    = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "experience", "experience").invoke(requiredEssences);

            Object curEssences = Reflect.getMethodOrThrow(FAReflection.hephaestusForgeBEClass, "getEssences", "getEssences").invoke(forge);
            int curAureal = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "aureal", "aureal").invoke(curEssences);
            int curSouls  = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "souls", "souls").invoke(curEssences);
            int curBlood  = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "blood", "blood").invoke(curEssences);
            int curExp    = (int) Reflect.getMethodOrThrow(FAReflection.essencesDefinitionClass, "experience", "experience").invoke(curEssences);

            RSIntegrationMod.LOGGER.debug("[RSI-FA] Essence check: a={}/{}, s={}/{}, b={}/{}, e={}/{}",
                    curAureal, reqAureal, curSouls, reqSouls, curBlood, reqBlood, curExp, reqExp);

            boolean ok = true;
            if (reqAureal > 0 && curAureal < reqAureal) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.fa.error.insufficient_aureal", reqAureal, curAureal));
                ok = false;
            }
            if (reqSouls > 0 && curSouls < reqSouls) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.fa.error.insufficient_souls", reqSouls, curSouls));
                ok = false;
            }
            if (reqBlood > 0 && curBlood < reqBlood) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.fa.error.insufficient_blood", reqBlood, curBlood));
                ok = false;
            }
            if (reqExp > 0 && curExp < reqExp) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.fa.error.insufficient_experience", reqExp, curExp));
                ok = false;
            }
            return ok;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Essence check failed", e);
            player.sendSystemMessage(Component.translatable("rsi.fa.error.essence_check_failed"));
            return false;
        }
    }

    // ── Pedestal finding ─────────────────────────────────────────

    static List<Object> findPedestals(BlockPos forgePos, ServerLevel level) {
        List<Object> result = new ArrayList<>();
        try {
            BlockPos.betweenClosedStream(
                    forgePos.offset(-8, -3, -8),
                    forgePos.offset(8, 3, 8)
            ).forEach(cp -> {
                BlockEntity be = level.getBlockEntity(cp);
                if (be != null && FAReflection.pedestalBEClass.isInstance(be)) {
                    result.add(be);
                }
            });
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-FA] Failed to find pedestals", e);
            return null;
        }
        return result;
    }

    // ── Slot helpers ─────────────────────────────────────────────

    private static volatile Integer cachedMainSlot;

    static int getMainSlot() {
        Integer v = cachedMainSlot;
        if (v != null) return v;
        try {
            java.lang.reflect.Field f = Reflect.findField(FAReflection.hephaestusForgeBEClass, "MAIN_SLOT").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                cachedMainSlot = f.getInt(null);
                return cachedMainSlot;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] MAIN_SLOT lookup failed", e);
        }
        return 0;
    }

    static void setForgeSlot(Object be, int slot, ItemStack stack) {
        // Strategy 1: setStack(int, ItemStack) — FA's actual method name
        try {
            Method m = Reflect.findMethod(be.getClass(), "setStack",
                    new Class<?>[]{int.class, ItemStack.class});
            if (m != null) { m.invoke(be, slot, stack); return; }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S1 setStack({}) failed", slot, e);
        }
        // Strategy 2: setStackInSlot (MCP mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "setStackInSlot",
                    new Class<?>[]{int.class, ItemStack.class});
            if (m != null) { m.invoke(be, slot, stack); return; }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S2 setStackInSlot({}) failed", slot, e);
        }
        // Strategy 3: setItem (Mojang mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "setItem",
                    new Class<?>[]{int.class, ItemStack.class});
            if (m != null) { m.invoke(be, slot, stack); return; }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S3 setItem({}) failed", slot, e);
        }
        // Strategy 4: Forge IItemHandler capability
        try {
            var cap = net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER;
            var handler = be.getClass().getMethod("getCapability",
                    net.minecraftforge.common.capabilities.Capability.class,
                    net.minecraft.core.Direction.class)
                    .invoke(be, cap, null);
            if (handler != null) {
                var ih = (net.minecraftforge.items.IItemHandler) handler;
                if (slot < ih.getSlots()) {
                    ih.extractItem(slot, ih.getStackInSlot(slot).getCount(), false);
                    if (!stack.isEmpty()) ih.insertItem(slot, stack.copy(), false);
                }
                return;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S4 IItemHandler set({}) failed", slot, e);
        }
        // Strategy 5: itemStackHandler field
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "itemStackHandler").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                Object h = f.get(be);
                if (h instanceof net.minecraftforge.items.IItemHandler ih) {
                    if (slot < ih.getSlots()) {
                        ih.extractItem(slot, ih.getStackInSlot(slot).getCount(), false);
                        if (!stack.isEmpty()) ih.insertItem(slot, stack.copy(), false);
                    }
                    return;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S5 itemStackHandler field({}) failed", slot, e);
        }
        // Strategy 6: inventory / items NonNullList field
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "inventory").orElse(null);
            if (f == null) f = Reflect.findField(be.getClass(), "items").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                var list = (net.minecraft.core.NonNullList<ItemStack>) f.get(be);
                if (slot < list.size()) { list.set(slot, stack.copy()); be.getClass().getMethod("setChanged").invoke(be); }
                return;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S6 inventory field({}) failed", slot, e);
        }
        RSIntegrationMod.LOGGER.warn("[RSI-FA] All slot strategies failed for set slot {}", slot);
    }

    static ItemStack getForgeSlot(Object be, int slot) {
        // Strategy 1: getStack(int) — FA's actual method name
        try {
            Method m = Reflect.findMethod(be.getClass(), "getStack",
                    new Class<?>[]{int.class});
            if (m != null) return (ItemStack) m.invoke(be, slot);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S1 getStack({}) failed", slot, e);
        }
        // Strategy 2: getStackInSlot (MCP mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "getStackInSlot",
                    new Class<?>[]{int.class});
            if (m != null) return (ItemStack) m.invoke(be, slot);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S2 getStackInSlot({}) failed", slot, e);
        }
        // Strategy 3: getItem (Mojang mapped name)
        try {
            Method m = Reflect.findMethod(be.getClass(), "getItem",
                    new Class<?>[]{int.class});
            if (m != null) return (ItemStack) m.invoke(be, slot);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S3 getItem({}) failed", slot, e);
        }
        // Strategy 4: Forge IItemHandler capability
        try {
            var cap = net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER;
            var handler = be.getClass().getMethod("getCapability",
                    net.minecraftforge.common.capabilities.Capability.class,
                    net.minecraft.core.Direction.class)
                    .invoke(be, cap, null);
            if (handler != null) {
                var ih = (net.minecraftforge.items.IItemHandler) handler;
                if (slot < ih.getSlots()) return ih.getStackInSlot(slot);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S4 IItemHandler get({}) failed", slot, e);
        }
        // Strategy 5: itemStackHandler field
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "itemStackHandler").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                Object h = f.get(be);
                if (h instanceof net.minecraftforge.items.IItemHandler ih) {
                    if (slot < ih.getSlots()) return ih.getStackInSlot(slot);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S5 itemStackHandler field({}) failed", slot, e);
        }
        // Strategy 6: inventory / items NonNullList field
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "inventory").orElse(null);
            if (f == null) f = Reflect.findField(be.getClass(), "items").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                var list = (net.minecraft.core.NonNullList<ItemStack>) f.get(be);
                if (slot < list.size()) return list.get(slot);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FA] S6 inv field({}) failed", slot, e);
        }
        return ItemStack.EMPTY;
    }

    // ── Reflection helpers ───────────────────────────────────────

    @Nullable
    static Object invoke(Object obj, String methodName) {
        try {
            Method m = Reflect.findMethod(obj.getClass(), methodName, new Class<?>[0]);
            if (m == null) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] invoke: method not found {}.{}",
                        obj.getClass().getName(), methodName);
                return null;
            }
            return m.invoke(obj);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] invoke failed: {}.{} —", obj.getClass().getName(), methodName, e);
            return null;
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    static List<?> invokeList(Object obj, String methodName) {
        try {
            Method m = Reflect.findMethod(obj.getClass(), methodName, new Class<?>[0]);
            if (m == null) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] invokeList: method not found {}.{}",
                        obj.getClass().getName(), methodName);
                return null;
            }
            return (List<?>) m.invoke(obj);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] invokeList failed: {}.{} —", obj.getClass().getName(), methodName, e);
            return null;
        }
    }

    static int invokeInt(Object obj, String methodName) {
        try {
            Method m = Reflect.findMethod(obj.getClass(), methodName, new Class<?>[0]);
            if (m == null) return 0;
            Object v = m.invoke(obj);
            return v instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) { return 0; }
    }

    /**
     * Tries multiple reflection strategies to read RitualManager's current
     * valid ritual.  FA versions rename this field/method across builds.
     */
    @Nullable
    static Object getValidRitualSafe(Object ritualManager) {
        try {
            Method m = Reflect.findMethod(FAReflection.ritualManagerClass, "getValidRitual", new Class<?>[0]);
            if (m != null) return m.invoke(ritualManager);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Fa] reflection probe failed", e); }
        try {
            Method m = Reflect.findMethod(FAReflection.ritualManagerClass, "getRitual", new Class<?>[0]);
            if (m != null) return m.invoke(ritualManager);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Fa] reflection probe failed", e); }
        try {
            java.lang.reflect.Field f = Reflect.findField(FAReflection.ritualManagerClass, "validRitual").orElse(null);
            if (f != null) { f.setAccessible(true); return f.get(ritualManager); }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Fa] reflection probe failed", e); }
        try {
            java.lang.reflect.Field f = Reflect.findField(FAReflection.ritualManagerClass, "ritual").orElse(null);
            if (f != null) { f.setAccessible(true); return f.get(ritualManager); }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Fa] reflection probe failed", e); }
        return null;
    }

    // ── RitualStarterItem helpers ─────────────────────────────────

    /** Return type for {@link #findRitualStarterItem}. */
    public static final class StarterResult {
        private final ItemStack stack;
        private final INetwork sourceNetwork;

        StarterResult(ItemStack stack, @Nullable INetwork sourceNetwork) {
            this.stack = stack;
            this.sourceNetwork = sourceNetwork;
        }

        public ItemStack stack() { return stack; }
        @Nullable public INetwork sourceNetwork() { return sourceNetwork; }
        public boolean isEmpty() { return stack.isEmpty(); }

        public static final StarterResult EMPTY = new StarterResult(ItemStack.EMPTY, null);
    }

    static StarterResult findRitualStarterItem(ServerPlayer player, @Nullable INetwork network) {
        if (FAReflection.ritualStarterItemClass == null) return StarterResult.EMPTY;

        // 1. Check player inventory + offhand
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && FAReflection.ritualStarterItemClass.isInstance(stack.getItem())
                    && canStartRitual(stack)) {
                RSIntegrationMod.LOGGER.debug("[RSI-FA] Found RitualStarterItem '{}' in player inventory",
                        stack.getHoverName().getString());
                return new StarterResult(stack, null);
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && FAReflection.ritualStarterItemClass.isInstance(offhand.getItem())
                && canStartRitual(offhand)) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] Found RitualStarterItem '{}' in player offhand",
                    offhand.getHoverName().getString());
            return new StarterResult(offhand, null);
        }

        // 2. Fall back to RS network
        if (network != null) {
            try {
                var cacheList = network.getItemStorageCache().getList();
                if (cacheList != null) {
                    for (var entry : cacheList.getStacks()) {
                        ItemStack rsStack = entry.getStack();
                        if (rsStack.isEmpty()) continue;
                        if (!FAReflection.ritualStarterItemClass.isInstance(rsStack.getItem())) continue;
                        if (!canStartRitual(rsStack)) {
                            RSIntegrationMod.LOGGER.debug("[RSI-FA] RS has RitualStarterItem '{}' but canStartRitual=false",
                                    rsStack.getHoverName().getString());
                            continue;
                        }

                        ItemStack req = rsStack.copy();
                        req.setCount(1);
                        ItemStack extracted = network.extractItem(req, 1,
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                        if (!extracted.isEmpty()) {
                            RSIntegrationMod.LOGGER.debug("[RSI-FA] Extracted RitualStarterItem '{}' from RS",
                                    extracted.getHoverName().getString());
                            return new StarterResult(extracted, network);
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-FA] RS starter search failed", e);
            }
        }

        return StarterResult.EMPTY;
    }

    static boolean canStartRitual(ItemStack stack) {
        try {
            return (boolean) Reflect.getMethodOrThrow(FAReflection.ritualStarterItemClass, "canStartRitual", "canStartRitual", ItemStack.class).invoke(stack.getItem(), stack);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Decrements remaining uses on a RitualStarterItem unless the player
     * is in creative mode.  If the item was extracted from RS, re-inserts
     * it after modifying durability.
     */
    static void consumeRitualStarterUse(ItemStack starterStack, ServerPlayer player,
                                        @Nullable INetwork starterNetwork) {
        boolean isCreative = player.isCreative();
        String source = starterNetwork != null ? "RS" : "inventory";
        RSIntegrationMod.LOGGER.debug("[RSI-FA] consumeRitualStarterUse: item='{}' creative={} source={}",
                starterStack.getHoverName().getString(), isCreative, source);

        if (!isCreative) {
            try {
                Object item = starterStack.getItem();
                int remaining = (int) Reflect.getMethodOrThrow(FAReflection.ritualStarterItemClass, "getRemainingUses", "getRemainingUses", ItemStack.class).invoke(item, starterStack);
                RSIntegrationMod.LOGGER.debug("[RSI-FA] Starter '{}' uses before: {} (source: {})",
                        starterStack.getHoverName().getString(), remaining, source);
                if (remaining > 0) {
                    int newRemaining = remaining - 1;
                    Reflect.getMethodOrThrow(FAReflection.ritualStarterItemClass, "setRemainingUses", "setRemainingUses", ItemStack.class, int.class)
                            .invoke(item, starterStack, newRemaining);
                    RSIntegrationMod.LOGGER.debug("[RSI-FA] Starter '{}' uses after: {}",
                            starterStack.getHoverName().getString(), newRemaining);
                } else {
                    RSIntegrationMod.LOGGER.warn("[RSI-FA] Starter '{}' has {} remaining uses — cannot consume",
                            starterStack.getHoverName().getString(), remaining);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-FA] consumeRitualStarterUse failed for '{}'", starterStack.getHoverName().getString(), e);
            }
        } else {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] consumeRitualStarterUse: player is creative, not consuming durability");
        }

        // Always re-insert to RS if it came from there — even in creative mode
        if (starterNetwork != null) {
            ItemStack leftover = starterNetwork.insertItem(starterStack, starterStack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!leftover.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-FA] consumeRitualStarterUse: RS insert failed, giving '{}' to player",
                        starterStack.getHoverName().getString());
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        }
    }

    static void returnStarterToSource(ItemStack stack, ServerPlayer player,
                                      @Nullable INetwork starterNetwork) {
        if (stack.isEmpty()) return;
        if (starterNetwork != null) {
            ItemStack leftover = starterNetwork.insertItem(stack, stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!leftover.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-FA] returnStarterToSource: RS insert failed, giving '{}' to player",
                        stack.getHoverName().getString());
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        }
    }

    // ── Display ───────────────────────────────────────────────────

    static String enhancerDefName(Object enhancerDef) {
        Optional<Object> item = Reflect.getField(enhancerDef, "item");
        if (item.isPresent() && item.get() instanceof Item it) {
            return it.getDescription().getString();
        }
        Optional<Object> desc = Reflect.invoke(enhancerDef, "getDescription");
        if (desc.isPresent() && desc.get() instanceof Component c) {
            return c.getString();
        }
        return enhancerDef.toString();
    }

    // ── Recipe resolution (used by GenericCraftPacket) ────────────

    private static volatile Item faForgeBlockItem;
    private static volatile java.lang.reflect.Method faSetTierOnStack;

    /** Create a HephaestusForgeBlock ItemStack with {@code upgradedTier}
     *  applied via {@code setTierOnStack}, matching FA's own JEI display. */
    @Nullable
    public static ItemStack makeFaUpgradeOutput(int upgradedTier) {
        try {
            if (faForgeBlockItem == null) {
                net.minecraft.world.level.block.Block block =
                        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                                new ResourceLocation(ModIds.FORBIDDEN_ARCANUS, "hephaestus_forge"));
                if (block == null) return ItemStack.EMPTY;
                faForgeBlockItem = block.asItem();
            }
            if (faSetTierOnStack == null) {
                Class<?> hfbClass = Class.forName(
                        "com.stal111.forbidden_arcanus.common.block.HephaestusForgeBlock");
                faSetTierOnStack = Reflect.findMethod(hfbClass, "setTierOnStack",
                        new Class<?>[]{ItemStack.class, int.class});
            }
            if (faSetTierOnStack == null) return ItemStack.EMPTY;
            ItemStack stack = new ItemStack(faForgeBlockItem);
            return (ItemStack) faSetTierOnStack.invoke(null, stack, upgradedTier);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] Upgrade output failed", e);
            return ItemStack.EMPTY;
        }
    }

    /** Fallback output for FA rituals whose {@code result()} is null or has an
     *  unrecognized type. Reads {@code mainIngredient} and returns the first
     *  matching item stack. */
    @Nullable
    public static ItemStack faFallbackOutput(Object ritual, ResourceLocation recipeId) {
        try {
            java.lang.reflect.Method getMain = Reflect.findMethod(ritual.getClass(), "mainIngredient", new Class<?>[0]);
            if (getMain == null) return ItemStack.EMPTY;
            Object main = getMain.invoke(ritual);
            if (main instanceof Ingredient ing && !ing.isEmpty()) {
                ItemStack[] items = ing.getItems();
                if (items.length > 0 && !items[0].isEmpty()) {
                    return items[0].copy();
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] Fallback output failed for {}", recipeId, e);
        }
        return ItemStack.EMPTY;
    }

    /** Scan FA ritual registry by iterating entries — O(N) fallback when the
     *  cached HashMap lookup misses due to key-format differences. */
    @Nullable
    public static Recipe<?> resolveFARitualScan(ServerLevel level, ResourceLocation recipeId) {
        ResourceKey<?> key = getFARegistryKey();
        if (key == null) return null;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            net.minecraft.core.Registry<?> registry = level.registryAccess()
                    .registryOrThrow((ResourceKey<? extends net.minecraft.core.Registry<?>>) (Object) key);
            for (var entry : registry.entrySet()) {
                if (entry.getKey().location().equals(recipeId)) {
                    return wrapFaRitual(recipeId, entry.getValue());
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] Scan failed", e);
        }
        return null;
    }

    /** Wrap a raw FA ritual object into a {@link FaRitualWrapper}. */
    @Nullable
    public static Recipe<?> wrapFaRitual(ResourceLocation recipeId, Object ritual) {
        try {
                java.lang.reflect.Method getResult = Reflect.findMethod(ritual.getClass(), "result", new Class<?>[0]);
            Object result = getResult != null ? getResult.invoke(ritual) : null;

            ItemStack output = ItemStack.EMPTY;
            if (result != null && FAReflection.createItemResultClass.isInstance(result)) {
                java.lang.reflect.Method getStack = Reflect.findMethod(result.getClass(),
                        "getResult", new Class<?>[0]);
                if (getStack != null) {
                    Object s = getStack.invoke(result);
                    if (s instanceof ItemStack st && !st.isEmpty())
                        output = st;
                }
            } else if (result != null && FAReflection.upgradeTierResultClass != null
                    && FAReflection.upgradeTierResultClass.isInstance(result)) {
                java.lang.reflect.Method getFrom = Reflect.findMethod(result.getClass(), "getRequiredTier", new Class<?>[0]);
                java.lang.reflect.Method getTo = Reflect.findMethod(result.getClass(), "getUpgradedTier", new Class<?>[0]);
                int from = 0, to = 0;
                try { if (getFrom != null) from = (int) getFrom.invoke(result); } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Fa] reflection probe failed", e); }
                try { if (getTo != null) to = (int) getTo.invoke(result); } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Fa] reflection probe failed", e); }
                output = makeFaUpgradeOutput(to);
                if (!output.isEmpty()) return new FaRitualWrapper(recipeId, ritual, output, from, to);
            }

            if (output.isEmpty()) {
                output = faFallbackOutput(ritual, recipeId);
            }
            if (output.isEmpty()) return null;

            return new FaRitualWrapper(recipeId, ritual, output);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FA] wrapFaRitual failed for {}", recipeId, e);
            return null;
        }
    }
}
