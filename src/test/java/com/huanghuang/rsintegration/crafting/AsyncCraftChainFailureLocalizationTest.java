package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncCraftChainFailureLocalizationTest extends BootstrapTest {

    @Test
    void failureChatUsesTheLocalizedProductNameInsteadOfRecipeId() {
        ItemStack output = new ItemStack(Items.STONE);
        output.setHoverName(Component.literal("本地化产物"));
        CraftingResolver.ResolutionStep step = mock(CraftingResolver.ResolutionStep.class);
        when(step.syntheticOutput()).thenReturn(output);

        Component target = AsyncCraftChain.stepFailureTarget(step, mock(ServerPlayer.class));

        assertEquals("本地化产物", target.getString());
    }
}
