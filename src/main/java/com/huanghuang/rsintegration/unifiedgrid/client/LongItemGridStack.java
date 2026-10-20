package com.huanghuang.rsintegration.unifiedgrid.client;

import com.huanghuang.rsintegration.crafting.quantity.CompactQuantityFormat;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.refinedmods.refinedstorage.api.storage.tracker.StorageTrackerEntry;
import com.refinedmods.refinedstorage.render.RenderSettings;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.ItemGridStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.UUID;

/** RS 物品条目仍以 int 作为接口，RSI 条目在内部保留完整 long 数量。 */
public final class LongItemGridStack extends ItemGridStack {
    private long longAmount;
    private boolean zeroed;

    public LongItemGridStack(UUID id, UUID otherId, ItemStack template, boolean craftable,
                             StorageTrackerEntry tracker, long amount) {
        super(id, otherId, template.copyWithCount(1), craftable, tracker);
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
        return zeroed ? "0" : String.format(Locale.ROOT, "%,d", getLongQuantity());
    }

    @Override
    public void draw(GuiGraphics graphics, BaseScreen<?> screen, int x, int y) {
        String text = null;
        int color = RenderSettings.INSTANCE.getSecondaryColor();
        if (zeroed) {
            text = "0";
            color = 0xFF5555;
        } else if (isCraftable()) {
            text = I18n.get("gui.refinedstorage.grid.craft");
        } else if (getLongQuantity() > 1L) {
            text = CompactQuantityFormat.format(getLongQuantity());
        }
        screen.renderItem(graphics, x, y, getStack().copyWithCount(1), true, text, color);
    }
}
