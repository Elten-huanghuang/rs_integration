package com.huanghuang.rsintegration.testutil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladePlanning;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.stream.Stream;

/** 用同一种原版物品模拟共享 item ID 的刀；不需要启动第三方模组或能力系统。 */
public final class SlashBladeFixtures {
    private SlashBladeFixtures() {}

    public static Ingredient ingredient(String name, int kills, int refine) {
        JsonObject request = new JsonObject();
        request.addProperty("name", name);
        request.addProperty("kill", kills);
        request.addProperty("refine", refine);
        return ingredient(request);
    }

    public static Ingredient ingredient(JsonObject request) {
        String name = request.has("name") ? request.get("name").getAsString() : "slashblade:none";
        int kills = request.has("kill") ? request.get("kill").getAsInt() : 0;
        int refine = request.has("refine") ? request.get("refine").getAsInt() : 0;
        ItemStack display = blade(name, kills, refine);
        if (request.has("proud_soul")) display.getTag().getCompound("bladeState")
                .putInt("proudSoul", request.get("proud_soul").getAsInt());
        JsonObject json = new JsonObject();
        json.addProperty("type", "slashblade:blade");
        json.addProperty("item", "minecraft:iron_sword");
        json.add("request", request.deepCopy());
        return new Ingredient(Stream.of(new Ingredient.ItemValue(display))) {
            @Override
            public JsonElement toJson() { return json.deepCopy(); }

            @Override
            public boolean test(ItemStack actual) {
                return actual != null && actual.is(Items.IRON_SWORD)
                        && SlashBladePlanning.matches(SlashBladePlanning.requirement(this), actual.getTag());
            }
        };
    }

    public static ItemStack blade(String name, int kills, int refine) {
        ItemStack stack = new ItemStack(Items.IRON_SWORD);
        CompoundTag state = new CompoundTag();
        state.putString("translationKey", "slashblade:none".equals(name)
                ? "" : Util.makeDescriptionId("item", new ResourceLocation(name)));
        state.putInt("killCount", kills);
        state.putInt("RepairCounter", refine);
        state.putInt("proudSoul", 0);
        state.putString("ModelName", "display/" + name);
        stack.getOrCreateTag().put("bladeState", state);
        return stack;
    }
}
