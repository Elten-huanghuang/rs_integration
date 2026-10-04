package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.client.config.JeiConfigButton;
import com.huanghuang.rsintegration.compat.jei.JeiConfigButtonPlacement;
import com.huanghuang.rsintegration.compat.jei.JeiConfigButtonPlacement.Position;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.handlers.CombinedInputHandler;
import mezz.jei.gui.overlay.ScreenPropertiesCache;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import mezz.jei.gui.overlay.ingredients.IngredientGridWithNavigation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** JEI 15.49 将 IngredientGridWithNavigation 移到了 overlay.ingredients 包。 */
@Mixin(value = BookmarkOverlay.class, remap = false)
public abstract class BookmarkOverlayConfigButtonV15Mixin {
    @Shadow @Final private IngredientGridWithNavigation contents;
    @Shadow @Final private ScreenPropertiesCache screenPropertiesCache;
    @Unique private final JeiConfigButton rsi$configButton = new JeiConfigButton();

    @Inject(method = "getDisplayArea", at = @At("RETURN"), cancellable = true, require = 0)
    private static void rsi$reserveConfigButtonRow(IGuiProperties properties, CallbackInfoReturnable<ImmutableRect2i> cir) {
        if (JeiConfigButtonPlacement.needsExtraRow(properties.getGuiLeft(), ModList.get().isLoaded("extendedae_plus"))) {
            ImmutableRect2i area = cir.getReturnValue();
            cir.setReturnValue(area.cropBottom(Math.min(22, area.height())));
        }
    }

    @Inject(method = "updateBounds", at = @At("TAIL"), require = 0)
    private void rsi$updateConfigButtonBounds(IGuiProperties properties, CallbackInfo ci) {
        int bookmarkX = contents.hasRoom() ? contents.getBackgroundArea().x() : 6;
        Position position = JeiConfigButtonPlacement.position(bookmarkX, properties.getGuiLeft(),
                properties.getScreenHeight(), ModList.get().isLoaded("extendedae_plus"));
        rsi$configButton.updateBounds(position.x(), position.y());
    }

    @Inject(method = "drawScreen", at = @At("HEAD"), require = 0)
    private void rsi$beginConfigButtonFrame(Minecraft minecraft, GuiGraphics graphics,
                                            int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        rsi$configButton.beginFrame();
    }

    @Inject(method = "drawScreen", at = @At("TAIL"), require = 0)
    private void rsi$drawConfigButton(Minecraft minecraft, GuiGraphics graphics,
                                     int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (screenPropertiesCache.hasValidScreen()) rsi$configButton.draw(graphics, mouseX, mouseY);
    }

    @Inject(method = "drawTooltips", at = @At("TAIL"), require = 0)
    private void rsi$drawConfigButtonTooltip(Minecraft minecraft, GuiGraphics graphics,
                                            int mouseX, int mouseY, CallbackInfo ci) {
        if (screenPropertiesCache.hasValidScreen()) rsi$configButton.drawTooltip(graphics, mouseX, mouseY);
    }

    @Inject(method = "createInputHandler", at = @At("RETURN"), cancellable = true, require = 0)
    private void rsi$appendConfigButtonInput(CallbackInfoReturnable<IUserInputHandler> cir) {
        cir.setReturnValue(new CombinedInputHandler("RSIntegrationConfigButton", rsi$configButton, cir.getReturnValue()));
    }
}
