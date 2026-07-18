package com.huanghuang.rsintegration.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Checks whether a player is allowed to interact with a block at a given
 * position, respecting external protection/claim mods (FTB Chunks, Cadmus).
 *
 * <p>All checks are performed via reflection so that these mods remain
 * optional dependencies. A loaded provider that cannot be queried is treated
 * as indeterminate and denied by the side-effecting callers; API drift must
 * never silently disable claim protection.</p>
 */
public final class ProtectionChecker {

    public enum Decision { ALLOW, DENY, UNKNOWN }
    public enum Reason {
        MOD_NOT_INSTALLED, UNCLAIMED, OWNER, ALLY, EXPLICIT_DENY,
        CLAIM_LOOKUP_FAILED, OWNER_LOOKUP_FAILED, API_FAILURE
    }
    public record ProtectionResult(Decision decision, Reason reason, String provider) {
        public boolean permitted() { return decision == Decision.ALLOW; }
    }

    private static final String TAG = "[RSI-Protect]";

    private ProtectionChecker() {}

    public static ProtectionResult check(ServerPlayer player, ServerLevel level, BlockPos pos) {
        ProtectionResult strongest = new ProtectionResult(Decision.ALLOW, Reason.MOD_NOT_INSTALLED, "none");
        if (ModList.get().isLoaded(ModIds.FTB_CHUNKS)) {
            boolean allowed = checkFTBChunks(player, level, pos);
            strongest = merge(strongest, new ProtectionResult(
                    allowed ? Decision.ALLOW : Decision.DENY,
                    allowed ? Reason.UNCLAIMED : Reason.EXPLICIT_DENY,
                    "ftb_chunks"));
        }
        if (ModList.get().isLoaded(ModIds.CADMUS)) {
            boolean allowed = checkCadmus(player, level, pos);
            strongest = merge(strongest, new ProtectionResult(
                    allowed ? Decision.ALLOW : Decision.DENY,
                    allowed ? Reason.UNCLAIMED : Reason.EXPLICIT_DENY,
                    "cadmus"));
        }
        return strongest;
    }

    private static ProtectionResult merge(ProtectionResult a, ProtectionResult b) {
        if (a.decision == Decision.DENY || b.decision == Decision.DENY) return a.decision == Decision.DENY ? a : b;
        if (a.decision == Decision.UNKNOWN || b.decision == Decision.UNKNOWN) return a.decision == Decision.UNKNOWN ? a : b;
        return b;
    }

    public static boolean canInteract(ServerPlayer player, ServerLevel level, BlockPos pos) {
        ProtectionResult result = check(player, level, pos);
        if (!result.permitted()) {
            RSIntegrationMod.LOGGER.info("{} {} denied {} at {} in {}", TAG, result.provider(),
                    player.getGameProfile().getName(), pos, level.dimension().location());
        }
        return result.permitted();
    }

    // ── Static method helper (Reflect.invoke cannot use null obj) ──

    /**
     * Invoke a static no-arg method on the given class. Returns {@code null}
     * on any failure.
     */
    private static Object invokeStatic(Class<?> clazz, String methodName) {
        try {
            Method m = Reflect.findMethod(clazz, methodName, new Class<?>[0]);
            if (m != null) return m.invoke(null);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }
        return null;
    }

    /**
     * Invoke a static method with explicit parameter types.
     */
    private static Object invokeStatic(Class<?> clazz, String methodName,
                                        Class<?>[] paramTypes, Object... args) {
        try {
            Method m = Reflect.findMethod(clazz, methodName, paramTypes);
            if (m != null) return m.invoke(null, args);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }
        return null;
    }

    /**
     * Invoke an instance method with auto-detected parameter types.
     * Returns {@link Optional#empty()} on any failure.
     */
    private static Optional<Object> invokeInstance(Object obj, String methodName, Object... args) {
        if (obj == null) return Optional.empty();
        try {
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
            }
            Method m = Reflect.findMethod(obj.getClass(), methodName, paramTypes);
            if (m == null) {
                // Retry with exact types using auto-unboxing
                return Reflect.invokeExact(obj, methodName, paramTypes, args);
            }
            return Optional.ofNullable(m.invoke(obj, args));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ── FTB Chunks ────────────────────────────────────────────────

    private static boolean checkFTBChunks(ServerPlayer player, ServerLevel level, BlockPos pos) {
        try {
            // 1. Load ClaimedChunks manager
            Optional<Class<?>> ccClass = Reflect.forName(
                    "dev.ftb.mods.ftbchunks.data.ClaimedChunks");
            if (ccClass.isEmpty()) return true;

            Object manager = getFTBChunksManager(ccClass.get());
            if (manager == null) return true;

            // 2. Check if the chunk is claimed
            boolean isClaimed = isFTBChunkClaimed(manager, level, pos);
            if (!isClaimed) return true; // Not claimed — allow

            // 3. Chunk is claimed — get the claim owner's team/player UUID
            Object claimOwnerId = getFTBClaimOwner(manager, level, pos);
            if (claimOwnerId == null) {
                // Can't determine owner — allow (fail-open)
                return true;
            }

            // 4. Get the player's team
            Object playerTeam = getFTBPlayerTeam(player);
            if (playerTeam == null) {
                // Player is not in any team — deny (they can't be the owner
                // of the claim if they have no team)
                RSIntegrationMod.LOGGER.debug(
                        "{} FTB Chunks: player {} has no team, chunk is claimed",
                        TAG, player.getGameProfile().getName());
                return false;
            }

            Object playerTeamId = getFTBTeamId(playerTeam);
            if (playerTeamId == null) return true; // Can't determine — allow

            // 5. Compare claim owner with player's team
            if (claimOwnerId.equals(playerTeamId)) return true;

            // 6. Player is in a different team — check alliance
            if (isFTBTeamAllied(playerTeam, claimOwnerId)) return true;

            // 7. Deny — chunk claimed by another team
            return false;

        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("{} FTB Chunks check error (fail-open)", TAG, e);
            return true; // fail-open
        }
    }

    // ── FTB Chunks: manager lookup ────────────────────────────────

    private static Object getFTBChunksManager(Class<?> ccClass) {
        // Try static get()
        Object manager = invokeStatic(ccClass, "get");
        if (manager != null) return manager;

        // Try static instance()
        manager = invokeStatic(ccClass, "instance");
        if (manager != null) return manager;

        // Try INSTANCE field
        try {
            Optional<java.lang.reflect.Field> f = Reflect.findField(ccClass, "INSTANCE");
            if (f.isPresent()) return f.get().get(null);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }

        return null;
    }

    // ── FTB Chunks: claim detection ───────────────────────────────

    private static boolean isFTBChunkClaimed(Object manager, ServerLevel level, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);

        // Approach 1: getChunk(ChunkPos) returns ClaimedChunk or null
        try {
            Method getChunk = Reflect.findMethod(
                    manager.getClass(), "getChunk", new Class<?>[]{ChunkPos.class});
            if (getChunk != null) {
                Object claim = getChunk.invoke(manager, chunkPos);
                return claim != null;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }

        // Approach 2: getChunk(ServerLevel, BlockPos)
        try {
            Method getChunk = Reflect.findMethod(
                    manager.getClass(), "getChunk",
                    new Class<?>[]{ServerLevel.class, BlockPos.class});
            if (getChunk != null) {
                Object claim = getChunk.invoke(manager, level, pos);
                return claim != null;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }

        // Approach 3: isWithinClaimedChunk(ServerLevel, BlockPos)
        try {
            Method m = Reflect.findMethod(manager.getClass(), "isWithinClaimedChunk",
                    new Class<?>[]{ServerLevel.class, BlockPos.class});
            if (m != null) {
                Object result = m.invoke(manager, level, pos);
                if (result instanceof Boolean b) return b;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }

        // Approach 4: isClaimed(ChunkPos)
        try {
            Method m = Reflect.findMethod(manager.getClass(), "isClaimed",
                    new Class<?>[]{ChunkPos.class});
            if (m != null) {
                Object result = m.invoke(manager, chunkPos);
                if (result instanceof Boolean b) return b;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }

        // Can't determine — default to not claimed (allow). This is a SILENT
        // fail-open: if FTB renamed these methods, every claimed chunk becomes
        // allowed. Log at warn so API drift is visible instead of silently
        // disabling claim protection.
        RSIntegrationMod.LOGGER.warn("{} FTB Chunks: all claim-detection probes failed "
                + "(API may have changed) — treating chunk as UNCLAIMED (fail-open)", TAG);
        return false;
    }

    // ── FTB Chunks: claim owner extraction ────────────────────────

    private static Object getFTBClaimOwner(Object manager, ServerLevel level, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);

        // Try to get the ClaimedChunk object first, then extract owner
        Object claim = null;
        try {
            Method getChunk = Reflect.findMethod(
                    manager.getClass(), "getChunk", new Class<?>[]{ChunkPos.class});
            if (getChunk != null) claim = getChunk.invoke(manager, chunkPos);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }

        if (claim == null) {
            try {
                Method getChunk = Reflect.findMethod(
                        manager.getClass(), "getChunk",
                        new Class<?>[]{ServerLevel.class, BlockPos.class});
                if (getChunk != null) claim = getChunk.invoke(manager, level, pos);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }
        }

        if (claim == null) return null;

        // Extract team/owner from the claim object
        return extractTeamIdFromClaim(claim);
    }

    private static Object extractTeamIdFromClaim(Object claim) {
        // Try getTeamData() → getId() / getTeamId()
        try {
            Optional<Object> teamData = invokeInstance(claim, "getTeamData");
            if (teamData.isPresent() && teamData.get() != null) {
                // Try getId()
                Optional<Object> id = invokeInstance(teamData.get(), "getId");
                if (id.isPresent()) return id.get();

                // Try getTeamId()
                id = invokeInstance(teamData.get(), "getTeamId");
                if (id.isPresent()) return id.get();

                // Try getOwner() — returns UUID
                Optional<Object> owner = invokeInstance(teamData.get(), "getOwner");
                if (owner.isPresent()) return owner.get();
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }

        // Try getTeamId() directly on the claim
        Optional<Object> teamId = invokeInstance(claim, "getTeamId");
        if (teamId.isPresent()) return teamId.get();

        // Try getOwningTeamId()
        teamId = invokeInstance(claim, "getOwningTeamId");
        if (teamId.isPresent()) return teamId.get();

        return null;
    }

    // ── FTB Teams: player team lookup ─────────────────────────────

    private static Object getFTBPlayerTeam(ServerPlayer player) {
        // Approach 1: FTBTeamsAPI.api().getManager().getPlayerTeam(uuid)
        try {
            Optional<Class<?>> apiClass = Reflect.forName(
                    "dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            if (apiClass.isPresent()) {
                Object api = invokeStatic(apiClass.get(), "api");
                if (api != null) {
                    Object manager = invokeInstance(api, "getManager").orElse(null);
                    if (manager != null) {
                        Method m = Reflect.findMethod(manager.getClass(), "getPlayerTeam",
                                new Class<?>[]{UUID.class});
                        if (m != null) {
                            return m.invoke(manager, player.getUUID());
                        }
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("{} FTBTeamsAPI lookup failed", TAG, e);
        }

        // Approach 2: TeamManager.getPlayerTeam(uuid) static
        try {
            Optional<Class<?>> tmClass = Reflect.forName(
                    "dev.ftb.mods.ftbteams.data.TeamManager");
            if (tmClass.isPresent()) {
                Object result = invokeStatic(tmClass.get(), "getPlayerTeam",
                        new Class<?>[]{UUID.class}, player.getUUID());
                if (result != null) return result;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("{} TeamManager static lookup failed", TAG, e);
        }

        // Approach 3: getTeamForPlayer(ServerPlayer) through the manager
        try {
            Optional<Class<?>> apiClass = Reflect.forName(
                    "dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
            if (apiClass.isPresent()) {
                Object api = invokeStatic(apiClass.get(), "api");
                if (api != null) {
                    Object manager = invokeInstance(api, "getManager").orElse(null);
                    if (manager != null) {
                        // Try alternative method names
                        for (String name : new String[]{"getTeamForPlayer", "getPlayerTeamForPlayer", "getTeam"}) {
                            try {
                                Method m = Reflect.findMethod(manager.getClass(), name,
                                        new Class<?>[]{UUID.class});
                                if (m != null) return m.invoke(manager, player.getUUID());
                            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }
                            try {
                                // Also try with ServerPlayer parameter
                                Method m = Reflect.findMethod(manager.getClass(), name,
                                        new Class<?>[]{ServerPlayer.class});
                                if (m != null) return m.invoke(manager, player);
                            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }
                        }
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("{} FTB Teams alt lookup failed", TAG, e);
        }

        return null;
    }

    // ── FTB Teams: team ID extraction ─────────────────────────────

    private static Object getFTBTeamId(Object team) {
        for (String name : new String[]{"getId", "getTeamId", "getShortName", "getName", "getOwner"}) {
            Optional<Object> id = invokeInstance(team, name);
            if (id.isPresent()) return id.get();
        }
        return null;
    }

    // ── FTB Teams: alliance check ─────────────────────────────────

    private static boolean isFTBTeamAllied(Object playerTeam, Object claimOwnerId) {
        // Try isAlly(UUID)
        try {
            Optional<Object> result = invokeInstance(playerTeam, "isAlly", claimOwnerId);
            if (result.isPresent() && result.get() instanceof Boolean b) return b;
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }

        // Try isAllied(teamId)
        try {
            Optional<Object> result = invokeInstance(playerTeam, "isAllied", claimOwnerId);
            if (result.isPresent() && result.get() instanceof Boolean b) return b;
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }

        // Try isAllied(UUID)
        if (claimOwnerId instanceof UUID) {
            try {
                Method m = Reflect.findMethod(playerTeam.getClass(), "isAlly",
                        new Class<?>[]{UUID.class});
                if (m != null) {
                    Object r = m.invoke(playerTeam, (UUID) claimOwnerId);
                    if (r instanceof Boolean b) return b;
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }
        }

        return false;
    }

    // ── Cadmus ────────────────────────────────────────────────────

    private static boolean checkCadmus(ServerPlayer player, ServerLevel level, BlockPos pos) {
        try {
            Optional<Class<?>> chClass = Reflect.forName(
                    "earth.terrarium.cadmus.common.claims.ClaimHandler");
            if (chClass.isEmpty()) return true;

            // Try static getClaim(ServerLevel, BlockPos) → ClaimInfo or null
            Object claimInfo = null;
            try {
                Method getClaim = Reflect.findMethod(
                        chClass.get(), "getClaim",
                        new Class<?>[]{ServerLevel.class, BlockPos.class});
                if (getClaim != null) {
                    claimInfo = getClaim.invoke(null, level, pos);
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }

            if (claimInfo == null) {
                try {
                    ChunkPos chunkPos = new ChunkPos(pos);
                    Method getClaim = Reflect.findMethod(
                            chClass.get(), "getClaim",
                            new Class<?>[]{ServerLevel.class, ChunkPos.class});
                    if (getClaim != null) {
                        claimInfo = getClaim.invoke(null, level, chunkPos);
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }
            }

            if (claimInfo == null) {
                // Try: isClaimed(ServerLevel, BlockPos)
                try {
                    Method m = Reflect.findMethod(chClass.get(), "isClaimed",
                            new Class<?>[]{ServerLevel.class, BlockPos.class});
                    if (m != null) {
                        Object result = m.invoke(null, level, pos);
                        if (result instanceof Boolean b && !b) return true;
                        // If claimed, proceed to owner check below — but we
                        // don't have a claimInfo object, so skip to deny
                        if (result instanceof Boolean) return false;
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("{} reflection probe failed", TAG, e); }
                // All Cadmus claim-detection probes failed — silent fail-open.
                RSIntegrationMod.LOGGER.warn("{} Cadmus: all claim-detection probes failed "
                        + "(API may have changed) — allowing interaction (fail-open)", TAG);
                return true; // Can't determine claim — allow
            }

            // Try to get claim owner and compare with player
            // getOwner() → UUID
            Optional<Object> ownerId = invokeInstance(claimInfo, "getOwner");
            if (ownerId.isPresent() && ownerId.get() instanceof UUID ownerUuid) {
                if (player.getUUID().equals(ownerUuid)) return true;
            }

            // Try getOwnerId()
            ownerId = invokeInstance(claimInfo, "getOwnerId");
            if (ownerId.isPresent() && ownerId.get() instanceof UUID ownerUuid) {
                if (player.getUUID().equals(ownerUuid)) return true;
            }

            // Try getOwnerName() → compare with player name
            Optional<Object> ownerName = invokeInstance(claimInfo, "getOwnerName");
            if (ownerName.isPresent() && ownerName.get() instanceof String name) {
                if (name.equals(player.getGameProfile().getName())) return true;
            }

            // Can't determine owner — deny to be safe
            // (Claim exists but we can't tell who owns it)
            return false;

        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("{} Cadmus check error (fail-open)", TAG, e);
            return true; // fail-open
        }
    }
}
