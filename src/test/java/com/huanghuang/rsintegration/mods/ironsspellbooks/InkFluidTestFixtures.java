package com.huanghuang.rsintegration.mods.ironsspellbooks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.EmptyFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import java.lang.reflect.Method;

final class InkFluidTestFixtures {
    private static final ResourceLocation ID = new ResourceLocation("rs_integration_test", "ink_fluid");

    private InkFluidTestFixtures() {}

    static synchronized InkFluidItem tokenItem() {
        Item existing = ForgeRegistries.ITEMS.getValue(ID);
        if (existing instanceof InkFluidItem token) return token;
        ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
        boolean locked = registry.isLocked();
        registry.unfreeze();
        try {
            Method unfreeze = BuiltInRegistries.ITEM.getClass().getMethod("unfreeze");
            unfreeze.setAccessible(true);
            unfreeze.invoke(BuiltInRegistries.ITEM);
            InkFluidItem token = new InkFluidItem();
            registry.register(ID, token);
            return token;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法注册测试流体物品", failure);
        } finally {
            BuiltInRegistries.ITEM.freeze();
            if (locked) registry.freeze();
        }
    }

    static synchronized FluidStack ink(String name, int amount) {
        ResourceLocation id = new ResourceLocation("irons_spellbooks", name);
        if (!ForgeRegistries.FLUIDS.containsKey(id)) {
            ForgeRegistry<Fluid> registry = (ForgeRegistry<Fluid>) ForgeRegistries.FLUIDS;
            boolean locked = registry.isLocked();
            registry.unfreeze();
            try {
                Method unfreeze = BuiltInRegistries.FLUID.getClass().getMethod("unfreeze");
                unfreeze.setAccessible(true);
                unfreeze.invoke(BuiltInRegistries.FLUID);
                registry.register(id, new EmptyFluid());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("无法注册测试墨水流体", failure);
            } finally {
                BuiltInRegistries.FLUID.freeze();
                if (locked) registry.freeze();
            }
        }
        if (!ForgeRegistries.ITEMS.containsKey(id)) {
            ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
            boolean locked = registry.isLocked();
            registry.unfreeze();
            try {
                Method unfreeze = BuiltInRegistries.ITEM.getClass().getMethod("unfreeze");
                unfreeze.setAccessible(true);
                unfreeze.invoke(BuiltInRegistries.ITEM);
                registry.register(id, new Item(new Item.Properties()));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("无法注册测试墨水物品", failure);
            } finally {
                BuiltInRegistries.ITEM.freeze();
                if (locked) registry.freeze();
            }
        }
        return new FluidStack(ForgeRegistries.FLUIDS.getValue(id), amount);
    }
}
