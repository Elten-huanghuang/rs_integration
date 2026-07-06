package com.huanghuang.rsintegration.reflection.contract;

import javax.annotation.Nullable;

/**
 * Declares a reflection dependency that must be validated at startup.
 *
 * <p>Classes are probed with {@code Class.forName(name, false, classLoader)}
 * to skip static initializers.  Field and method contracts specify exact
 * signatures, preventing overload mismatches when the target mod updates.</p>
 */
public record ReflectionContract(
    String modId,
    String description,
    String className,
    @Nullable String minVersion,
    @Nullable String maxVersion,
    boolean required,
    FieldContract[] fields,
    MethodContract[] methods
) {
    /** Convenience: only verify the class exists. */
    public ReflectionContract(String modId, String description,
                              String className, boolean required) {
        this(modId, description, className, null, null, required,
             new FieldContract[0], new MethodContract[0]);
    }

    /** Convenience: class + fields + methods, all versions. */
    public ReflectionContract(String modId, String description,
                              String className, boolean required,
                              FieldContract[] fields, MethodContract[] methods) {
        this(modId, description, className, null, null, required, fields, methods);
    }

    /** Where a field or method originated — affects obfuscation handling. */
    public enum MemberOrigin {
        /** Mod's own API — safe to use standard reflection by name. */
        MOD,
        /** Inherited from vanilla Minecraft — must use {@code ObfuscationReflectionHelper}. */
        VANILLA
    }

    /** Verifies a field exists, with optional type check and obfuscation origin. */
    public record FieldContract(String name, @Nullable Class<?> expectedType,
                                MemberOrigin origin) {
        public FieldContract(String name) {
            this(name, null, MemberOrigin.MOD);
        }

        public FieldContract(String name, Class<?> expectedType) {
            this(name, expectedType, MemberOrigin.MOD);
        }
    }

    /** Verifies a method exists with an exact parameter-type signature. */
    public record MethodContract(String name, Class<?>... parameterTypes) {}
}
