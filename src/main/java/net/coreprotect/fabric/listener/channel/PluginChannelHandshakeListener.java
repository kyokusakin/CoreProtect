package net.coreprotect.fabric.listener.channel;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.permission.CoreProtectPermissions;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PluginChannelHandshakeListener {
    public static final String pluginChannel = "coreprotect:handshake";
    private static final int NETWORKING_PROTOCOL_VERSION = 1;
    private static final PluginChannelHandshakeListener INSTANCE = new PluginChannelHandshakeListener();

    private final Set<UUID> pluginChannelPlayers = ConcurrentHashMap.newKeySet();
    private volatile boolean initialized;

    private PluginChannelHandshakeListener() {
    }

    public static PluginChannelHandshakeListener getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        PayloadTypeRegistry.playC2S().register(HandshakeC2SPayload.ID, HandshakeC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HandshakeS2CPayload.ID, HandshakeS2CPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(HandshakeC2SPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (!CoreProtectPermissions.canUseNetworking(player.getCommandSource(), false)) {
                return;
            }

            if (isDebugEnabled()) {
                CoreProtectFabricMod.LOGGER.info("CoreProtect handshake: raw={}, modVersion={}, modId={}, protocol={}", payload, payload.modVersion(), payload.modId(), payload.protocolVersion());
            }

            if (payload.protocolVersion() != NETWORKING_PROTOCOL_VERSION) {
                CoreProtectFabricMod.LOGGER.info(
                    "CoreProtect networking connection rejected for {} ({} v{}): protocol {} != {}",
                    player.getName().getString(),
                    payload.modId(),
                    payload.modVersion(),
                    payload.protocolVersion(),
                    NETWORKING_PROTOCOL_VERSION
                );
                return;
            }

            pluginChannelPlayers.add(player.getUuid());
            CoreProtectFabricMod.LOGGER.info(
                "CoreProtect networking connection registered for {} ({} v{})",
                player.getName().getString(),
                payload.modId(),
                payload.modVersion()
            );
            ServerPlayNetworking.send(player, new HandshakeS2CPayload(true));
        });
        initialized = true;
    }

    public Set<UUID> getPluginChannelPlayers() {
        return pluginChannelPlayers;
    }

    public boolean isPluginChannelPlayer(ServerCommandSource source) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return false;
        }
        return isPluginChannelPlayer(player);
    }

    public boolean isPluginChannelPlayer(ServerPlayerEntity player) {
        return pluginChannelPlayers.contains(player.getUuid());
    }

    public void unregisterPlayer(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        pluginChannelPlayers.remove(player.getUuid());
    }

    private boolean isDebugEnabled() {
        return CoreProtectFabricMod.getRuntime() != null
            && CoreProtectFabricMod.getRuntime().config() != null
            && CoreProtectFabricMod.getRuntime().config().networkDebug();
    }

    public record HandshakeC2SPayload(String modVersion, String modId, int protocolVersion) implements CustomPayload {
        public static final Id<HandshakeC2SPayload> ID = new Id<>(Identifier.of("coreprotect", "handshake"));
        public static final PacketCodec<PacketByteBuf, HandshakeC2SPayload> CODEC = CustomPayload.codecOf(HandshakeC2SPayload::write, HandshakeC2SPayload::new);

        public HandshakeC2SPayload(PacketByteBuf buf) {
            this(buf.readString(), buf.readString(), buf.readVarInt());
        }

        public void write(PacketByteBuf buf) {
            buf.writeString(modVersion);
            buf.writeString(modId);
            buf.writeVarInt(protocolVersion);
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record HandshakeS2CPayload(boolean registered) implements CustomPayload {
        public static final Id<HandshakeS2CPayload> ID = new Id<>(Identifier.of("coreprotect", "handshake"));
        public static final PacketCodec<PacketByteBuf, HandshakeS2CPayload> CODEC = CustomPayload.codecOf(HandshakeS2CPayload::write, HandshakeS2CPayload::new);

        public HandshakeS2CPayload(PacketByteBuf buf) {
            this(buf.readBoolean());
        }

        public void write(PacketByteBuf buf) {
            buf.writeBoolean(registered);
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
