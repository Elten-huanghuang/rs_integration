package com.huanghuang.rsintegration.mods.ironsspellbooks.client;

import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

/** 配方树与材料清单使用流体贴图和颜色。 */
public final class InkFluidRenderer {
    private InkFluidRenderer() {}
    public static void render(GuiGraphics graphics, ItemStack stack, int x, int y) {
        if (!InkFluidSupport.isToken(stack)) { graphics.renderItem(stack, x, y); return; }
        var fluid = InkFluidSupport.fluid(stack);
        if (fluid.isEmpty()) return;
        var extension = IClientFluidTypeExtensions.of(fluid.getFluid());
        var texture = extension.getStillTexture(fluid);
        if (texture == null) return;
        var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
        int color = extension.getTintColor(fluid);
        graphics.setColor(((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f,
                (color & 255) / 255f, ((color >> 24) & 255) / 255f);
        graphics.blit(x, y, 0, 16, 16, sprite);
        graphics.setColor(1, 1, 1, 1);
    }
    public static String quantity(ItemStack stack, int amount) {
        return InkFluidSupport.isToken(stack) ? amount + " mB" : "×" + amount;
    }
}
