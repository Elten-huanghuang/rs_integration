package com.huanghuang.rsintegration.testutil;

import com.huanghuang.rsintegration.feature.experience.ResonantExperienceWandItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;

import java.lang.reflect.Method;

/** 注册真实经验杖，测试物品身份、磁盘存取及 NBT 序列化。 */
public final class ExperienceWandTestItems {
    private ExperienceWandTestItems() {}

    public static synchronized ItemStack wand() {
        ResourceLocation id = new ResourceLocation("rs_integration_test", "experience_wand");
        if (ForgeRegistries.ITEMS.containsKey(id)) {
            return new ItemStack(ForgeRegistries.ITEMS.getValue(id));
        }
        ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
        boolean locked = registry.isLocked();
        registry.unfreeze();
        try {
            Method unfreeze = BuiltInRegistries.ITEM.getClass().getMethod("unfreeze");
            unfreeze.setAccessible(true);
            unfreeze.invoke(BuiltInRegistries.ITEM);
            Item item = new ResonantExperienceWandItem();
            registry.register(id, item);
            return new ItemStack(item);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法注册测试经验杖", failure);
        } finally {
            BuiltInRegistries.ITEM.freeze();
            if (locked) registry.freeze();
        }
    }
}
