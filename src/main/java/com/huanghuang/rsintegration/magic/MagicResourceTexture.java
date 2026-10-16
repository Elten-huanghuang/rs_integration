package com.huanghuang.rsintegration.magic;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

/** RS 混合终端按资源种类取用的魔法资源贴图。 */
public final class MagicResourceTexture {
    private static final Material MANA = material("botania", "block/mana_pool_inside");
    private static final Material SOURCE = material("ars_nouveau", "block/source_jar");

    private MagicResourceTexture() {}

    public static ResourceLocation of(MagicResourceKind kind) {
        return switch (kind) {
            case MANA -> MANA.texture();
            case SOURCE -> SOURCE.texture();
            case SOUL -> LiquidSoulEnergyTexture.ID;
        };
    }

    public static void render(GuiGraphics gui, MagicResourceKind kind, int x, int y) {
        if (kind == MagicResourceKind.SOUL) {
            LiquidSoulEnergyTexture.render(gui, x, y);
            return;
        }
        TextureAtlasSprite sprite = (kind == MagicResourceKind.MANA ? MANA : SOURCE).sprite();
        if (sprite == null || sprite.atlasLocation().equals(MissingTextureAtlasSprite.getLocation())) return;
        gui.blit(x, y, 0, 16, 16, sprite);
    }

    private static Material material(String namespace, String path) {
        return new Material(InventoryMenu.BLOCK_ATLAS, new ResourceLocation(namespace, path));
    }
}
