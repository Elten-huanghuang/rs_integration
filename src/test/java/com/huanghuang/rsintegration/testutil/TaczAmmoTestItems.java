package com.huanghuang.rsintegration.testutil;

import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.item.AmmoItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/** 使用 TaCZ 真实弹药接口，避免测试依赖枪包资源注册。 */
public final class TaczAmmoTestItems {

    private TaczAmmoTestItems() {}

    public static ItemStack ammo(String id, int count) {
        ItemStack stack = new ItemStack(registerItem("tacz_ammo", TestAmmoItem::new), count);
        stack.getOrCreateTag().putString("AmmoId", id);
        return stack;
    }

    public static ItemStack nativeAmmo(String id, int count) {
        ItemStack stack = new ItemStack(registerItem("native_tacz_ammo", AmmoItem::new), count);
        stack.getOrCreateTag().putString("AmmoId", id);
        return stack;
    }

    public static ItemStack gun(String ammoId) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.getOrCreateTag().putString("AmmoId", ammoId);
        return stack;
    }

    private static synchronized Item registerItem(String name, Supplier<Item> factory) {
        ResourceLocation id = new ResourceLocation("rs_integration_test", name);
        if (ForgeRegistries.ITEMS.containsKey(id)) return ForgeRegistries.ITEMS.getValue(id);
        ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
        boolean locked = registry.isLocked();
        registry.unfreeze();
        try {
            Method unfreeze = BuiltInRegistries.ITEM.getClass().getMethod("unfreeze");
            unfreeze.setAccessible(true);
            unfreeze.invoke(BuiltInRegistries.ITEM);
            Item item = factory.get();
            registry.register(id, item);
            return item;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法注册测试弹药", failure);
        } finally {
            BuiltInRegistries.ITEM.freeze();
            if (locked) registry.freeze();
        }
    }

    private static final class TestAmmoItem extends Item implements IAmmo {
        private TestAmmoItem() { super(new Properties().stacksTo(64)); }

        @Override
        public ResourceLocation getAmmoId(ItemStack stack) {
            return new ResourceLocation(stack.getOrCreateTag().getString("AmmoId"));
        }

        @Override
        public void setAmmoId(ItemStack stack, ResourceLocation id) {
            stack.getOrCreateTag().putString("AmmoId", id.toString());
        }

        @Override
        public boolean isAmmoOfGun(ItemStack gun, ItemStack ammo) {
            return getAmmoId(gun).equals(getAmmoId(ammo));
        }
    }
}
