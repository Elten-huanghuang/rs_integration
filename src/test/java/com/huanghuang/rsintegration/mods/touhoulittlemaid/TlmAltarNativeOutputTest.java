package com.huanghuang.rsintegration.mods.touhoulittlemaid;

import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.OperationLifecycle;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import com.huanghuang.rsintegration.RSIntegrationMod;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TlmAltarNativeOutputTest extends BootstrapTest {
    @BeforeAll
    static void initializeMod() {
        var context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            RSIntegrationMod.LOGGER.debug("初始化车万女仆祭坛产物测试");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeSpawnKeepsInheritedTagsButDoesNotAcceptWrongFixedVariant(boolean copiesInput) {
        ServerLevel level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(server.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        var expected = new ItemStack(Items.INK_SAC, 2);
        expected.getOrCreateTag().putString("variant", "template");
        var actual = expected.copy();
        actual.getOrCreateTag().putString("variant", "inherited");
        ItemEntity entity = mock(ItemEntity.class);
        when(entity.level()).thenReturn(level);
        when(entity.getItem()).thenReturn(actual);

        var recipe = new NativeRecipe(expected, copiesInput);
        var delegate = new TlmAltarBatchDelegate();
        delegate.setMachineServer(server);
        Reflect.setField(delegate, "recipe", recipe);
        var reception = TaskOutputReception.open();
        delegate.bindProduction(reception.writer());
        delegate.armAltarOutput(recipe, level);

        assertNull(delegate.getOutputCaptureRegion());
        assertFalse(recipe.receiver.test(mock(Entity.class)));
        assertEquals(copiesInput, recipe.receiver.test(entity));
        var result = new OperationLifecycle(delegate, reception).collect(null, 1, List::of);
        assertEquals(copiesInput, result.settlement().complete());
        assertEquals(copiesInput ? 1 : 0, result.stacks().size());
        if (copiesInput) {
            assertEquals(2, result.stacks().get(0).getCount());
            assertTrue(ItemStack.isSameItemSameTags(actual, result.stacks().get(0)));
        }
        assertFalse(recipe.receiver.test(entity));
        verify(level, never()).getEntitiesOfClass(eq(ItemEntity.class), any(AABB.class));
    }

    private static final class NativeRecipe extends ShapelessRecipe implements TlmAltarOutputHook {
        private final Ingredient copyInput;
        private Predicate<Entity> receiver;

        NativeRecipe(ItemStack expected, boolean copiesInput) {
            super(ResourceLocation.fromNamespaceAndPath("test", "native_altar"), "", CraftingBookCategory.MISC,
                    expected, NonNullList.create());
            copyInput = copiesInput ? Ingredient.of(Items.IRON_INGOT) : Ingredient.EMPTY;
        }

        @Override
        public void rsi$setAltarOutputReceiver(Predicate<Entity> receiver) {
            this.receiver = receiver;
        }
    }
}
