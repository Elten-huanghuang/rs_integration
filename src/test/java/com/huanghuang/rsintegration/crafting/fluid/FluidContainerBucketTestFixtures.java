package com.huanghuang.rsintegration.crafting.fluid;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;

import java.lang.reflect.Method;

final class FluidContainerBucketTestFixtures {
    private FluidContainerBucketTestFixtures() {}

    record Buckets(Item empty, Item filled) {}

    static synchronized Buckets buckets(String name) {
        ResourceLocation emptyId = new ResourceLocation("rs_integration_test", name + "_bucket");
        ResourceLocation filledId = new ResourceLocation("rs_integration_test", name + "_water_bucket");
        if (ForgeRegistries.ITEMS.containsKey(emptyId)) {
            return new Buckets(ForgeRegistries.ITEMS.getValue(emptyId), ForgeRegistries.ITEMS.getValue(filledId));
        }
        ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
        boolean locked = registry.isLocked();
        registry.unfreeze();
        try {
            Method unfreeze = BuiltInRegistries.ITEM.getClass().getMethod("unfreeze");
            unfreeze.setAccessible(true);
            unfreeze.invoke(BuiltInRegistries.ITEM);
            Item empty = new BucketItem(() -> Fluids.EMPTY, new Item.Properties());
            Item filled = new BucketItem(() -> Fluids.WATER,
                    new Item.Properties().stacksTo(1).craftRemainder(empty));
            registry.register(emptyId, empty);
            registry.register(filledId, filled);
            return new Buckets(empty, filled);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法注册测试桶", failure);
        } finally {
            BuiltInRegistries.ITEM.freeze();
            if (locked) registry.freeze();
        }
    }

    static IFluidHandlerItem handler(ItemStack stack) {
        return stack.getItem() instanceof BucketItem ? new FluidBucketWrapper(stack) : null;
    }
}
