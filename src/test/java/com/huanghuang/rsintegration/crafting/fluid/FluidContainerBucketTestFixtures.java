package com.huanghuang.rsintegration.crafting.fluid;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
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
        // 与 Forge 默认能力一致，桶子类需要自行提供能力。
        return stack.getItem().getClass() == BucketItem.class ? new FluidBucketWrapper(stack) : null;
    }

    static synchronized Buckets subclassBuckets(String name) {
        ResourceLocation fluidId = new ResourceLocation("rs_integration_test", name);
        ResourceLocation filledId = new ResourceLocation("rs_integration_test", name + "_bucket");
        if (ForgeRegistries.ITEMS.containsKey(filledId)) {
            return new Buckets(Items.BUCKET, ForgeRegistries.ITEMS.getValue(filledId));
        }
        ForgeRegistry<Fluid> fluids = (ForgeRegistry<Fluid>) ForgeRegistries.FLUIDS;
        ForgeRegistry<Item> items = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
        boolean fluidsLocked = fluids.isLocked();
        boolean itemsLocked = items.isLocked();
        fluids.unfreeze();
        items.unfreeze();
        try {
            for (var registry : new Object[] {BuiltInRegistries.FLUID, BuiltInRegistries.ITEM}) {
                Method unfreeze = registry.getClass().getMethod("unfreeze");
                unfreeze.setAccessible(true);
                unfreeze.invoke(registry);
            }
            FluidType type = new FluidType(FluidType.Properties.create());
            Fluid fluid = new ForgeFlowingFluid.Source(new ForgeFlowingFluid.Properties(() -> type,
                    () -> ForgeRegistries.FLUIDS.getValue(fluidId),
                    () -> ForgeRegistries.FLUIDS.getValue(fluidId))
                    .bucket(() -> ForgeRegistries.ITEMS.getValue(filledId)));
            fluids.register(fluidId, fluid);
            Item filled = new BucketItem(() -> fluid,
                    new Item.Properties().stacksTo(1).craftRemainder(Items.BUCKET)) {};
            items.register(filledId, filled);
            return new Buckets(Items.BUCKET, filled);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法注册测试桶子类及流体", failure);
        } finally {
            BuiltInRegistries.FLUID.freeze();
            BuiltInRegistries.ITEM.freeze();
            if (fluidsLocked) fluids.freeze();
            if (itemsLocked) items.freeze();
        }
    }
}
