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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        TARGET_FIELDS.put(contractDescription, targetField);
    }

    /**
     * Validate all registered contracts and populate probe-class static fields.
     * Called once from {@code RSIntegrationMod.onCommonSetup()}.
     */
    public static void validateAll() {
        ensureProbeClassesLoaded();
        ClassLoader cl = ContractValidation.class.getClassLoader();
        int ok = 0, failed = 0, skipped = 0;

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
                    Method m = clazz.getDeclaredMethod(mc.name(), mc.parameterTypes());
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
            } catch (Exception e) {
                String msg = "[RSI-Contract] {} 反射契约失败: {} — {}";
                if (c.required()) {
                    failed++;
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
     */
    private static void ensureProbeClassesLoaded() {
        Object o;
        o = AetherworksReflection.anvilBEClass;
        o = BackpackReflection.backpackBEClass;
        o = CrabbersDelightReflection.crabTrapBEClass;
        o = CrockPotReflection.crockPotBEClass;
        o = DistantWorldsReflection.lithumCoreBlockClass;
        o = EidolonReflection.crucibleTileEntityClass;
        o = EmbersReflection.alchemyTabletBEClass;
        o = FAReflection.hephaestusForgeBEClass;
        o = FarmersDelightReflection.cookingPotBEClass;
        o = FarmingForBlockheadsReflection.marketBEClass;
        o = FRReflection.kettleBEClass;
        o = GoetyReflection.darkAltarBEClass;
        o = ImmersalsDelightReflection.enchantalCoolerBEClass;
        o = JEIReflection.ingredientListOverlayClass;
        o = MalumReflection.spiritAltarBEClass;
        o = TLMReflection.altarBEClass;
        o = WRReflection.arcaneWorkbenchBEClass;
        o = YHKReflection.cookingBEClass;
        // suppress "unused" warning — the reads above are the whole point
        if (o == null) { /* probe fields start null; contracts populate them */ }
    }

    private static Field resolveField(Class<?> clazz,
                                       ReflectionContract.FieldContract fc)
            throws NoSuchFieldException {
        if (fc.origin() == ReflectionContract.MemberOrigin.VANILLA) {
            return ObfuscationReflectionHelper.findField(clazz, fc.name());
        }
        return clazz.getDeclaredField(fc.name());
    }

    /** Number of registered contracts (for diagnostics). */
    public static int size() {
        return CONTRACTS.size();
    }
}
