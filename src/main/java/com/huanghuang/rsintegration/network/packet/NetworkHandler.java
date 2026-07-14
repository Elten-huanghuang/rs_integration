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
 * <p>Protocol version is strictly enforced — a remote running a different
 * rs_integration protocol is rejected at connection time (with a clear error)
 * instead of silently connecting with a mismatched packet-id table. A remote
 * without the mod at all is still allowed to connect (the mod is simply inert
 * there). Bump {@link #PROTOCOL_VERSION} whenever any packet's wire format, or
 * the set/ids of registered packets, changes.</p>
 */
public final class NetworkHandler {

    /** Bump this whenever packet serialization or the registered packet set changes. */
    private static final String PROTOCOL_VERSION = "8";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RSIntegrationMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION)
    );

    private NetworkHandler() {}

    /** Called once during mod init to log channel creation. */
    public static void init() {
        RSIntegrationMod.LOGGER.info("[RSI-Network] Unified channel ready (protocol v{})", PROTOCOL_VERSION);
    }
}
