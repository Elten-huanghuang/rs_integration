package com.huanghuang.rsintegration.mixin.aetherworks;

import com.huanghuang.rsintegration.mixin.plugin.RSIntegrationMixinPlugin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.nbt.CompoundTag;
import net.sirplop.aetherworks.recipe.MetalFormerRecipe;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class MetalFormerRecipeMixinTest {
    private static final Operation<Boolean> ORIGINAL_EQUALS = args -> args[0].equals(args[1]);

    @Test
    void untaggedExactInputsMatchWithoutCallingEqualsOnNull() throws Exception {
        assertTrue(compareInputTags(null, null));
        assertFalse(compareInputTags(null, new CompoundTag()));
    }

    @Test
    void taggedInputsKeepNativeExactEquality() throws Exception {
        CompoundTag expected = new CompoundTag();
        expected.putInt("gem_color", 7);
        assertTrue(compareInputTags(expected, expected.copy()));
        assertFalse(compareInputTags(expected, null));
        assertFalse(compareInputTags(expected, new CompoundTag()));
    }

    @Test
    void staticInjectionHandlerMeetsMixinLoadingVisibilityRules() throws Exception {
        Method handler = inputTagHandler();
        // Mixin 0.8.5 在加载阶段拒绝任何非 private 的静态注入方法。
        assertTrue(Modifier.isStatic(handler.getModifiers()));
        assertTrue(Modifier.isPrivate(handler.getModifiers()));
    }

    private static Method inputTagHandler() throws NoSuchMethodException {
        return MetalFormerRecipeMixin.class.getDeclaredMethod("rsi$compareNullableInputTag",
                CompoundTag.class, Object.class, Operation.class);
    }

    private static boolean compareInputTags(CompoundTag inputTag, Object actualTag) throws Exception {
        Method handler = inputTagHandler();
        handler.setAccessible(true);
        return (boolean) handler.invoke(null, inputTag, actualTag, ORIGINAL_EQUALS);
    }

    @Test
    void suppliedJarContainsTheWrappedInvocationAndAbsentModIsSkipped() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream source = MetalFormerRecipe.class.getResourceAsStream("MetalFormerRecipe.class")) {
            assertNotNull(source);
            new ClassReader(source).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        MethodNode matches = node.methods.stream().filter(method -> method.name.equals("matches")
                && method.desc.equals("(Lnet/sirplop/aetherworks/recipe/MetalFormerContext;Lnet/minecraft/world/level/Level;)Z"))
                .findFirst().orElseThrow();
        int sites = 0;
        for (AbstractInsnNode instruction : matches.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/nbt/CompoundTag")
                    && call.name.equals("equals") && call.desc.equals("(Ljava/lang/Object;)Z")) sites++;
        }
        assertEquals(1, sites);
        RSIntegrationMixinPlugin plugin = new RSIntegrationMixinPlugin();
        assertTrue(plugin.shouldApplyMixin(MetalFormerRecipe.class.getName(), MetalFormerRecipeMixin.class.getName()));
        assertFalse(plugin.shouldApplyMixin("absent.aetherworks.MetalFormerRecipe", MetalFormerRecipeMixin.class.getName()));
    }
}
