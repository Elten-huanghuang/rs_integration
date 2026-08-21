package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StorageReference;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Optional;

/** Internal codec for persistent RS controller coordinates. */
final class RefinedStorageReference {
    private static final String VERSION_PREFIX = "v1|";
    private RefinedStorageReference() {}

    static StorageReference fromNetwork(INetwork network) {
        if (network.getLevel() == null) throw new IllegalStateException("RS network has no level");
        ResourceLocation dimension = network.getLevel().dimension().location();
        BlockPos position = network.getPosition();
        if (position == null) throw new IllegalStateException("RS network has no controller position");
        String id = VERSION_PREFIX + dimension + "@"
                + position.getX() + "," + position.getY() + "," + position.getZ();
        return new StorageReference(RefinedStorageIds.BACKEND, id);
    }

    static Optional<Location> parse(StorageReference reference) {
        if (!RefinedStorageIds.BACKEND.equals(reference.backendId())) return Optional.empty();
        String value = reference.networkId();
        if (!value.startsWith(VERSION_PREFIX)) return Optional.empty();
        value = value.substring(VERSION_PREFIX.length());
        int separator = value.lastIndexOf('@');
        if (separator <= 0 || separator == value.length() - 1) return Optional.empty();
        ResourceLocation dimension = ResourceLocation.tryParse(value.substring(0, separator));
        if (dimension == null) return Optional.empty();
        String[] coordinates = value.substring(separator + 1).split(",", -1);
        if (coordinates.length != 3) return Optional.empty();
        try {
            BlockPos position = new BlockPos(
                    Integer.parseInt(coordinates[0]),
                    Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]));
            return Optional.of(new Location(
                    ResourceKey.create(Registries.DIMENSION, dimension), position));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    record Location(ResourceKey<Level> dimension, BlockPos position) {}
}
