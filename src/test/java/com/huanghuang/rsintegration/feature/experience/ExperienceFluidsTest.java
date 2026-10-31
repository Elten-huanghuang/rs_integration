package com.huanghuang.rsintegration.feature.experience;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.feature.experience.client.ExperienceFluidClient;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegisterEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExperienceFluidsTest extends BootstrapTest {
    private static final Path RESOURCES = Path.of("src/main/resources");

    @BeforeAll
    static void registerFluids() throws ReflectiveOperationException {
        List<DeferredRegister.EventDispatcher> dispatchers = new ArrayList<>();
        IEventBus bus = mock(IEventBus.class);
        doAnswer(call -> {
            dispatchers.add(call.getArgument(0));
            return null;
        }).when(bus).register(any());
        ExperienceFluids.register(bus);
        assertEquals(2, dispatchers.size());

        // 单测不启动 FML 的自定义注册表，只替代 FluidType 注册边界。
        Map<ResourceLocation, FluidType> types = new HashMap<>();
        IForgeRegistry<FluidType> typeRegistry = mockRegistry(types);
        RegisterEvent typesEvent = registerEvent(ForgeRegistries.Keys.FLUID_TYPES, typeRegistry);
        dispatchers.forEach(dispatcher -> dispatcher.handleEvent(typesEvent));
        assertSame(ExperienceFluids.EXPERIENCE_TYPE.get(),
                types.get(new ResourceLocation("rs_integration", "experience")));

        ForgeRegistry<Fluid> fluids = (ForgeRegistry<Fluid>) ForgeRegistries.FLUIDS;
        boolean locked = fluids.isLocked();
        fluids.unfreeze();
        try {
            Method unfreeze = BuiltInRegistries.FLUID.getClass().getMethod("unfreeze");
            unfreeze.setAccessible(true);
            unfreeze.invoke(BuiltInRegistries.FLUID);
            RegisterEvent fluidsEvent = registerEvent(ForgeRegistries.Keys.FLUIDS, fluids);
            dispatchers.forEach(dispatcher -> dispatcher.handleEvent(fluidsEvent));
        } finally {
            BuiltInRegistries.FLUID.freeze();
            if (locked) fluids.freeze();
        }
    }

    @Test
    void registersIndependentSourceAndFlowingFluidWithOneType() {
        var source = ExperienceFluids.EXPERIENCE.get();
        var flowing = ExperienceFluids.FLOWING_EXPERIENCE.get();
        FluidType type = ExperienceFluids.EXPERIENCE_TYPE.get();

        assertEquals(new ResourceLocation("rs_integration", "experience"),
                ForgeRegistries.FLUIDS.getKey(source));
        assertEquals(new ResourceLocation("rs_integration", "flowing_experience"),
                ForgeRegistries.FLUIDS.getKey(flowing));
        assertSame(source, source.getSource());
        assertSame(source, flowing.getSource());
        assertSame(flowing, source.getFlowing());
        assertSame(flowing, flowing.getFlowing());
        assertSame(type, source.getFluidType());
        assertSame(type, flowing.getFluidType());
        assertTrue(source.isSame(flowing));
        assertTrue(source.defaultFluidState().isSource());
        assertFalse(flowing.defaultFluidState().isSource());
        assertSame(Items.AIR, source.getBucket());
        assertTrue(source.defaultFluidState().createLegacyBlock().isAir());
        assertFalse(type.canConvertToSource(source.defaultFluidState(), null, null));
        assertEquals("fluid_type.rs_integration.experience", type.getDescriptionId());
        assertEquals(10, type.getLightLevel());
        assertEquals(20, ExperienceFluids.MILLIBUCKETS_PER_EXPERIENCE_POINT);
    }

    @Test
    void storesAsOrdinaryFluidAndPreservesIdentityInNbt() {
        FluidStack original = new FluidStack(ExperienceFluids.EXPERIENCE.get(), 1000);
        FluidStack restored = FluidStack.loadFluidStackFromNBT(original.writeToNBT(new CompoundTag()));
        assertTrue(restored.isFluidEqual(original));
        assertEquals(1000, restored.getAmount());

        FluidTank tank = new FluidTank(4000);
        assertEquals(1000, tank.fill(restored, FluidAction.SIMULATE));
        assertTrue(tank.isEmpty());
        assertEquals(1000, tank.fill(restored, FluidAction.EXECUTE));
        FluidStack drained = tank.drain(250, FluidAction.EXECUTE);
        assertSame(ExperienceFluids.EXPERIENCE.get(), drained.getFluid());
        assertEquals(250, drained.getAmount());
        assertEquals(750, tank.getFluidAmount());
    }

    @Test
    void joinsExperienceTagWithoutReplacingOtherMods() throws IOException {
        JsonObject tag = json(RESOURCES.resolve("data/forge/tags/fluids/experience.json"));
        assertFalse(tag.get("replace").getAsBoolean());
        assertEquals("forge:experience", ExperienceFluids.EXPERIENCE_TAG.location().toString());
        List<String> values = new ArrayList<>();
        tag.getAsJsonArray("values").forEach(value -> values.add(value.getAsString()));
        assertEquals(List.of(ExperienceFluids.EXPERIENCE.getId().toString(),
                ExperienceFluids.FLOWING_EXPERIENCE.getId().toString()), values);
    }

    @Test
    void rendersWithBundledAnimatedTexturesAndOriginalColors() throws IOException {
        List<IClientFluidTypeExtensions> extensions = new ArrayList<>();
        ExperienceFluids.EXPERIENCE_TYPE.get().initializeClient(extensions::add);
        assertEquals(1, extensions.size());
        IClientFluidTypeExtensions extension = extensions.get(0);
        assertEquals(0xFFFFFFFF, extension.getTintColor());
        assertEquals(new ResourceLocation("rs_integration", "block/experience_still"),
                extension.getStillTexture());
        assertEquals(new ResourceLocation("rs_integration", "block/experience_flow"),
                extension.getFlowingTexture());
        List<String> atlasTextures = new ArrayList<>();
        json(RESOURCES.resolve("assets/minecraft/atlases/blocks.json"))
                .getAsJsonArray("sources").forEach(entry ->
                        atlasTextures.add(entry.getAsJsonObject().get("resource").getAsString()));
        for (ResourceLocation texture : List.of(extension.getStillTexture(), extension.getFlowingTexture())) {
            assertTrue(atlasTextures.contains(texture.toString()));
            Path file = RESOURCES.resolve("assets/" + texture.getNamespace()
                    + "/textures/" + texture.getPath() + ".png");
            var image = ImageIO.read(file.toFile());
            assertNotNull(image);
            assertEquals(16, image.getWidth());
            assertEquals(512, image.getHeight());
            JsonObject animation = json(file.resolveSibling(file.getFileName() + ".mcmeta"));
            assertEquals(3, animation.getAsJsonObject("animation").get("frametime").getAsInt());
        }
    }

    @Test
    void bundlesTranslationsAndTextureLicense() throws IOException {
        String key = ExperienceFluids.EXPERIENCE_TYPE.get().getDescriptionId();
        assertEquals("液态经验",
                json(RESOURCES.resolve("assets/rs_integration/lang/zh_cn.json")).get(key).getAsString());
        assertEquals("Liquid Experience",
                json(RESOURCES.resolve("assets/rs_integration/lang/en_us.json")).get(key).getAsString());
        String license = Files.readString(RESOURCES.resolve("META-INF/licenses/MIT-BeyondDimensions.txt"));
        assertTrue(license.contains("Copyright (c) 2025 Frostbite-time"));
        assertTrue(license.contains("Permission is hereby granted"));
    }

    @Test
    void doesNotLinkOptionalModsOrMinecraftClientClasses() throws IOException {
        for (Class<?> type : List.of(ExperienceFluids.class,
                ExperienceFluids.EXPERIENCE_TYPE.get().getClass(), ExperienceFluidClient.class)) {
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            try (var input = type.getResourceAsStream(resource)) {
                assertNotNull(input);
                String constants = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (String forbidden : List.of("net/p3pp3rf1y/", "com/wintercogs/",
                        "com/refinedmods/", "net/minecraft/client/")) {
                    assertFalse(constants.contains(forbidden), type.getSimpleName() + ": " + forbidden);
                }
            }
        }
    }

    private static JsonObject json(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    @SuppressWarnings("unchecked")
    private static <T> IForgeRegistry<T> mockRegistry(Map<ResourceLocation, T> entries) {
        IForgeRegistry<T> registry = mock(IForgeRegistry.class);
        doAnswer(call -> {
            entries.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(registry).register(any(ResourceLocation.class), any());
        when(registry.containsKey(any(ResourceLocation.class)))
                .thenAnswer(call -> entries.containsKey(call.getArgument(0)));
        when(registry.getValue(any(ResourceLocation.class)))
                .thenAnswer(call -> entries.get(call.getArgument(0)));
        return registry;
    }

    private static <T> RegisterEvent registerEvent(ResourceKey<? extends Registry<T>> key,
                                                   IForgeRegistry<T> registry) {
        RegisterEvent event = mock(RegisterEvent.class);
        doReturn(key).when(event).getRegistryKey();
        doReturn(registry).when(event).getForgeRegistry();
        doAnswer(call -> {
            ResourceLocation id = call.getArgument(1);
            Supplier<T> supplier = call.getArgument(2);
            registry.register(id, supplier.get());
            return null;
        }).when(event).register(eq(key), any(ResourceLocation.class), any());
        return event;
    }
}
