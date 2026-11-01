package com.huanghuang.rsintegration.mods.jade;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;
import snownee.jade.api.ui.IProgressStyle;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CauldronJadeProviderTest extends BootstrapTest {
    private final BlockAccessor accessor = mock(BlockAccessor.class);
    private final ITooltip tooltip = mock(ITooltip.class);
    private final IPluginConfig config = mock(IPluginConfig.class);
    private final IElementHelper helper = mock(IElementHelper.class);
    private final IProgressStyle style = mock(IProgressStyle.class);
    private final CompoundTag serverData = new CompoundTag();

    @AfterEach
    void unloadConfig() {
        RSIntegrationConfig.COMMON_SPEC.setConfig(null);
    }

    @Test
    void showsServerSuppliedOutputEffectsAndProgressWithoutAnOpenMenu() {
        var state = state("BREWING");
        var output = new ItemStack(Items.POTION);
        output.getOrCreateTag().putString("brew", "night_vision");
        state.put("output", output.save(new CompoundTag()));
        state.putInt("progress", 125);
        state.putInt("total", 500);
        state.putBoolean("running", true);
        var effects = new ListTag();
        effects.add(StringTag.valueOf(Component.Serializer.toJson(Component.translatable("effect.minecraft.night_vision"))));
        state.put("effects", effects);
        render(state);

        var item = ArgumentCaptor.forClass(ItemStack.class);
        verify(helper).smallItem(item.capture());
        assertTrue(ItemStack.isSameItemSameTags(output, item.getValue()));
        verify(tooltip).append(Component.translatable("jade.rs_integration.cauldron.output", output.getHoverName()));
        verify(tooltip).add(Component.translatable("effect.minecraft.night_vision"));
        verify(helper).progress(eq(.25F), eq(Component.translatable(
                "jade.rs_integration.cauldron.souls", 125, 500)), eq(style), eq(BoxStyle.DEFAULT), eq(true));
        verify(tooltip).add(Component.translatable("jade.rs_integration.cauldron.running"));
        verify(accessor, never()).getBlockEntity();
    }

    @Test
    void clampsOvershootingProgressAndShowsPausedState() {
        var state = state("BREWING");
        state.put("output", new ItemStack(Items.POTION).save(new CompoundTag()));
        state.putInt("progress", 1024);
        state.putInt("total", 500);
        render(state);
        verify(helper).progress(eq(1F), eq(Component.translatable(
                "jade.rs_integration.cauldron.souls", 500, 500)), eq(style), eq(BoxStyle.DEFAULT), eq(true));
        verify(tooltip).add(Component.translatable("jade.rs_integration.cauldron.paused"));
    }

    @Test
    void idleWaterAndWasteNeverDisplayAStaleProductOrProgressBar() {
        for (String mode : List.of("IDLE", "FAILED")) {
            reset(tooltip, helper);
            var state = state(mode);
            state.put("output", new ItemStack(Items.POTION).save(new CompoundTag()));
            state.putInt("progress", 100);
            state.putInt("total", 500);
            render(state);
            verify(tooltip).add(Component.translatable("gui.rs_integration.mode." + mode));
            verify(tooltip).add(Component.translatable("jade.rs_integration.cauldron.water", 3));
            verify(helper, never()).smallItem(any());
            verify(helper, never()).progress(anyFloat(), any(), any(), any(), anyBoolean());
        }
    }

    @Test
    void completedBrewStillShowsItsProductWithoutAnActiveProgressBar() {
        for (String mode : List.of("COMPLETED", "CRAFTED")) {
            reset(tooltip, helper);
            var state = state(mode);
            state.put("output", new ItemStack(Items.POTION).save(new CompoundTag()));
            state.putInt("progress", 500);
            state.putInt("total", 500);
            state.putBoolean("running", true);
            render(state);
            verify(helper).smallItem(any());
            verify(tooltip).add(Component.translatable("gui.rs_integration.mode." + mode));
            verify(helper, never()).progress(anyFloat(), any(), any(), any(), anyBoolean());
            verify(tooltip, never()).add(Component.translatable("jade.rs_integration.cauldron.running"));
            verify(tooltip, never()).add(Component.translatable("jade.rs_integration.cauldron.paused"));
        }
    }

    @Test
    void missingServerDataDoesNotGuessTheCurrentRecipeOnTheClient() {
        when(accessor.getServerData()).thenReturn(serverData);
        CauldronJadeProvider.INSTANCE.appendTooltip(tooltip, accessor, config);
        verifyNoInteractions(tooltip);
    }

    @Test
    void disabledFeatureHidesTheTooltipAndClearsPreviouslySentData() {
        var settings = CommentedConfig.inMemory();
        RSIntegrationConfig.COMMON_SPEC.correct(settings);
        RSIntegrationConfig.COMMON_SPEC.setConfig(settings);
        RSIntegrationConfig.ENABLE_GOETY_CAULDRON.set(false);
        serverData.put(CauldronJadeProvider.DATA_KEY, state("BREWING"));
        CauldronJadeProvider.INSTANCE.appendTooltip(tooltip, accessor, config);
        CauldronJadeProvider.INSTANCE.appendServerData(serverData, accessor);
        assertFalse(serverData.contains(CauldronJadeProvider.DATA_KEY));
        verifyNoInteractions(tooltip, accessor);
    }

    @Test
    void providerHasStableConfigurationId() {
        assertEquals("rs_integration:witch_cauldron", CauldronJadeProvider.INSTANCE.getUid().toString());
    }

    @Test
    void optionalPluginIsDiscoveredOnlyWithGoetyAndRegistersBothSides() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/com/huanghuang/rsintegration/mods/jade/GoetyCauldronJadePlugin.class")) {
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            var annotation = node.visibleAnnotations.stream()
                    .filter(value -> value.desc.equals("Lsnownee/jade/api/WailaPlugin;"))
                    .findFirst().orElseThrow();
            assertEquals("goety", annotation.values.get(annotation.values.indexOf("value") + 1));
            for (String method : List.of("registerBlockDataProvider", "registerBlockComponent")) {
                assertTrue(node.methods.stream().anyMatch(body -> {
                    for (var instruction : body.instructions) {
                        if (instruction instanceof MethodInsnNode call && call.name.equals(method)) return true;
                    }
                    return false;
                }), method);
            }
        }
    }

    private CompoundTag state(String mode) {
        var state = new CompoundTag();
        state.putString("mode", mode);
        state.putInt("water", 3);
        state.putBoolean("heated", true);
        return state;
    }

    private void render(CompoundTag state) {
        serverData.put(CauldronJadeProvider.DATA_KEY, state);
        when(accessor.getServerData()).thenReturn(serverData);
        when(tooltip.getElementHelper()).thenReturn(helper);
        when(helper.smallItem(any())).thenReturn(mock(IElement.class));
        when(helper.progressStyle()).thenReturn(style);
        when(helper.progress(anyFloat(), any(), any(), any(), anyBoolean())).thenReturn(mock(IElement.class));
        CauldronJadeProvider.INSTANCE.appendTooltip(tooltip, accessor, config);
    }
}
