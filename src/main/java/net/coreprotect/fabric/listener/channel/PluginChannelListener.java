package net.coreprotect.fabric.listener.channel;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.command.CoreProtectText;
import net.coreprotect.language.Phrase;
import net.coreprotect.language.Selector;
import net.coreprotect.fabric.permission.CoreProtectPermissions;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

public final class PluginChannelListener {
    public static final String pluginChannel = "coreprotect:data";
    private static final PluginChannelListener INSTANCE = new PluginChannelListener();

    private volatile boolean initialized;

    private PluginChannelListener() {
    }

    public static PluginChannelListener getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        PayloadTypeRegistry.playS2C().register(CoreProtectDataPayload.ID, CoreProtectDataPayload.CODEC);
        initialized = true;
    }

    public void sendData(ServerCommandSource source, long timeAgo, String phraseSelector, String resultUser, String target, int amount, int x, int y, int z, String worldName, String rbFormat, boolean isContainer, boolean added) throws IOException {
        if (!PluginChannelHandshakeListener.getInstance().isPluginChannelPlayer(source)) {
            return;
        }

        ByteArrayOutputStream msgBytes = new ByteArrayOutputStream();
        DataOutputStream msgOut = new DataOutputStream(msgBytes);
        msgOut.writeInt(1);
        msgOut.writeLong(timeAgo * 1000L);
        msgOut.writeUTF(phraseSelector);
        msgOut.writeUTF(resultUser);
        msgOut.writeUTF(target);
        msgOut.writeInt(amount);
        msgOut.writeInt(x);
        msgOut.writeInt(y);
        msgOut.writeInt(z);
        msgOut.writeUTF(worldName);
        msgOut.writeBoolean(rbFormat != null && !rbFormat.isEmpty());
        msgOut.writeBoolean(isContainer);
        msgOut.writeBoolean(added);

        if (isDebugEnabled()) {
            CoreProtectFabricMod.LOGGER.info(
                "CoreProtect networking send(type=1): time={}, selector={}, user={}, target={}, amount={}, x={}, y={}, z={}, world={}, rolledback={}, container={}, added={}",
                timeAgo * 1000L,
                phraseSelector,
                resultUser,
                target,
                amount,
                x,
                y,
                z,
                worldName,
                rbFormat != null && !rbFormat.isEmpty(),
                isContainer,
                added
            );
        }

        send(source, msgBytes.toByteArray());
    }

    public void sendInfoData(ServerCommandSource source, long timeAgo, String phraseSelector, String resultUser, int amount, int x, int y, int z, String worldName) throws IOException {
        if (!PluginChannelHandshakeListener.getInstance().isPluginChannelPlayer(source)) {
            return;
        }

        ByteArrayOutputStream msgBytes = new ByteArrayOutputStream();
        DataOutputStream msgOut = new DataOutputStream(msgBytes);
        msgOut.writeInt(2);
        msgOut.writeLong(timeAgo * 1000L);
        msgOut.writeUTF(phraseSelector);
        msgOut.writeUTF(resultUser);
        msgOut.writeInt(amount);
        msgOut.writeInt(x);
        msgOut.writeInt(y);
        msgOut.writeInt(z);
        msgOut.writeUTF(worldName);

        if (isDebugEnabled()) {
            CoreProtectFabricMod.LOGGER.info(
                "CoreProtect networking send(type=2): time={}, selector={}, user={}, amount={}, x={}, y={}, z={}, world={}",
                timeAgo * 1000L,
                phraseSelector,
                resultUser,
                amount,
                x,
                y,
                z,
                worldName
            );
        }

        send(source, msgBytes.toByteArray());
    }

    public void sendMessageData(ServerCommandSource source, long timeAgo, String resultUser, String message, boolean sign, int x, int y, int z, String worldName) throws IOException {
        if (!PluginChannelHandshakeListener.getInstance().isPluginChannelPlayer(source)) {
            return;
        }

        ByteArrayOutputStream msgBytes = new ByteArrayOutputStream();
        DataOutputStream msgOut = new DataOutputStream(msgBytes);
        msgOut.writeInt(3);
        msgOut.writeLong(timeAgo * 1000L);
        msgOut.writeUTF(resultUser);
        msgOut.writeUTF(message);
        msgOut.writeBoolean(sign);
        msgOut.writeInt(x);
        msgOut.writeInt(y);
        msgOut.writeInt(z);
        msgOut.writeUTF(worldName);

        if (isDebugEnabled()) {
            CoreProtectFabricMod.LOGGER.info(
                "CoreProtect networking send(type=3): time={}, user={}, message={}, sign={}, x={}, y={}, z={}, world={}",
                timeAgo * 1000L,
                resultUser,
                message,
                sign,
                x,
                y,
                z,
                worldName
            );
        }

        send(source, msgBytes.toByteArray());
    }

    public void sendUsernameData(ServerCommandSource source, long timeAgo, String resultUser, String target) throws IOException {
        if (!PluginChannelHandshakeListener.getInstance().isPluginChannelPlayer(source)) {
            return;
        }

        ByteArrayOutputStream msgBytes = new ByteArrayOutputStream();
        DataOutputStream msgOut = new DataOutputStream(msgBytes);
        msgOut.writeInt(4);
        msgOut.writeLong(timeAgo * 1000L);
        msgOut.writeUTF(resultUser);
        msgOut.writeUTF(target);

        if (isDebugEnabled()) {
            CoreProtectFabricMod.LOGGER.info(
                "CoreProtect networking send(type=4): time={}, user={}, target={}",
                timeAgo * 1000L,
                resultUser,
                target
            );
        }

        send(source, msgBytes.toByteArray());
    }

    public void sendTest(ServerCommandSource source, String type) throws IOException {
        if (!PluginChannelHandshakeListener.getInstance().isPluginChannelPlayer(source)) {
            return;
        }

        Random random = new Random();
        int timeAgo = random.nextInt(20);
        String resultUser = "Anne";
        int amount = 5;
        int x = random.nextInt(10);
        int y = random.nextInt(10);
        int z = random.nextInt(10);
        String worldName = "minecraft:overworld";
        String rbFormat = "test";
        String message = "This is a test";
        boolean sign = true;

        switch (type) {
            case "2" -> sendInfoData(source, timeAgo, Phrase.getPhraseSelector(Phrase.LOOKUP_LOGIN, Selector.FIRST), resultUser, amount, x, y, z, worldName);
            case "3" -> sendMessageData(source, timeAgo, resultUser, message, sign, x, y, z, worldName);
            case "4" -> sendUsernameData(source, timeAgo, resultUser, "Arne");
            default -> sendData(source, timeAgo, Phrase.getPhraseSelector(Phrase.LOOKUP_CONTAINER, Selector.FIRST), resultUser, "clay_ball", amount, x, y, z, worldName, rbFormat, false, true);
        }

        source.sendFeedback(() -> CoreProtectText.prefixed(Phrase.build(Phrase.NETWORK_TEST)), false);
    }

    private void send(ServerCommandSource source, byte[] msgBytes) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return;
        }

        sendCoreProtectData(player, msgBytes);
    }

    private void sendCoreProtectData(ServerPlayerEntity player, byte[] data) {
        if (!CoreProtectPermissions.canUseNetworking(player.getCommandSource(), false)) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, CoreProtectDataPayload.ID)) {
            return;
        }

        ServerPlayNetworking.send(player, new CoreProtectDataPayload(data));
    }

    private boolean isDebugEnabled() {
        return CoreProtectFabricMod.getRuntime() != null
            && CoreProtectFabricMod.getRuntime().config() != null
            && CoreProtectFabricMod.getRuntime().config().networkDebug();
    }

    public record CoreProtectDataPayload(byte[] data) implements CustomPayload {
        public static final Id<CoreProtectDataPayload> ID = new Id<>(Identifier.of("coreprotect", "data"));
        public static final PacketCodec<PacketByteBuf, CoreProtectDataPayload> CODEC = CustomPayload.codecOf(CoreProtectDataPayload::write, CoreProtectDataPayload::new);

        public CoreProtectDataPayload(PacketByteBuf buf) {
            this(readRemaining(buf));
        }

        public void write(PacketByteBuf buf) {
            buf.writeBytes(data);
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }

        private static byte[] readRemaining(PacketByteBuf buf) {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        }
    }
}
