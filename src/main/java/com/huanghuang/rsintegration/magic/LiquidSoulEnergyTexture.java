package com.huanghuang.rsintegration.magic;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

/** RS 混合终端与 BD 终端共用的液态灵魂能量贴图。 */
public final class LiquidSoulEnergyTexture {
    public static final ResourceLocation ID =
            new ResourceLocation("rs_integration", "block/liquid_soul_energy");
    private static final Material MATERIAL = new Material(InventoryMenu.BLOCK_ATLAS, ID);

    private LiquidSoulEnergyTexture() {}

    public static void render(GuiGraphics gui, int x, int y) {
        TextureAtlasSprite sprite = MATERIAL.sprite();
        if (sprite == null || sprite.atlasLocation().equals(MissingTextureAtlasSprite.getLocation())) return;
        gui.blit(x, y, 0, 16, 16, sprite);
    }
}
