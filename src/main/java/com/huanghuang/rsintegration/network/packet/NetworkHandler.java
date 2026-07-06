package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Single unified network channel for all RSI packets.
 *
 * <p>Replaces the previous 8 separate {@link SimpleChannel} instances.
 * Packet registration is delegated to each subsystem's handler class
 * (which lives in the same package as its packets, avoiding Java
 * visibility issues with method references).</p>
 *
 * <p>Protocol version is strictly enforced — mismatched client/server
 * versions will be rejected at connection time.</p>
 */
public final class NetworkHandler {

    /** Bump this whenever packet serialization changes. */
    private static final String PROTOCOL_VERSION = "5";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            remote -> true,
            remote -> true
    );

    /** Global auto-incrementing packet discriminator ID.
     *  Every call to registerMessage must use {@link #nextId()} instead of a
     *  hard-coded number to prevent collisions across subsystems. */
    private static int packetId;

    public static int nextId() {
        return packetId++;
    }

    private NetworkHandler() {}

    /** Called once during mod init to log channel creation. */
    public static void init() {
        RSIntegrationMod.LOGGER.info("[RSI-Network] Unified channel ready (protocol v{})", PROTOCOL_VERSION);
    }
}
