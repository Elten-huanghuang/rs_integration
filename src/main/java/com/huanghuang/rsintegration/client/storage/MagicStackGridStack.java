package com.huanghuang.rsintegration.client.storage;

import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceStack;

import com.huanghuang.rsintegration.crafting.quantity.CompactQuantityFormat;
import com.refinedmods.refinedstorage.api.storage.tracker.StorageTrackerEntry;
import com.refinedmods.refinedstorage.render.RenderSettings;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath.clampToInt;

/**
 * 终端显示用的魔法资源 GridStack，实现 RS 的 IGridStack 接口。
 *
 * <p>身份取自 {@link MagicResourceKind}，名称、模组归属和图标都由资源自己决定，
 * 不依赖具体模组类型，所以未安装对应模组时这里也不会链接原生类。</p>
 */
public final class MagicStackGridStack implements IGridStack {
    private final MagicResourceKind kind;
    private final UUID id;
    private final UUID otherId;
    private long amount;
    private final long capacity;
    private StorageTrackerEntry trackerEntry;
    private final boolean craftable;

    public MagicStackGridStack(MagicResourceKind kind, UUID id, UUID otherId, long amount, long capacity,
                               StorageTrackerEntry trackerEntry, boolean craftable) {
        this.kind = kind;
        this.id = id;
        this.otherId = otherId;
        this.amount = amount;
        this.capacity = capacity;
        this.trackerEntry = trackerEntry;
        this.craftable = craftable;
    }

    public MagicResourceKind resourceKind() { return kind; }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public String getName() {
        return I18n.get(kind.translationKey());
    }

    @Override
    public String getModId() {
        return kind.namespace();
    }

    @Override
    public String getModName() {
        return kind.namespace();
    }

    @Override
    public List<Component> getTooltip(boolean bypassCache) {
        return List.of(kind.displayName(), Component.translatable(
                "gui.rs_integration.unified_grid.magic_amount_capacity",
                String.format(Locale.ROOT, "%,d", amount),
                String.format(Locale.ROOT, "%,d", capacity)));
    }

    public long getCapacity() { return capacity; }

    public long getLongQuantity() { return amount; }

    public void setLongQuantity(long quantity) { this.amount = Math.max(0, quantity); }

    @Override
    public int getQuantity() {
        return clampToInt(amount);
    }

    @Override
    public void setQuantity(int quantity) {
        this.amount = Math.max(0, quantity);
    }

    @Override
    public String getFormattedFullQuantity() {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    @Override
    public boolean isCraftable() {
        return craftable;
    }

    @Override
    public UUID getOtherId() {
        return otherId;
    }

    @Override
    public void updateOtherId(UUID uuid) {
        // 魔法资源没有可合成的对应条目，不需要同步
    }

    public UUID getCraftableId() {
        return craftable ? id : otherId;
    }

    @Override
    public void setTrackerEntry(StorageTrackerEntry entry) {
        this.trackerEntry = entry;
    }

    @Override
    public StorageTrackerEntry getTrackerEntry() {
        return trackerEntry;
    }

    @Override
    public Set<String> getTags() {
        return Set.of();
    }

    public void updateOther(IGridStack other) {
        // 魔法资源没有可合成的对应条目，不需要同步
    }

    public ResourceLocation getResourceLocation() {
        return MagicResourceTexture.of(kind);
    }

    @Override
    public void draw(GuiGraphics graphics, BaseScreen<?> screen, int x, int y) {
        MagicResourceTexture.render(graphics, kind, x, y);
        String text = craftable
                ? I18n.get("gui.refinedstorage.grid.craft")
                : CompactQuantityFormat.format(amount);
        screen.renderQuantity(graphics, x, y, text, RenderSettings.INSTANCE.getSecondaryColor());
    }

    @Override
    public Object getIngredient() {
        return new MagicResourceStack(kind, amount);
    }
}
