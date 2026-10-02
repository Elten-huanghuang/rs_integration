package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class IronAlchemistJarContractTest {
    private ZipFile installedJar() throws Exception {
        String path = System.getenv("RSI_ALCHEMIST_COMPAT_JAR");
        assumeTrue(path != null, "set RSI_ALCHEMIST_COMPAT_JAR to verify the installed cauldron");
        return new ZipFile(path);
    }

    @Test
    void nativeBottlingRecipesUseTheSameFiveFluidIdsAnd250MbCosts() throws Exception {
        try (ZipFile jar = installedJar()) {
            for (String ink : List.of("common_ink", "uncommon_ink", "rare_ink", "epic_ink", "legendary_ink")) {
                var entry = jar.getEntry("data/irons_spellbooks/recipes/alchemist_cauldron/empty_" + ink + ".json");
                assertNotNull(entry);
                try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                    JsonObject recipe = JsonParser.parseReader(reader).getAsJsonObject();
                    assertEquals("minecraft:glass_bottle", recipe.getAsJsonObject("input").get("item").getAsString());
                    assertEquals(InkFluidSupport.BOTTLE_AMOUNT, recipe.getAsJsonObject("fluid").get("Amount").getAsInt());
                    assertEquals("irons_spellbooks:" + ink, recipe.getAsJsonObject("fluid").get("FluidName").getAsString());
                    assertEquals("irons_spellbooks:" + ink, recipe.getAsJsonObject("result").get("item").getAsString());
                    assertEquals(1, recipe.getAsJsonObject("result").get("count").getAsInt());
                }
            }
        }
    }

    @Test
    void scrollMeltingAndFluidCapabilityApisRemainAvailable() throws Exception {
        try (ZipFile jar = installedJar()) {
            ClassNode cauldron = readClass(jar,
                    "io/redspace/ironsspellbooks/block/alchemist_cauldron/AlchemistCauldronTile.class");
            MethodNode melt = cauldron.methods.stream().filter(method -> method.name.equals("tryMeltInput")
                    && method.desc.equals("(Lnet/minecraft/world/item/ItemStack;)V")).findFirst().orElseThrow();
            assertTrue((melt.access & Opcodes.ACC_PUBLIC) != 0);
            assertTrue(cauldron.methods.stream().anyMatch(method -> method.name.equals("getInkFromScroll")
                    && (method.access & Opcodes.ACC_STATIC) != 0));
            assertTrue(cauldron.methods.stream().anyMatch(method -> method.name.equals("getCapability")));
            boolean bottleAmount = false;
            boolean configuredChance = false;
            for (AbstractInsnNode instruction : melt.instructions) {
                if (instruction instanceof IntInsnNode constant && constant.operand == InkFluidSupport.BOTTLE_AMOUNT) bottleAmount = true;
                if (instruction instanceof FieldInsnNode field && field.name.equals("SCROLL_RECYCLE_CHANCE")) configuredChance = true;
            }
            assertTrue(bottleAmount);
            assertTrue(configuredChance);
        }
    }

    @Test
    void nativeJeiScrollWrapperKeepsTheAccessorContract() throws Exception {
        try (ZipFile jar = installedJar()) {
            ClassNode wrapper = readClass(jar,
                    "io/redspace/ironsspellbooks/jei/AlchemistCauldronJeiRecipe.class");
            for (String accessor : List.of("itemIn", "fluidIn", "results", "resultByproduct")) {
                assertTrue(wrapper.methods.stream().anyMatch(method -> method.name.equals(accessor)
                        && method.desc.startsWith("()") && (method.access & Opcodes.ACC_PUBLIC) != 0), accessor);
            }
        }
    }

    private ClassNode readClass(ZipFile jar, String path) throws Exception {
        try (var input = jar.getInputStream(jar.getEntry(path))) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
