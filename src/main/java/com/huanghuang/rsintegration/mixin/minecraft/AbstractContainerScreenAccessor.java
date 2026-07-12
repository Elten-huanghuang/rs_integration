package com.huanghuang.rsintegration.mixin.minecraft;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    // Explicit field names. A bare @Accessor derives the name from the method
    // (getLeftPos -> leftPos), but that derived form was not emitted into the
    // refmap, so at runtime the SRG field f_97735_ could not be located and
    // Mixin threw InvalidAccessorException. Naming the field explicitly (as the
    // other accessors in this package do, e.g. InventoryAccessor) makes the
    // refmap map official -> SRG correctly under the 'official' mappings channel.
    @Accessor("leftPos")
    int getLeftPos();

    @Accessor("topPos")
    int getTopPos();

    @Accessor("imageWidth")
    int getImageWidth();
}
