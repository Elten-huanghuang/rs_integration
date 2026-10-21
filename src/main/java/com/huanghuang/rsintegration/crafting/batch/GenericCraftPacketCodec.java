package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageReferenceCodec;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** GenericCraftPacket 的线协议编解码，保持既有字节顺序和兼容读取规则。 */
final class GenericCraftPacketCodec {
    private GenericCraftPacketCodec() {}

    record WireData(
            ResourceLocation recipeId,
            boolean preview,
            Map<String, String> forcedRecipes,
            @Nullable ResourceLocation dimension,
            @Nullable BlockPos position,
            int repeatCount,
            boolean inferMode,
            @Nullable ItemStack baseItem,
            @Nullable ItemStack targetOutput,
            long requestId,
            OutputDestination outputDestination,
            @Nullable StorageReference storageReference,
            boolean maximize,
            MachineSelectionMode machineSelectionMode,
            Map<String, ItemStack> materialLocks,
            boolean partialPreparation,
            PlanningPreferences planningPreferences) {}

    static void write(FriendlyByteBuf buf, WireData data) {
        buf.writeResourceLocation(data.recipeId());
        buf.writeBoolean(data.preview());
        buf.writeVarInt(data.forcedRecipes().size());
        for (Map.Entry<String, String> entry : data.forcedRecipes().entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeUtf(entry.getValue());
        }
        buf.writeBoolean(data.dimension() != null);
        if (data.dimension() != null) buf.writeResourceLocation(data.dimension());
        buf.writeBoolean(data.position() != null);
        if (data.position() != null) buf.writeBlockPos(data.position());
        buf.writeVarInt(data.repeatCount());
        buf.writeBoolean(data.inferMode());
        buf.writeBoolean(data.baseItem() != null);
        if (data.baseItem() != null) InkFluidSupport.writePacket(buf, data.baseItem());
        buf.writeBoolean(data.targetOutput() != null);
        if (data.targetOutput() != null) {
            InkFluidSupport.writePacket(buf, data.targetOutput());
        }
        buf.writeBoolean(data.requestId() != 0L);
        if (data.requestId() != 0L) buf.writeVarLong(data.requestId());
        data.outputDestination().write(buf);
        buf.writeBoolean(data.storageReference() != null);
        if (data.storageReference() != null) {
            buf.writeNbt(StorageReferenceCodec.encode(data.storageReference()));
        }
        buf.writeBoolean(data.maximize());
        data.machineSelectionMode().write(buf);
        buf.writeVarInt(data.materialLocks().size());
        for (Map.Entry<String, ItemStack> entry : data.materialLocks().entrySet()) {
            buf.writeUtf(entry.getKey(), MaterialLocks.MAX_KEY_LENGTH);
            buf.writeItem(entry.getValue().copyWithCount(1));
        }
        buf.writeBoolean(data.partialPreparation());
        buf.writeBoolean(!data.planningPreferences().isEmpty());
        if (!data.planningPreferences().isEmpty()) {
            data.planningPreferences().write(buf);
        }
    }

    static WireData read(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        boolean preview = buf.readBoolean();
        int forcedCount = buf.readVarInt();
        if (forcedCount < 0 || forcedCount > 128) {
            throw new DecoderException(
                    "GenericCraftPacket forcedCount out of range: " + forcedCount);
        }
        Map<String, String> forced = new HashMap<>();
        for (int i = 0; i < forcedCount; i++) {
            String key = buf.readUtf();
            String value = buf.readUtf();
            if (!key.isEmpty() && !value.isEmpty()
                    && ResourceLocation.tryParse(key) != null
                    && ResourceLocation.tryParse(value) != null) {
                forced.put(key, value);
            }
        }
        ResourceLocation dimension = buf.readBoolean() ? buf.readResourceLocation() : null;
        BlockPos position = null;
        if (buf.readBoolean()) {
            BlockPos raw = buf.readBlockPos();
            if (raw.getX() >= -30000000 && raw.getX() <= 30000000
                    && raw.getY() >= -64 && raw.getY() <= 2048
                    && raw.getZ() >= -30000000 && raw.getZ() <= 30000000) {
                position = raw;
            }
        }
        int repeatCount = buf.readVarInt();
        if (repeatCount < 1 || repeatCount > RSIntegrationConfig.REPEAT_COUNT_MAX.get()) {
            throw new IllegalArgumentException("invalid repeat count");
        }
        boolean inferMode = buf.readBoolean();
        ItemStack baseItem = buf.readBoolean() ? InkFluidSupport.readPacket(buf) : null;
        ItemStack targetOutput = buf.readBoolean() ? InkFluidSupport.readPacket(buf) : null;
        long requestId = 0L;
        if (buf.isReadable() && buf.readBoolean()) {
            requestId = buf.readVarLong();
            if (requestId < 0 || requestId > 0x7FFF_FFFF_FFFF_FFFFL) {
                throw new DecoderException("GenericCraftPacket requestId out of range");
            }
        }
        OutputDestination outputDestination = buf.isReadable()
                ? OutputDestination.read(buf)
                : OutputDestination.RS_NETWORK;
        StorageReference storageReference = null;
        if (buf.isReadable() && buf.readBoolean()) {
            storageReference = StorageReferenceCodec.decode(buf.readNbt())
                    .orElseThrow(() -> new DecoderException(
                            "invalid GenericCraftPacket storage reference"));
        }
        boolean maximize = buf.readBoolean();
        if (maximize && !preview) {
            throw new DecoderException("maximize requires preview mode");
        }
        MachineSelectionMode machineSelectionMode = buf.isReadable()
                ? MachineSelectionMode.read(buf) : MachineSelectionMode.AUTO;
        Map<String, ItemStack> materialLocks = new LinkedHashMap<>();
        if (buf.isReadable()) {
            int materialLockCount = buf.readVarInt();
            if (materialLockCount < 0 || materialLockCount > MaterialLocks.MAX_LOCKS) {
                throw new DecoderException(
                        "GenericCraftPacket materialLockCount out of range: "
                                + materialLockCount);
            }
            for (int i = 0; i < materialLockCount; i++) {
                String key = buf.readUtf(MaterialLocks.MAX_KEY_LENGTH);
                ItemStack selected = buf.readItem();
                if (!key.isEmpty() && !selected.isEmpty()) {
                    materialLocks.put(key, selected.copyWithCount(1));
                }
            }
        }
        boolean partialPreparation = buf.isReadable() && buf.readBoolean();
        PlanningPreferences planningPreferences = buf.isReadable() && buf.readBoolean()
                ? PlanningPreferences.read(buf) : PlanningPreferences.empty();
        if (buf.isReadable()) {
            throw new DecoderException("trailing GenericCraftPacket data");
        }
        return new WireData(recipeId, preview, forced, dimension, position, repeatCount,
                inferMode, baseItem, targetOutput, requestId, outputDestination,
                storageReference, maximize, machineSelectionMode,
                MaterialLocks.immutableCopy(materialLocks), partialPreparation,
                planningPreferences);
    }
}
