package com.huanghuang.rsintegration.reflection.contract;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.VersionRange;
import com.huanghuang.rsintegration.reflection.probes.AetherworksReflection;
import com.huanghuang.rsintegration.reflection.probes.BackpackReflection;
import com.huanghuang.rsintegration.reflection.probes.CrabbersDelightReflection;
import com.huanghuang.rsintegration.reflection.probes.CrockPotReflection;
import com.huanghuang.rsintegration.reflection.probes.DistantWorldsReflection;
import com.huanghuang.rsintegration.reflection.probes.EidolonReflection;
import com.huanghuang.rsintegration.reflection.probes.EmbersReflection;
import com.huanghuang.rsintegration.reflection.probes.FAReflection;
import com.huanghuang.rsintegration.reflection.probes.FRReflection;
import com.huanghuang.rsintegration.reflection.probes.FarmersDelightReflection;
import com.huanghuang.rsintegration.reflection.probes.FarmingForBlockheadsReflection;
import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import com.huanghuang.rsintegration.reflection.probes.ImmersalsDelightReflection;
import com.huanghuang.rsintegration.reflection.probes.JEIReflection;
import com.huanghuang.rsintegration.reflection.probes.MalumReflection;
import com.huanghuang.rsintegration.reflection.probes.TLMReflection;
import com.huanghuang.rsintegration.reflection.probes.WRReflection;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Startup-time validation of all registered {@link ReflectionContract} entries.
 *
 * <p>Called once during {@code onCommonSetup()}.  Uses
 * {@code Class.forName(name, false, classLoader)} — the {@code false}
 * skips static initializers, avoiding both the performance cost of
 * class-loading cascades and the risk of {@code ExceptionInInitializerError}
 * when probing mod internals before the game is fully initialised.</p>
 *
 * <p>On success, resolved {@code Class<?>}, {@code Field}, and
 * {@code Method} handles are written into the corresponding probe class
 * static fields via the registered target map, so the runtime path is
 * a pure field read with zero locking.</p>
 */
public final class ContractValidation {

    private static final List<ReflectionContract> CONTRACTS = new ArrayList<>();
    private static final Map<String, Field> TARGET_FIELDS = new LinkedHashMap<>();

    private ContractValidation() {}

    /** Register a contract to be validated. */
    public static void register(ReflectionContract contract) {
        CONTRACTS.add(contract);
    }

    /**
     * Register a target field for eager resolution.
     *
     * @param contractDescription  must match the {@code description} of a
     *                             previously registered {@link ReflectionContract}
     * @param targetField          the static field on a probe class that
     *                             should receive the resolved {@code Class<?>}
     */
    public static void registerTarget(String contractDescription, Field targetField) {
        targetField.setAccessible(true);
        Field existing = TARGET_FIELDS.put(contractDescription, targetField);
        if (existing != null && existing != targetField) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Contract] Contract description collision: '{}' was already bound to {}, now overwritten by {}",
                    contractDescription, existing, targetField);
        }
    }

    /**
     * Validate all registered contracts and populate probe-class static fields.
     * Called once from {@code RSIntegrationMod.onCommonSetup()}.
     */
    public static void validateAll() {
        ensureProbeClassesLoaded();
        ClassLoader cl = ContractValidation.class.getClassLoader();
        int ok = 0, failed = 0, skipped = 0;
        Set<Class<?>> invalidProbes = new HashSet<>();

        for (ReflectionContract c : CONTRACTS) {
            if (!ModList.get().isLoaded(c.modId())) { skipped++; continue; }

            // Version filtering
            if (c.minVersion() != null && VersionRange.isBelow(c.modId(), c.minVersion())) {
                skipped++; continue;
            }
            if (c.maxVersion() != null && VersionRange.isAtLeast(c.modId(), c.maxVersion())) {
                skipped++; continue;
            }

            try {
                // false = do not trigger static initializers
                Class<?> clazz = Class.forName(c.className(), false, cl);

                // Validate fields
                for (ReflectionContract.FieldContract fc : c.fields()) {
                    Field f = resolveField(clazz, fc);
                    if (fc.expectedType() != null
                            && !fc.expectedType().isAssignableFrom(f.getType())) {
                        throw new NoSuchFieldException(
                            "Field " + fc.name() + " is " + f.getType().getName()
                            + ", expected " + fc.expectedType().getName());
                    }
                    f.setAccessible(true);
                }

                // Validate methods (precise signature)
                for (ReflectionContract.MethodContract mc : c.methods()) {
                    Method m = resolveMethod(clazz, mc);
                    m.setAccessible(true);
                }

                // Eager-populate the probe class field
                Field target = TARGET_FIELDS.get(c.description());
                if (target != null) {
                    target.set(null, clazz);
                }

                RSIntegrationMod.LOGGER.debug("[RSI-Contract] OK: {} — {}",
                        c.modId(), c.description());
                ok++;
            } catch (Exception | LinkageError e) {
                String msg = "[RSI-Contract] {} 反射契约失败: {} — {}";
                if (c.required()) {
                    failed++;
                    Field target = TARGET_FIELDS.get(c.description());
                    if (target != null) invalidProbes.add(target.getDeclaringClass());
                    RSIntegrationMod.LOGGER.error(msg, c.modId(), c.description(),
                            c.className(), e);
                } else {
                    skipped++;
                    RSIntegrationMod.LOGGER.debug(
                            "[RSI-Contract] Optional contract unavailable: {} — {} ({})",
                            c.modId(), c.description(), c.className());
                }
            }
        }

        // A probe's isAvailable() generally checks one representative field.
        // If any required contract in that probe failed, clear every resolved
        // target in the probe so partial resolution cannot enable a broken adapter.
        for (Class<?> probe : invalidProbes) {
            for (Field target : TARGET_FIELDS.values()) {
                if (target.getDeclaringClass() != probe) continue;
                try {
                    target.set(null, null);
                } catch (IllegalAccessException e) {
                    RSIntegrationMod.LOGGER.error(
                            "[RSI-Contract] Failed to disable invalid probe {}", probe.getName(), e);
                }
            }
        }

        RSIntegrationMod.LOGGER.info(
                "[RSI-Contract] Contract validation done: {} OK, {} failed, {} skipped",
                ok, failed, skipped);
    }

    /**
     * Access a non-constant static field on every probe class to trigger
     * {@code <clinit>}.  Java only initializes a class on first active use
     * (JLS §12.4.1); without this, the static blocks that call
     * {@link #register} and {@link #registerTarget} never run and
     * {@link #validateAll()} finds zero contracts.
     * <p>
     * Each probe is loaded individually with its own try-catch to prevent
     * a single probe's initialization failure (field name typo, etc.) from
     * cascading and breaking all other probes.
     */
    private static void ensureProbeClassesLoaded() {
        tryLoadProbe("Aetherworks", () -> AetherworksReflection.anvilBEClass);
        tryLoadProbe("Backpack", () -> BackpackReflection.backpackBEClass);
        tryLoadProbe("CrabbersDelight", () -> CrabbersDelightReflection.crabTrapBEClass);
        tryLoadProbe("CrockPot", () -> CrockPotReflection.crockPotBEClass);
        tryLoadProbe("DistantWorlds", () -> DistantWorldsReflection.lithumCoreBlockClass);
        tryLoadProbe("Eidolon", () -> EidolonReflection.crucibleTileEntityClass);
        tryLoadProbe("Embers", () -> EmbersReflection.alchemyTabletBEClass);
        tryLoadProbe("FA", () -> FAReflection.hephaestusForgeBEClass);
        tryLoadProbe("FarmersDelight", () -> FarmersDelightReflection.cookingPotBEClass);
        tryLoadProbe("FarmingForBlockheads", () -> FarmingForBlockheadsReflection.marketBEClass);
        tryLoadProbe("FR", () -> FRReflection.kettleBEClass);
        tryLoadProbe("Goety", () -> GoetyReflection.darkAltarBEClass);
        tryLoadProbe("ImmersalsDelight", () -> ImmersalsDelightReflection.enchantalCoolerBEClass);
        tryLoadProbe("JEI", () -> JEIReflection.ingredientListOverlayClass);
        tryLoadProbe("Malum", () -> MalumReflection.spiritAltarBEClass);
        tryLoadProbe("TLM", () -> TLMReflection.altarBEClass);
        tryLoadProbe("WR", () -> WRReflection.arcaneWorkbenchBEClass);
        tryLoadProbe("YHK", () -> YHKReflection.cookingBEClass);
    }

    private static void tryLoadProbe(String probeName, java.util.function.Supplier<Object> fieldAccess) {
        try {
            Object o = fieldAccess.get();
            // suppress "unused" warning — the read above is the whole point
            if (o == null) { /* probe fields start null; contracts populate them */ }
        } catch (ExceptionInInitializerError e) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-Contract] Failed to load probe {}: initialization error (likely field name typo)",
                    probeName, e.getCause() != null ? e.getCause() : e);
        } catch (Throwable t) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-Contract] Failed to load probe {}: unexpected error",
                    probeName, t);
        }
    }

    private static Field resolveField(Class<?> clazz,
                                       ReflectionContract.FieldContract fc)
            throws NoSuchFieldException {
        if (fc.origin() == ReflectionContract.MemberOrigin.VANILLA) {
            return ObfuscationReflectionHelper.findField(clazz, fc.name());
        }
        return clazz.getDeclaredField(fc.name());
    }

    private static Method resolveMethod(Class<?> clazz,
                                        ReflectionContract.MethodContract mc)
            throws NoSuchMethodException {
        if (mc.origin() == ReflectionContract.MemberOrigin.VANILLA) {
            return ObfuscationReflectionHelper.findMethod(clazz, mc.name(), mc.parameterTypes());
        }
        return clazz.getDeclaredMethod(mc.name(), mc.parameterTypes());
    }

    /** Number of registered contracts (for diagnostics). */
    public static int size() {
        return CONTRACTS.size();
    }
}
