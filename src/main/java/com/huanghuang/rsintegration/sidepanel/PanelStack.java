package com.huanghuang.rsintegration.sidepanel;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Unified data model for a single item entry in the side panel display.
 *  Replaces the old {@code Map<key, ItemEntry> + List<ItemStack> displayList}
 *  dual-structure with a single self-contained object, analogous to RS
 *  native {@code IGridStack}. */
public final class PanelStack {

    private final UUID id;       // RS StackListEntry UUID — stable identity
    final ItemStack stack;       // item with current count
    long timestamp;
    boolean craftable;
    boolean zeroed;              // true when count dropped to 0 — stays in view, dimmed

    // Cached lazy values
    private String cachedName;
    private String cachedModId;
    private String cachedModName;

    PanelStack(UUID id, ItemStack stack, long timestamp, boolean craftable) {
        this.id = id;
        this.stack = stack.copy();
        this.timestamp = timestamp;
        this.craftable = craftable;
    }

    public UUID getId()          { return id; }
    public int getCount()        { return stack.getCount(); }
    public void setCount(int c)  { stack.setCount(c); }
    public void grow(int delta)  { stack.grow(delta); }
    public ItemStack getStack()  { return stack; }

    public String getName() {
        if (cachedName == null) cachedName = stack.getHoverName().getString();
        return cachedName;
    }

    public String getModId() {
        if (cachedModId == null) {
            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            cachedModId = key != null ? key.getNamespace() : "minecraft";
        }
        return cachedModId;
    }

    public String getModName() {
        if (cachedModName == null) {
            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key != null) {
                cachedModName = net.minecraftforge.fml.ModList.get()
                        .getModContainerById(key.getNamespace())
                        .map(c -> c.getModInfo().getDisplayName())
                        .orElse(key.getNamespace());
            } else {
                cachedModName = "Minecraft";
            }
        }
        return cachedModName;
    }

    /** Stable NBT-independent key for search/drag matching.
     *  Not used as primary identity — use {@link #getId()} for that. */
    public String searchKey() {
        var rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        String base = rl != null ? rl.toString() : "";
        if (stack.getTag() != null && !stack.getTag().isEmpty()) {
            base += "|" + stack.getTag().toString();
        }
        return base;
    }
}
