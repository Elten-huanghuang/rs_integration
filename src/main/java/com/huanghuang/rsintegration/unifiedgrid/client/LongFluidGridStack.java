package com.huanghuang.rsintegration.unifiedgrid.client;

import com.huanghuang.rsintegration.crafting.quantity.CompactQuantityFormat;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.refinedmods.refinedstorage.api.storage.tracker.StorageTrackerEntry;
import com.refinedmods.refinedstorage.render.FluidRenderer;
import com.refinedmods.refinedstorage.render.RenderSettings;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.FluidGridStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraftforge.fluids.FluidStack;

import java.util.Locale;
import java.util.UUID;

/** RS 流体条目仍以 int 作为接口，RSI 条目在内部保留完整 long 数量。 */
public final class LongFluidGridStack extends FluidGridStack {
    private long longAmount;
    private boolean zeroed;

    public LongFluidGridStack(UUID id, UUID otherId, FluidStack template,
                              StorageTrackerEntry tracker, boolean craftable, long amount) {
        super(id, otherId, copyWithAmount(template, 1), tracker, craftable);
        this.longAmount = Math.max(0L, amount);
    }

    public long getLongQuantity() {
        return longAmount;
    }

    public void setLongQuantity(long amount) {
        longAmount = Math.max(0L, amount);
        zeroed = longAmount == 0L;
    }

    @Override
    public int getQuantity() {
        return SaturatingLongMath.clampToInt(getLongQuantity());
    }

    @Override
    public void setQuantity(int quantity) {
        setLongQuantity(quantity);
    }

    @Override
    public void setZeroed(boolean zeroed) {
        this.zeroed = zeroed;
    }

    @Override
    public String getFormattedFullQuantity() {
        return zeroed ? "0 mB"
                : String.format(Locale.ROOT, "%,d mB", getLongQuantity());
    }

    @Override
    public void draw(GuiGraphics graphics, BaseScreen<?> screen, int x, int y) {
        FluidRenderer.INSTANCE.render(graphics, x, y, getStack());
        String text;
        int color = RenderSettings.INSTANCE.getSecondaryColor();
        if (zeroed) {
            text = "0";
            color = 0xFF5555;
        } else if (isCraftable()) {
            text = I18n.get("gui.refinedstorage.grid.craft");
        } else {
            text = CompactQuantityFormat.format(getLongQuantity());
        }
        screen.renderQuantity(graphics, x, y, text, color);
    }

    private static FluidStack copyWithAmount(FluidStack source, int amount) {
        FluidStack copy = source.copy();
        copy.setAmount(amount);
        return copy;
    }
}
