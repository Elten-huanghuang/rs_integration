package com.huanghuang.rsintegration.magic;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * RS 混合终端按资源种类取用的魔法资源贴图。
 *
 * <p>图源与 Beyond Dimensions 的终端一致：BD 自己并不画贴图，它的
 * {@code ManaStackKeyRender}/{@code SourceStackKeyRender} 直接取用两个模组本体的
 * <b>流动</b>贴图——{@code botania:block/mana_water}（16×512，32 帧）与
 * {@code ars_nouveau:block/mana_still}（16×320，20 帧）。这里照搬同样的来源，只是把
 * PNG 与 {@code .mcmeta} 拷进自己的资源包，免得模组改路径或没装时图标变紫黑格。</p>
 *
 * <p>注意不能用静态方块贴图代替：{@code ars_nouveau:block/source_jar} 那种是方块外观，
 * 放到终端格子里既不成图标也不动。动画由 {@link GuiGraphics#blit} 的贴图重载按当前帧
 * 取 UV，无需逐帧绘制。</p>
 *
 * <p>灵魂本来就走自带贴图，现在三种资源口径一致，灵魂也走同一个 {@link #render} 分支。</p>
 */
public final class MagicResourceTexture {
    private static final ResourceLocation MANA = id("magic/mana");
    private static final ResourceLocation SOURCE = id("magic/source");

    private MagicResourceTexture() {}

    /** 该资源使用的图集贴图；灵魂是独立资源，由 {@link LiquidSoulEnergyTexture} 提供。 */
    public static ResourceLocation of(MagicResourceKind kind) {
        return switch (kind) {
            case MANA -> MANA;
            case SOURCE -> SOURCE;
            case SOUL -> LiquidSoulEnergyTexture.ID;
        };
    }

    public static void render(GuiGraphics gui, MagicResourceKind kind, int x, int y) {
        if (kind == MagicResourceKind.SOUL) {
            LiquidSoulEnergyTexture.render(gui, x, y);
            return;
        }
        TextureAtlasSprite sprite = sprite(kind);
        if (sprite == null || sprite.atlasLocation().equals(MissingTextureAtlasSprite.getLocation())) return;
        gui.blit(x, y, 0, 16, 16, sprite);
    }

    private static TextureAtlasSprite sprite(MagicResourceKind kind) {
        return new Material(InventoryMenu.BLOCK_ATLAS, of(kind)).sprite();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("rs_integration", path);
    }
}
