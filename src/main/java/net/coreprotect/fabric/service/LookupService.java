package net.coreprotect.fabric.service;

import net.coreprotect.fabric.db.CoreProtectDatabase;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.util.LoggedItemChange;
import net.coreprotect.fabric.util.QueryBounds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

public final class LookupService {
    private static final double TARGET_DISTANCE = 20.0D;
    private static final List<CoreProtectEventType> ENTITY_HISTORY_TYPES = List.of(
        CoreProtectEventType.ENTITY_PLACE,
        CoreProtectEventType.ENTITY_BREAK,
        CoreProtectEventType.ENTITY_USE
    );

    private final CoreProtectDatabase database;

    public LookupService(CoreProtectDatabase database) {
        this.database = database;
    }

    public List<Text> describeTargetedBlockHistory(ServerPlayerEntity player, int limit) {
        return describeTargetedBlockHistory(
            player,
            limit,
            null,
            "CoreProtect block history",
            "No block history recorded at this position."
        );
    }

    public List<Text> describeTargetedBlockHistory(ServerPlayerEntity player, int limit, List<CoreProtectEventType> eventTypes, String title, String emptyMessage) {
        BlockPos targetPos = findTargetedBlockPos(player);
        if (targetPos == null) {
            return List.of(Text.literal("No block targeted within 20 blocks.").formatted(Formatting.RED));
        }

        return describeBlockHistory((ServerWorld) player.getEntityWorld(), targetPos, limit, eventTypes, title, emptyMessage);
    }

    public List<Text> describeTargetedEntityHistory(ServerPlayerEntity player, int limit) {
        return describeTargetedEntityHistory(
            player,
            limit,
            ENTITY_HISTORY_TYPES,
            "CoreProtect entity history",
            "No entity history recorded for the targeted entity."
        );
    }

    public List<Text> describeTargetedEntityHistory(ServerPlayerEntity player, int limit, List<CoreProtectEventType> eventTypes, String title, String emptyMessage) {
        Entity entity = findTargetedEntity(player, TARGET_DISTANCE);
        if (entity == null) {
            return List.of(Text.literal("No entity targeted within 20 blocks.").formatted(Formatting.RED));
        }

        return describeEntityHistory((ServerWorld) player.getEntityWorld(), entity, limit, eventTypes, title, emptyMessage);
    }

    public BlockPos findTargetedBlockPos(ServerPlayerEntity player) {
        HitResult hitResult = player.raycast(20.0, 0.0F, false);
        if (hitResult.getType() != HitResult.Type.BLOCK || !(hitResult instanceof BlockHitResult blockHitResult)) {
            return null;
        }
        return blockHitResult.getBlockPos();
    }

    public List<StoredEventRecord> getTargetedBlockHistory(ServerPlayerEntity player, int limit, List<CoreProtectEventType> eventTypes) {
        BlockPos targetPos = findTargetedBlockPos(player);
        if (targetPos == null) {
            return List.of();
        }

        ServerWorld world = (ServerWorld) player.getEntityWorld();
        return getBlockHistory(world, targetPos, limit, eventTypes);
    }

    public List<StoredEventRecord> getTargetedEntityHistory(ServerPlayerEntity player, int limit, List<CoreProtectEventType> eventTypes) {
        Entity entity = findTargetedEntity(player, TARGET_DISTANCE);
        if (entity == null) {
            return List.of();
        }

        return getEntityHistory((ServerWorld) player.getEntityWorld(), entity, limit, eventTypes);
    }

    public List<StoredEventRecord> getBlockHistory(ServerWorld world, BlockPos pos, int limit, List<CoreProtectEventType> eventTypes) {
        return eventTypes == null || eventTypes.isEmpty()
            ? database.lookupBlockHistory(world.getRegistryKey().getValue().toString(), pos, limit)
            : database.lookupBlockHistory(world.getRegistryKey().getValue().toString(), pos, limit, eventTypes);
    }

    public List<StoredEventRecord> getEntityHistory(ServerWorld world, Entity entity, int limit, List<CoreProtectEventType> eventTypes) {
        return database.lookupBlockHistory(world.getRegistryKey().getValue().toString(), historyPos(entity), limit, eventTypes);
    }

    public List<Text> describeBlockHistory(ServerWorld world, BlockPos pos, int limit) {
        return describeBlockHistory(world, pos, limit, null, "CoreProtect block history", "No block history recorded at this position.");
    }

    public List<Text> describeBlockHistory(ServerWorld world, BlockPos pos, int limit, List<CoreProtectEventType> eventTypes, String title, String emptyMessage) {
        List<StoredEventRecord> events = eventTypes == null || eventTypes.isEmpty()
            ? database.lookupBlockHistory(world.getRegistryKey().getValue().toString(), pos, limit)
            : database.lookupBlockHistory(world.getRegistryKey().getValue().toString(), pos, limit, eventTypes);
        List<Text> lines = new ArrayList<>();
        lines.add(header(title, world, pos));
        if (events.isEmpty()) {
            lines.add(Text.literal(emptyMessage).formatted(Formatting.GRAY));
            return lines;
        }

        long now = System.currentTimeMillis();
        for (StoredEventRecord event : events) {
            lines.add(formatEvent(event, now, false));
        }
        return lines;
    }

    public List<Text> describeEntityHistory(ServerWorld world, Entity entity, int limit) {
        return describeEntityHistory(
            world,
            entity,
            limit,
            ENTITY_HISTORY_TYPES,
            "CoreProtect entity history",
            "No entity history recorded for the targeted entity."
        );
    }

    public List<Text> describeEntityHistory(ServerWorld world, Entity entity, int limit, List<CoreProtectEventType> eventTypes, String title, String emptyMessage) {
        return describeBlockHistory(world, historyPos(entity), limit, eventTypes, title, emptyMessage);
    }

    public List<Text> describeNearbyHistory(ServerWorld world, BlockPos center, int radius, int seconds, int limit, String actorName) {
        return describeNearbyHistory(world, center, radius, seconds, limit, actorName, null);
    }

    public List<Text> describeNearbyHistory(ServerWorld world, BlockPos center, int radius, int seconds, int limit, String actorName, List<CoreProtectEventType> eventTypes) {
        List<StoredEventRecord> events = database.lookupNearby(world.getRegistryKey().getValue().toString(), center, radius, seconds, limit, actorName, eventTypes);
        List<Text> lines = new ArrayList<>();
        String suffix = actorName == null ? "" : " actor=" + actorName;
        String actionSuffix = describeFilter(eventTypes);
        lines.add(Text.literal("CoreProtect nearby lookup r=" + radius + " t=" + seconds + "s" + suffix + actionSuffix).formatted(Formatting.AQUA));
        if (events.isEmpty()) {
            lines.add(Text.literal("No matching events found.").formatted(Formatting.GRAY));
            return lines;
        }

        long now = System.currentTimeMillis();
        for (StoredEventRecord event : events) {
            lines.add(formatEvent(event, now, true));
        }
        return lines;
    }

    public List<Text> describeScopedHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        List<String> actorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return describeScopedHistory(worldKey, center, radius, null, minimumSeconds, maximumSeconds, limit, 0, 1, 1, actorNames, null, eventTypes, includeTargets, excludeTargets);
    }

    public List<Text> describeScopedHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return describeScopedHistory(worldKey, center, radius, null, minimumSeconds, maximumSeconds, limit, 0, 1, 1, actorNames, excludeActorNames, eventTypes, includeTargets, excludeTargets);
    }

    public List<Text> describeScopedHistory(
        QueryBounds bounds,
        String scopeLabel,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        List<String> actorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return describeScopedHistory(bounds.worldKey(), null, null, bounds, minimumSeconds, maximumSeconds, limit, 0, 1, 1, actorNames, null, eventTypes, includeTargets, excludeTargets, scopeLabel);
    }

    public List<Text> describeScopedHistory(
        QueryBounds bounds,
        String scopeLabel,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return describeScopedHistory(bounds.worldKey(), null, null, bounds, minimumSeconds, maximumSeconds, limit, 0, 1, 1, actorNames, excludeActorNames, eventTypes, includeTargets, excludeTargets, scopeLabel);
    }

    public List<Text> describeScopedHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        QueryBounds bounds,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        int offset,
        int page,
        int totalPages,
        List<String> actorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return describeScopedHistory(worldKey, center, radius, bounds, minimumSeconds, maximumSeconds, limit, offset, page, totalPages, actorNames, null, eventTypes, includeTargets, excludeTargets, null);
    }

    public List<Text> describeScopedHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        QueryBounds bounds,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        int offset,
        int page,
        int totalPages,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return describeScopedHistory(worldKey, center, radius, bounds, minimumSeconds, maximumSeconds, limit, offset, page, totalPages, actorNames, excludeActorNames, eventTypes, includeTargets, excludeTargets, null);
    }

    public List<Text> describeScopedHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        QueryBounds bounds,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        int offset,
        int page,
        int totalPages,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets,
        String scopeLabel
    ) {
        List<StoredEventRecord> events = getScopedHistory(
            worldKey,
            center,
            radius,
            bounds,
            minimumSeconds,
            maximumSeconds,
            limit,
            offset,
            actorNames,
            excludeActorNames,
            eventTypes,
            includeTargets,
            excludeTargets
        );
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal(
            "CoreProtect lookup "
                + (scopeLabel == null ? describeScope(worldKey, center, radius) : scopeLabel)
                + " t=" + describeTimeWindow(minimumSeconds, maximumSeconds)
                + " page=" + page + "/" + totalPages
                + describeActors(actorNames, excludeActorNames)
                + describeFilter(eventTypes)
                + describeTargetFilters(includeTargets, excludeTargets)
        ).formatted(Formatting.AQUA));
        if (events.isEmpty()) {
            lines.add(Text.literal("No matching events found.").formatted(Formatting.GRAY));
            return lines;
        }

        long now = System.currentTimeMillis();
        for (StoredEventRecord event : events) {
            lines.add(formatEvent(event, now, true));
        }
        return lines;
    }

    public int countScopedHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        QueryBounds bounds,
        int minimumSeconds,
        int maximumSeconds,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        if (bounds == null || !bounds.hasExactPositions()) {
            return bounds == null
                ? database.countHistory(worldKey, center, radius, minimumSeconds, maximumSeconds, actorNames, excludeActorNames, eventTypes, includeTargets, excludeTargets)
                : database.countHistory(bounds.worldKey(), bounds.minimum(), bounds.maximum(), minimumSeconds, maximumSeconds, actorNames, excludeActorNames, eventTypes, includeTargets, excludeTargets);
        }
        return getExactScopedHistory(bounds, minimumSeconds, maximumSeconds, actorNames, excludeActorNames, eventTypes, includeTargets, excludeTargets).size();
    }

    public List<StoredEventRecord> getScopedHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        QueryBounds bounds,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        int offset,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        if (bounds == null || !bounds.hasExactPositions()) {
            return bounds == null
                ? database.lookupHistory(worldKey, center, radius, minimumSeconds, maximumSeconds, limit, offset, actorNames, excludeActorNames, eventTypes, includeTargets, excludeTargets)
                : database.lookupHistory(bounds.worldKey(), bounds.minimum(), bounds.maximum(), minimumSeconds, maximumSeconds, limit, offset, actorNames, excludeActorNames, eventTypes, includeTargets, excludeTargets);
        }

        List<StoredEventRecord> filtered = getExactScopedHistory(bounds, minimumSeconds, maximumSeconds, actorNames, excludeActorNames, eventTypes, includeTargets, excludeTargets);
        int start = Math.max(0, Math.min(offset, filtered.size()));
        int end = Math.max(start, Math.min(filtered.size(), start + Math.max(0, limit)));
        return filtered.subList(start, end);
    }

    private List<StoredEventRecord> getExactScopedHistory(
        QueryBounds bounds,
        int minimumSeconds,
        int maximumSeconds,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        List<StoredEventRecord> candidates = database.lookupHistory(
            bounds.worldKey(),
            bounds.minimum(),
            bounds.maximum(),
            minimumSeconds,
            maximumSeconds,
            Integer.MAX_VALUE,
            0,
            actorNames,
            excludeActorNames,
            eventTypes,
            includeTargets,
            excludeTargets
        );
        List<StoredEventRecord> filtered = new ArrayList<>();
        for (StoredEventRecord candidate : candidates) {
            if (candidate.hasPosition() && bounds.contains(candidate.blockPos())) {
                filtered.add(candidate);
            }
        }
        return filtered;
    }

    private Text header(String title, ServerWorld world, BlockPos pos) {
        return Text.literal(title + " @ " + world.getRegistryKey().getValue() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ())
            .formatted(Formatting.AQUA);
    }

    private Entity findTargetedEntity(ServerPlayerEntity player, double distance) {
        Vec3d start = player.getCameraPosVec(1.0F);
        Vec3d direction = player.getRotationVec(1.0F);
        Vec3d end = start.add(direction.multiply(distance));
        Box searchBox = player.getBoundingBox().stretch(direction.multiply(distance)).expand(1.0D);
        EntityHitResult hitResult = ProjectileUtil.raycast(
            player,
            start,
            end,
            searchBox,
            entity -> !entity.isSpectator() && entity.isAlive(),
            distance * distance
        );
        return hitResult == null ? null : hitResult.getEntity();
    }

    private BlockPos historyPos(Entity entity) {
        if (entity instanceof AbstractDecorationEntity) {
            return ((AbstractDecorationEntity) entity).getAttachedBlockPos();
        }
        return entity.getBlockPos();
    }

    private Text formatEvent(StoredEventRecord event, long now, boolean includePosition) {
        String age = timeAgo(now - event.timestamp());
        String actor = event.actorName() == null ? "system" : event.actorName();
        String target = describeTarget(event);

        MutableText line = Text.literal(age + " ").formatted(Formatting.DARK_GRAY)
            .append(Text.literal(symbol(event.type()) + " ").formatted(symbolColor(event.type())))
            .append(Text.literal(actor).formatted(Formatting.AQUA))
            .append(Text.literal(" " + verb(event.type())).formatted(Formatting.WHITE));

        if (!target.isBlank()) {
            line.append(Text.literal(" " + target).formatted(Formatting.YELLOW));
        }

        if (includePosition && event.hasPosition()) {
            BlockPos pos = event.blockPos();
            line.append(Text.literal(" @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ())
                .styled(style -> style
                    .withFormatting(Formatting.GRAY, Formatting.UNDERLINE)
                    .withClickEvent(new ClickEvent.RunCommand("/co teleport " + event.worldKey() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                ));
        }

        if (event.rolledBack()) {
            line.append(Text.literal(" [rolled back]").formatted(Formatting.STRIKETHROUGH, Formatting.RED));
        }

        return line;
    }

    private String simplifyTarget(String target) {
        if (target == null || target.isBlank()) {
            return "";
        }

        if (target.startsWith("minecraft:")) {
            return target.substring("minecraft:".length());
        }

        return target;
    }

    private String describeTarget(StoredEventRecord event) {
        if (isItemEvent(event.type())) {
            return LoggedItemChange.parse(event.target(), event.payload()).lookupTarget();
        }
        return simplifyTarget(event.target());
    }

    private String verb(CoreProtectEventType type) {
        switch (type) {
            case BLOCK_PLACE:
            case ENTITY_PLACE:
                return "placed";
            case ITEM_PICKUP:
                return "picked up";
            case ITEM_THROW:
                return "threw";
            case ITEM_BUY:
                return "bought";
            case ITEM_CREATE:
                return "crafted";
            case BLOCK_BREAK:
            case ENTITY_BREAK:
                return "broke";
            case ITEM_DROP:
                return "dropped";
            case ITEM_SHOOT:
                return "shot";
            case ITEM_SELL:
                return "sold";
            case ITEM_DESTROY:
                return "consumed";
            case BLOCK_USE:
            case ENTITY_USE:
                return "used";
            case ENTITY_KILL:
                return "killed";
            case SIGN_CHANGE:
                return "edited";
            case CONTAINER_TRANSACTION:
                return "changed";
            case PLAYER_CHAT:
                return "said";
            case PLAYER_COMMAND:
                return "ran";
            case PLAYER_JOIN:
                return "joined";
            case PLAYER_QUIT:
                return "quit";
            case USERNAME_CHANGE:
                return "changed username";
            case SERVER_START:
                return "started";
            case SERVER_STOP:
                return "stopped";
            default:
                throw new IllegalStateException("Unhandled event type: " + type);
        }
    }

    private String symbol(CoreProtectEventType type) {
        switch (type) {
            case BLOCK_PLACE:
            case ENTITY_PLACE:
            case PLAYER_JOIN:
            case ITEM_PICKUP:
            case ITEM_BUY:
            case ITEM_CREATE:
            case SERVER_START:
                return "+";
            case BLOCK_BREAK:
            case ENTITY_BREAK:
            case PLAYER_QUIT:
            case ITEM_DROP:
            case ITEM_THROW:
            case ITEM_SHOOT:
            case ITEM_SELL:
            case ITEM_DESTROY:
            case SERVER_STOP:
                return "-";
            case BLOCK_USE:
            case ENTITY_USE:
                return "*";
            case ENTITY_KILL:
                return "x";
            case SIGN_CHANGE:
                return "=";
            case CONTAINER_TRANSACTION:
                return "#";
            case USERNAME_CHANGE:
                return "~";
            case PLAYER_CHAT:
                return "\"";
            case PLAYER_COMMAND:
                return ">";
            default:
                throw new IllegalStateException("Unhandled event type: " + type);
        }
    }

    private Formatting symbolColor(CoreProtectEventType type) {
        switch (type) {
            case BLOCK_PLACE:
            case ENTITY_PLACE:
            case PLAYER_JOIN:
            case ITEM_PICKUP:
            case ITEM_BUY:
            case ITEM_CREATE:
            case SERVER_START:
                return Formatting.GREEN;
            case BLOCK_BREAK:
            case ENTITY_BREAK:
            case PLAYER_QUIT:
            case ITEM_DROP:
            case ITEM_THROW:
            case ITEM_SHOOT:
            case ITEM_SELL:
            case ITEM_DESTROY:
            case SERVER_STOP:
                return Formatting.RED;
            case BLOCK_USE:
            case ENTITY_USE:
                return Formatting.GOLD;
            case ENTITY_KILL:
                return Formatting.DARK_RED;
            case SIGN_CHANGE:
                return Formatting.LIGHT_PURPLE;
            case CONTAINER_TRANSACTION:
                return Formatting.BLUE;
            case USERNAME_CHANGE:
                return Formatting.AQUA;
            case PLAYER_CHAT:
            case PLAYER_COMMAND:
                return Formatting.YELLOW;
            default:
                throw new IllegalStateException("Unhandled event type: " + type);
        }
    }

    private String timeAgo(long ageMs) {
        long seconds = Math.max(0L, ageMs / 1000L);
        if (seconds < 60L) {
            return seconds + "s";
        }

        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + "m";
        }

        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + "h";
        }

        return (hours / 24L) + "d";
    }

    private String describeTimeWindow(int minimumSeconds, int maximumSeconds) {
        if (minimumSeconds <= 0) {
            return maximumSeconds + "s";
        }
        return minimumSeconds + "s-" + maximumSeconds + "s";
    }

    private String describeFilter(List<CoreProtectEventType> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return "";
        }

        Set<String> labels = new LinkedHashSet<>();
        boolean hasContainer = eventTypes.contains(CoreProtectEventType.CONTAINER_TRANSACTION);
        boolean hasItem = containsItemEvents(eventTypes);
        if (hasContainer && hasItem) {
            labels.add("inventory");
        }
        for (CoreProtectEventType eventType : eventTypes) {
            if (hasContainer && hasItem && (eventType == CoreProtectEventType.CONTAINER_TRANSACTION || isItemEvent(eventType))) {
                continue;
            }
            labels.add(filterLabel(eventType));
        }

        StringJoiner joiner = new StringJoiner(",");
        for (String label : labels) {
            joiner.add(label);
        }
        return " actions=" + joiner;
    }

    private String describeScope(String worldKey, BlockPos center, Integer radius) {
        if (center != null && radius != null) {
            return "local@" + center.getX() + "," + center.getY() + "," + center.getZ() + " r=" + radius;
        }
        if (worldKey != null && !worldKey.isBlank()) {
            return "world=" + worldKey;
        }
        return "global";
    }

    private String describeActors(List<String> actorNames, List<String> excludeActorNames) {
        StringBuilder builder = new StringBuilder();
        if (actorNames != null && !actorNames.isEmpty()) {
            StringJoiner joiner = new StringJoiner(",");
            for (String actorName : actorNames) {
                joiner.add(actorName);
            }
            builder.append(" actor=").append(joiner);
        }
        if (excludeActorNames != null && !excludeActorNames.isEmpty()) {
            StringJoiner joiner = new StringJoiner(",");
            for (String actorName : excludeActorNames) {
                joiner.add(actorName);
            }
            builder.append(" exclude-user=").append(joiner);
        }
        return builder.toString();
    }

    private String describeTargetFilters(List<String> includeTargets, List<String> excludeTargets) {
        StringBuilder builder = new StringBuilder();
        if (includeTargets != null && !includeTargets.isEmpty()) {
            builder.append(" include=");
            appendCsv(builder, includeTargets);
        }
        if (excludeTargets != null && !excludeTargets.isEmpty()) {
            builder.append(" exclude=");
            appendCsv(builder, excludeTargets);
        }
        return builder.toString();
    }

    private void appendCsv(StringBuilder builder, List<String> values) {
        StringJoiner joiner = new StringJoiner(",");
        for (String value : values) {
            joiner.add(value);
        }
        builder.append(joiner);
    }

    private String filterLabel(CoreProtectEventType eventType) {
        switch (eventType) {
            case BLOCK_BREAK:
            case BLOCK_PLACE:
            case ENTITY_BREAK:
            case ENTITY_PLACE:
                return "block";
            case ITEM_PICKUP:
            case ITEM_DROP:
            case ITEM_THROW:
            case ITEM_SHOOT:
            case ITEM_BUY:
            case ITEM_SELL:
            case ITEM_CREATE:
            case ITEM_DESTROY:
                return "item";
            case BLOCK_USE:
            case ENTITY_USE:
                return "click";
            case ENTITY_KILL:
                return "kill";
            case SIGN_CHANGE:
                return "sign";
            case CONTAINER_TRANSACTION:
                return "container";
            case USERNAME_CHANGE:
                return "username";
            case PLAYER_CHAT:
                return "chat";
            case PLAYER_COMMAND:
                return "command";
            case PLAYER_JOIN:
            case PLAYER_QUIT:
                return "session";
            case SERVER_START:
                return "server-start";
            case SERVER_STOP:
                return "server-stop";
            default:
                return eventType.name().toLowerCase();
        }
    }

    private boolean containsItemEvents(List<CoreProtectEventType> eventTypes) {
        for (CoreProtectEventType eventType : eventTypes) {
            if (isItemEvent(eventType)) {
                return true;
            }
        }
        return false;
    }

    private boolean isItemEvent(CoreProtectEventType eventType) {
        return eventType == CoreProtectEventType.ITEM_PICKUP
            || eventType == CoreProtectEventType.ITEM_DROP
            || eventType == CoreProtectEventType.ITEM_THROW
            || eventType == CoreProtectEventType.ITEM_SHOOT
            || eventType == CoreProtectEventType.ITEM_BUY
            || eventType == CoreProtectEventType.ITEM_SELL
            || eventType == CoreProtectEventType.ITEM_CREATE
            || eventType == CoreProtectEventType.ITEM_DESTROY;
    }
}
