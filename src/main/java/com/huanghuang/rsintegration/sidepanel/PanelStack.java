package com.huanghuang.rsintegration.sidepanel;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Mirrors RS native {@code ItemGridStack}.  The wrapped {@link #stack}
 *  always holds count ≥ 1 (for rendering), while {@link #getCount()}
 *  delegates to the {@link #zeroed} flag — exactly matching RS's
 *  {@code getQuantity() / setQuantity() / setZeroed()} pattern. */
public final class PanelStack {

    private final UUID id;
    final ItemStack stack;       // count is NEVER set to 0 — matches RS final stack
    long timestamp;
    boolean craftable;
    boolean zeroed;
    long zeroedAt;

    private String cachedName;
    private String cachedModId;
    private String cachedModName;

    PanelStack(UUID id, ItemStack stack, long timestamp, boolean craftable) {
        this.id = id;
        this.stack = stack.copy();
        if (this.stack.getCount() <= 0 && !this.stack.isEmpty()) {
            this.stack.setCount(1); // ensure renderable
        }
        this.timestamp = timestamp;
        this.craftable = craftable;
    }

    public UUID getId()          { return id; }

    /** Matches RS {@code IGridStack.getQuantity()}: returns 0 when zeroed. */
    public int getCount()        { return zeroed ? 0 : stack.getCount(); }

    /** Matches RS {@code IGridStack.setQuantity(int)}:
     *  ≤0 → set zeroed (keep stack count intact for rendering);
     *  >0 → update stack count, clear zeroed. */
    public void setCount(int c) {
        if (c <= 0) {
            zeroed = true;
            zeroedAt = System.currentTimeMillis();
        } else {
            zeroed = false;
            zeroedAt = 0;
            stack.setCount(c);
        }
    }

    public void grow(int delta) {
        int cur = getCount();
        setCount(cur + delta);
    }

    /** Always returns a renderable stack (count ≥ 1). */
    public ItemStack getStack()  { return stack; }

    public String getName() {
        if (cachedName == null) cachedName = stack.getHoverName().getString();
        return cachedName;
    }

    public String getModId() {
        if (cachedModId == null) {
            cachedModId = stack.getItem().getCreatorModId(stack);
        }
        return cachedModId;
    }

    public String getModName() {
        if (cachedModName == null) {
            String modId = getModId();
            cachedModName = net.minecraftforge.fml.ModList.get()
                    .getModContainerById(modId)
                    .map(c -> c.getModInfo().getDisplayName())
                    .orElse(modId);
        }
        return cachedModName;
    }

    public String searchKey() {
        var rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        String base = rl != null ? rl.toString() : "";
        if (stack.getTag() != null && !stack.getTag().isEmpty()) {
            base += "|" + stack.getTag().toString();
        }
        return base;
    }
}
