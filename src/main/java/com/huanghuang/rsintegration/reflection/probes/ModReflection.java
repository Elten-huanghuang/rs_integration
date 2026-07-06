package com.huanghuang.rsintegration.reflection.probes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a reflection-probe class that caches resolved {@code Class<?>},
 * {@code Field}, and {@code Method} handles for a specific mod.
 *
 * <p>Probe classes register {@link com.huanghuang.rsintegration.reflection.contract.ReflectionContract}
 * entries in their static initializer.  {@code ContractValidation} fills
 * the static fields at startup, turning the runtime path into a simple
 * field read with zero locking.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ModReflection {
    /** ModIds constant, e.g. {@code "forbidden_arcanus"}. */
    String modId();

    /** Human-readable description of the mod subsystem. */
    String description() default "";
}
