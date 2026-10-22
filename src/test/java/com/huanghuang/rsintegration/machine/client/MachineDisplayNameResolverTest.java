package com.huanghuang.rsintegration.machine.client;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.machine.client.data.BindingCache;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MachineDisplayNameResolverTest extends BootstrapTest {
    private static final String TYPE_ID = "test_auto_machine_name";

    @Test
    void resolvesBoundMachineNameFromRecipeBinding() {
        ModType type = ModType.byId(TYPE_ID);
        if (type == ModType.GENERIC) {
            type = ModType.register(TYPE_ID, new String[0],
                    new String[]{"test_auto_machine"},
                    new String[]{"test_auto_machine"}, () -> null);
        }

        ItemStack displayStack = new ItemStack(Items.STONE);
        displayStack.setHoverName(Component.literal("自动读取机器名"));
        BindingInfo binding = new BindingInfo(
                "minecraft:air", ResourceLocation.tryParse("minecraft:overworld"),
                BlockPos.ZERO, TYPE_ID + "||block.example.test_auto_machine",
                "block.example.test_auto_machine", "example:test_auto_machine",
                displayStack);

        BindingCache.getInstance().updateBindings(List.of(binding));
        try {
            Component name = MachineDisplayNameResolver.findDisplayName(
                    type, ResourceLocation.tryParse(
                            "example:test_auto_machine/example_recipe"));

            assertNotNull(name);
            assertEquals("自动读取机器名", name.getString());
        } finally {
            BindingCache.getInstance().clear();
        }
    }
}
