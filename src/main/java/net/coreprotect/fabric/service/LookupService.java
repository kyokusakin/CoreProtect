package net.coreprotect.fabric.service;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.db.CoreProtectDatabase;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.language.PhraseService;
import net.coreprotect.language.Phrase;
import net.coreprotect.language.Selector;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.util.LoggedSignState;
import net.coreprotect.fabric.util.LoggedItemChange;
import net.coreprotect.fabric.util.QueryBounds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public final class LookupService {
    private static final String COMMAND_PREFIX = "CoreProtect - ";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private static final DecimalFormat ELAPSED_DECIMAL = new DecimalFormat("0.00", new DecimalFormatSymbols(java.util.Locale.ROOT));
    private static final double TARGET_DISTANCE = 20.0D;
    private static final List<CoreProtectEventType> ENTITY_HISTORY_TYPES = List.of(
        CoreProtectEventType.ENTITY_PLACE,
        CoreProtectEventType.ENTITY_BREAK,
        CoreProtectEventType.ENTITY_USE
    );
    private static final List<CoreProtectEventType> ENTITY_INVENTORY_HISTORY_TYPES = List.of(
        CoreProtectEventType.CONTAINER_TRANSACTION,
        CoreProtectEventType.ITEM_PICKUP,
        CoreProtectEventType.ITEM_DROP,
        CoreProtectEventType.ITEM_THROW,
        CoreProtectEventType.ITEM_SHOOT,
        CoreProtectEventType.ITEM_BUY,
        CoreProtectEventType.ITEM_SELL,
        CoreProtectEventType.ITEM_CREATE,
        CoreProtectEventType.ITEM_DESTROY
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
            "CoreProtect",
            COMMAND_PREFIX + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.FIRST)
        );
    }

    public List<Text> describeTargetedBlockHistory(ServerPlayerEntity player, int limit, List<CoreProtectEventType> eventTypes, String title, String emptyMessage) {
        BlockPos targetPos = findTargetedBlockPos(player);
        if (targetPos == null) {
            return List.of(Text.literal(COMMAND_PREFIX + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.FIRST)).formatted(Formatting.RED));
        }

        return describeBlockHistory((ServerWorld) player.getEntityWorld(), targetPos, limit, eventTypes, title, emptyMessage);
    }

    public List<Text> describeTargetedEntityHistory(ServerPlayerEntity player, int limit) {
        return describeTargetedEntityHistory(
            player,
            limit,
            ENTITY_HISTORY_TYPES,
            "CoreProtect",
            COMMAND_PREFIX + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.FIRST)
        );
    }

    public List<Text> describeTargetedEntityHistory(ServerPlayerEntity player, int limit, List<CoreProtectEventType> eventTypes, String title, String emptyMessage) {
        Entity entity = findTargetedEntity(player, TARGET_DISTANCE);
        if (entity == null) {
            return List.of(Text.literal(COMMAND_PREFIX + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.FIRST)).formatted(Formatting.RED));
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

    public BlockPos findTargetedEntityHistoryPos(ServerPlayerEntity player, double distance) {
        Entity entity = findTargetedEntity(player, distance);
        return entity == null ? null : historyPos(entity);
    }

    public List<StoredEventRecord> loadBlockHistory(String worldKey, BlockPos pos, int limit, List<CoreProtectEventType> eventTypes) {
        return eventTypes == null || eventTypes.isEmpty()
            ? database.lookupBlockHistory(worldKey, pos, limit)
            : database.lookupBlockHistory(worldKey, pos, limit, eventTypes);
    }

    public List<StoredEventRecord> loadNearbyHistory(String worldKey, BlockPos center, int radius, int seconds, int limit, String actorName, List<CoreProtectEventType> eventTypes) {
        return database.lookupNearby(worldKey, center, radius, seconds, limit, actorName, eventTypes);
    }

    public List<StoredEventRecord> getBlockHistory(ServerWorld world, BlockPos pos, int limit, List<CoreProtectEventType> eventTypes) {
        return eventTypes == null || eventTypes.isEmpty()
            ? database.lookupBlockHistory(world.getRegistryKey().getValue().toString(), pos, limit)
            : database.lookupBlockHistory(world.getRegistryKey().getValue().toString(), pos, limit, eventTypes);
    }

    public List<StoredEventRecord> getEntityHistory(ServerWorld world, Entity entity, int limit, List<CoreProtectEventType> eventTypes) {
        return database.lookupBlockHistory(world.getRegistryKey().getValue().toString(), historyPos(entity), limit, eventTypes);
    }

    public List<StoredEventRecord> getEntityInventoryHistory(ServerWorld world, Entity entity, int limit) {
        if (!supportsEntityInventoryHistory(entity)) {
            return List.of();
        }

        return database.lookupBlockHistory(world.getRegistryKey().getValue().toString(), historyPos(entity), limit, ENTITY_INVENTORY_HISTORY_TYPES);
    }

    public List<Text> describeBlockHistory(ServerWorld world, BlockPos pos, int limit) {
        return describeBlockHistory(world, pos, limit, null, "CoreProtect", COMMAND_PREFIX + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.FIRST));
    }

    public List<Text> describeBlockHistory(ServerWorld world, BlockPos pos, int limit, List<CoreProtectEventType> eventTypes, String title, String emptyMessage) {
        List<StoredEventRecord> events = eventTypes == null || eventTypes.isEmpty()
            ? database.lookupBlockHistory(world.getRegistryKey().getValue().toString(), pos, limit)
            : database.lookupBlockHistory(world.getRegistryKey().getValue().toString(), pos, limit, eventTypes);
        List<Text> lines = new ArrayList<>();
        lines.add(header(title, world, pos, false));
        if (events.isEmpty()) {
            lines.add(Text.literal(resolveEmptyMessage(world, pos, eventTypes, emptyMessage)).formatted(Formatting.GRAY));
            return lines;
        }

        long now = System.currentTimeMillis();
        for (StoredEventRecord event : events) {
            appendFormattedEvent(lines, event, now, false);
        }
        return lines;
    }

    public List<Text> describeEntityHistory(ServerWorld world, Entity entity, int limit) {
        return describeEntityHistory(
            world,
            entity,
            limit,
            ENTITY_HISTORY_TYPES,
            "CoreProtect",
            COMMAND_PREFIX + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.FIRST)
        );
    }

    public List<Text> describeEntityHistory(ServerWorld world, Entity entity, int limit, List<CoreProtectEventType> eventTypes, String title, String emptyMessage) {
        return describeBlockHistory(world, historyPos(entity), limit, eventTypes, title, emptyMessage);
    }

    public List<Text> describeEntityInventoryHistory(ServerWorld world, Entity entity, int limit) {
        if (!supportsEntityInventoryHistory(entity)) {
            return List.of();
        }

        List<StoredEventRecord> events = getEntityInventoryHistory(world, entity, limit);
        if (events.isEmpty()) {
            return List.of();
        }

        List<Text> lines = new ArrayList<>();
        lines.add(header(Phrase.build(Phrase.CONTAINER_HEADER), world, historyPos(entity), false));
        long now = System.currentTimeMillis();
        for (StoredEventRecord event : events) {
            appendFormattedEvent(lines, event, now, false);
        }
        return lines;
    }

    public List<Text> describeNearbyHistory(ServerWorld world, BlockPos center, int radius, int seconds, int limit, String actorName) {
        return describeNearbyHistory(world, center, radius, seconds, limit, actorName, null);
    }

    public List<Text> describeNearbyHistory(ServerWorld world, BlockPos center, int radius, int seconds, int limit, String actorName, List<CoreProtectEventType> eventTypes) {
        List<StoredEventRecord> events = database.lookupNearby(world.getRegistryKey().getValue().toString(), center, radius, seconds, limit, actorName, eventTypes);
        List<Text> lines = new ArrayList<>();
        lines.add(header(Phrase.build(Phrase.LOOKUP_HEADER, "CoreProtect")));
        if (events.isEmpty()) {
            lines.add(Text.literal(COMMAND_PREFIX + Phrase.build(Phrase.NO_RESULTS)).formatted(Formatting.GRAY));
            return lines;
        }

        long now = System.currentTimeMillis();
        for (StoredEventRecord event : events) {
            appendFormattedEvent(lines, event, now, true);
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
        lines.add(header(Phrase.build(Phrase.LOOKUP_HEADER, "CoreProtect")));
        if (events.isEmpty()) {
            lines.add(Text.literal(COMMAND_PREFIX + Phrase.build(Phrase.NO_RESULTS)).formatted(Formatting.GRAY));
            return lines;
        }

        long now = System.currentTimeMillis();
        for (StoredEventRecord event : events) {
            appendFormattedEvent(lines, event, now, true);
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

    public List<Text> renderBlockHistory(String worldKey, BlockPos pos, List<StoredEventRecord> events, String title, String emptyMessage) {
        List<Text> lines = new ArrayList<>();
        lines.add(header(title, worldKey, pos, false));
        if (events == null || events.isEmpty()) {
            lines.add(Text.literal(emptyMessage).formatted(Formatting.GRAY));
            return lines;
        }

        long now = System.currentTimeMillis();
        for (StoredEventRecord event : events) {
            appendFormattedEvent(lines, event, now, false);
        }
        return lines;
    }

    public List<Text> renderNearbyHistory(List<StoredEventRecord> events) {
        List<Text> lines = new ArrayList<>();
        lines.add(header(Phrase.build(Phrase.LOOKUP_HEADER, "CoreProtect")));
        if (events == null || events.isEmpty()) {
            lines.add(Text.literal(COMMAND_PREFIX + Phrase.build(Phrase.NO_RESULTS)).formatted(Formatting.GRAY));
            return lines;
        }

        long now = System.currentTimeMillis();
        for (StoredEventRecord event : events) {
            appendFormattedEvent(lines, event, now, true);
        }
        return lines;
    }

    public List<Text> renderScopedHistory(List<StoredEventRecord> events) {
        List<Text> lines = new ArrayList<>();
        lines.add(header(Phrase.build(Phrase.LOOKUP_HEADER, "CoreProtect")));
        if (events == null || events.isEmpty()) {
            lines.add(Text.literal(COMMAND_PREFIX + Phrase.build(Phrase.NO_RESULTS)).formatted(Formatting.GRAY));
            return lines;
        }

        long now = System.currentTimeMillis();
        for (StoredEventRecord event : events) {
            appendFormattedEvent(lines, event, now, true);
        }
        return lines;
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

    private Text header(String title) {
        return Text.literal("----- " + title + " -----").formatted(Formatting.AQUA);
    }

    private Text header(String title, ServerWorld world, BlockPos pos, boolean displayWorld) {
        return header(title, world.getRegistryKey().getValue().toString(), pos, displayWorld);
    }

    private Text header(String title, String worldKey, BlockPos pos, boolean displayWorld) {
        MutableText header = Text.literal("----- " + title + " ----- ").formatted(Formatting.AQUA);
        header.append(coordinateText(worldKey, pos, displayWorld, false));
        return header;
    }

    private void appendFormattedEvent(List<Text> lines, StoredEventRecord event, long now, boolean includePosition) {
        if (event.type() == CoreProtectEventType.SIGN_CHANGE) {
            lines.addAll(formatSignEvent(event, now));
        }
        else {
            lines.add(formatEvent(event, now));
        }
        if (shouldIncludeCoordinateLine(event.type(), includePosition) && event.hasPosition()) {
            lines.add(formatCoordinateLine(event));
        }
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

    private boolean supportsEntityInventoryHistory(Entity entity) {
        return entity instanceof ItemFrameEntity || entity instanceof ArmorStandEntity;
    }

    private Text formatEvent(StoredEventRecord event, long now) {
        switch (event.type()) {
            case PLAYER_CHAT:
            case PLAYER_COMMAND:
                return formatMessageEvent(event, now, extractMessage(event));
            case SIGN_CHANGE:
                return formatMessageEvent(event, now, extractSignMessage(event));
            case PLAYER_JOIN:
            case PLAYER_QUIT:
                return formatSessionEvent(event, now);
            case USERNAME_CHANGE:
                return formatUsernameEvent(event, now);
            case CONTAINER_TRANSACTION:
                return formatContainerEvent(event, now);
            case ITEM_PICKUP:
            case ITEM_DROP:
            case ITEM_THROW:
            case ITEM_SHOOT:
            case ITEM_BUY:
            case ITEM_SELL:
            case ITEM_CREATE:
            case ITEM_DESTROY:
                return formatItemEvent(event, now);
            default:
                return formatGenericEvent(event, now);
        }
    }

    private Text formatGenericEvent(StoredEventRecord event, long now) {
        String age = timeAgo(now - event.timestamp());
        String actor = event.actorName() == null
            ? PhraseService.getInstance().phrase("lookup.system_actor", "system")
            : event.actorName();
        String target = describeTarget(event);

        MutableText line = ageText(event, age)
            .append(Text.literal(genericTag(event.type())).formatted(genericTagColor(event.type())))
            .append(styledLookupText(actor, Formatting.AQUA, event.rolledBack()))
            .append(styledLookupText(" " + verb(event.type()), Formatting.WHITE, event.rolledBack()));

        if (!target.isBlank()) {
            line.append(styledLookupText(" " + target, Formatting.DARK_AQUA, event.rolledBack()));
        }

        return line;
    }

    private String genericTag(CoreProtectEventType type) {
        return switch (type) {
            case BLOCK_PLACE, ENTITY_PLACE, SERVER_START -> "+ ";
            case BLOCK_BREAK, ENTITY_BREAK, BLOCK_USE, ENTITY_USE, ENTITY_KILL, SERVER_STOP -> "- ";
            default -> "- ";
        };
    }

    private Formatting genericTagColor(CoreProtectEventType type) {
        return switch (type) {
            case BLOCK_PLACE, ENTITY_PLACE, SERVER_START -> Formatting.GREEN;
            case BLOCK_BREAK, ENTITY_BREAK, ENTITY_KILL, SERVER_STOP -> Formatting.RED;
            case BLOCK_USE, ENTITY_USE -> Formatting.WHITE;
            default -> Formatting.WHITE;
        };
    }

    private Text formatMessageEvent(StoredEventRecord event, long now, String message) {
        String age = timeAgo(now - event.timestamp());
        String actor = event.actorName() == null
            ? PhraseService.getInstance().phrase("lookup.system_actor", "system")
            : event.actorName();

        MutableText line = ageText(event, age)
            .append(Text.literal("- ").formatted(Formatting.WHITE))
            .append(styledLookupText(actor + ": ", Formatting.AQUA, event.rolledBack()))
            .append(styledLookupText(message == null ? "" : message, Formatting.WHITE, event.rolledBack()));
        return line;
    }

    private List<Text> formatSignEvent(StoredEventRecord event, long now) {
        String age = timeAgo(now - event.timestamp());
        String actor = event.actorName() == null
            ? PhraseService.getInstance().phrase("lookup.system_actor", "system")
            : event.actorName();
        String message = extractSignMessage(event);

        MutableText headerLine = ageText(event, age)
            .append(Text.literal("- ").formatted(Formatting.WHITE))
            .append(styledLookupText(actor + ":", Formatting.AQUA, event.rolledBack()));

        MutableText messageLine = styledLookupText("    " + (message == null ? "" : message), Formatting.WHITE, event.rolledBack());
        return List.of(headerLine, messageLine);
    }

    private Text formatSessionEvent(StoredEventRecord event, long now) {
        String age = timeAgo(now - event.timestamp());
        String actor = event.actorName() == null
            ? PhraseService.getInstance().phrase("lookup.system_actor", "system")
            : event.actorName();
        boolean joined = event.type() == CoreProtectEventType.PLAYER_JOIN;
        String state = selectorWord(Phrase.LOOKUP_LOGIN, joined ? Selector.FIRST : Selector.SECOND, joined ? "in" : "out");

        MutableText line = ageText(event, age)
            .append(Text.literal(joined ? "+ " : "- ").formatted(joined ? Formatting.GREEN : Formatting.RED))
            .append(styledLookupText(actor, Formatting.AQUA, event.rolledBack()))
            .append(styledLookupText(" logged " + state + ".", Formatting.WHITE, event.rolledBack()));
        return line;
    }

    private Text formatUsernameEvent(StoredEventRecord event, long now) {
        String age = timeAgo(now - event.timestamp());
        String actor = event.actorName() == null
            ? PhraseService.getInstance().phrase("lookup.system_actor", "system")
            : event.actorName();
        String username = extractUsername(event);
        String rendered = Phrase.build(Phrase.LOOKUP_USERNAME, actor, username);
        int actorIndex = rendered.indexOf(actor);
        int usernameIndex = rendered.lastIndexOf(username);

        MutableText line = ageText(event, age).append(Text.literal("- ").formatted(Formatting.WHITE));
        if (actorIndex >= 0 && usernameIndex > actorIndex) {
            String between = rendered.substring(actorIndex + actor.length(), usernameIndex);
            String suffix = rendered.substring(usernameIndex + username.length());
            line.append(styledLookupText(actor, Formatting.AQUA, event.rolledBack()))
                .append(styledLookupText(between, Formatting.WHITE, event.rolledBack()))
                .append(styledLookupText(username, Formatting.AQUA, event.rolledBack()))
                .append(styledLookupText(suffix, Formatting.WHITE, event.rolledBack()));
        }
        else {
            line.append(styledLookupText(rendered, Formatting.WHITE, event.rolledBack()));
        }
        return line;
    }

    private Text formatItemEvent(StoredEventRecord event, long now) {
        String age = timeAgo(now - event.timestamp());
        String actor = event.actorName() == null
            ? PhraseService.getInstance().phrase("lookup.system_actor", "system")
            : event.actorName();
        LoggedItemChange itemChange = LoggedItemChange.parse(event.target(), event.payload());
        String itemLabel = itemLabel(itemChange);
        String amount = "x" + itemChange.count();
        Phrase phrase;
        String selector;
        Formatting tagColor;

        switch (event.type()) {
            case ITEM_PICKUP:
            case ITEM_BUY:
            case ITEM_CREATE:
                phrase = Phrase.LOOKUP_ITEM;
                selector = Selector.FIRST;
                tagColor = Formatting.GREEN;
                break;
            case ITEM_DROP:
            case ITEM_SELL:
            case ITEM_DESTROY:
                phrase = Phrase.LOOKUP_ITEM;
                selector = Selector.SECOND;
                tagColor = Formatting.RED;
                break;
            case ITEM_THROW:
                phrase = Phrase.LOOKUP_PROJECTILE;
                selector = Selector.FIRST;
                tagColor = Formatting.RED;
                break;
            case ITEM_SHOOT:
                phrase = Phrase.LOOKUP_PROJECTILE;
                selector = Selector.SECOND;
                tagColor = Formatting.RED;
                break;
            default:
                return formatGenericEvent(event, now);
        }

        String verb = selectorWord(phrase, selector, verb(event.type()));

        MutableText line = ageText(event, age)
            .append(Text.literal(tagColor == Formatting.GREEN ? "+ " : "- ").formatted(tagColor))
            .append(styledLookupText(actor, Formatting.AQUA, event.rolledBack()))
            .append(styledLookupText(" " + verb + " " + amount + " ", Formatting.WHITE, event.rolledBack()))
            .append(itemLabelText(itemChange, itemLabel, Formatting.DARK_AQUA, event.rolledBack()))
            .append(styledLookupText(".", Formatting.WHITE, event.rolledBack()));
        return line;
    }

    private Text formatContainerEvent(StoredEventRecord event, long now) {
        String age = timeAgo(now - event.timestamp());
        String actor = event.actorName() == null
            ? PhraseService.getInstance().phrase("lookup.system_actor", "system")
            : event.actorName();
        ContainerDelta delta = parseContainerDelta(event);
        String selector = delta.added() ? Selector.FIRST : Selector.SECOND;
        String verb = selectorWord(Phrase.LOOKUP_CONTAINER, selector, delta.added() ? "added" : "removed");

        MutableText line = ageText(event, age)
            .append(Text.literal(delta.added() ? "+ " : "- ").formatted(delta.added() ? Formatting.GREEN : Formatting.RED))
            .append(styledLookupText(actor, Formatting.AQUA, event.rolledBack()))
            .append(styledLookupText(" " + verb + " x" + delta.amount() + " ", Formatting.WHITE, event.rolledBack()))
            .append(itemLabelText(delta.change(), delta.target(), Formatting.DARK_AQUA, event.rolledBack()))
            .append(styledLookupText(".", Formatting.WHITE, event.rolledBack()));
        return line;
    }

    private boolean shouldIncludeCoordinateLine(CoreProtectEventType type, boolean includePosition) {
        if (!includePosition) {
            return false;
        }
        return type != CoreProtectEventType.PLAYER_CHAT
            && type != CoreProtectEventType.PLAYER_COMMAND
            && type != CoreProtectEventType.USERNAME_CHANGE;
    }

    private Text formatCoordinateLine(StoredEventRecord event) {
        MutableText line = Text.literal("    ^ ").formatted(Formatting.GRAY);
        line.append(coordinateText(event.worldKey(), event.blockPos(), true, false));
        return line;
    }

    private MutableText ageText(StoredEventRecord event, String age) {
        return Text.literal(age + " ").styled(style -> style
            .withFormatting(Formatting.DARK_GRAY)
            .withHoverEvent(new HoverEvent.ShowText(Text.literal(formatTimestamp(event.timestamp()))))
        );
    }

    private Text coordinateText(String worldKey, BlockPos pos, boolean displayWorld, boolean italic) {
        String worldDisplay = "";
        if (displayWorld && worldKey != null && !worldKey.isBlank()) {
            worldDisplay = "/" + displayWorldName(worldKey);
        }

        String coordinates = "(x" + pos.getX() + "/y" + pos.getY() + "/z" + pos.getZ() + worldDisplay + ")";
        if (hoverEventsEnabled(worldKey)) {
            String command = coordinateCommand(worldKey, pos);
            return Text.literal(coordinates).styled(style -> {
                style = style.withFormatting(Formatting.GRAY, Formatting.UNDERLINE);
                if (italic) {
                    style = style.withFormatting(Formatting.ITALIC);
                }
                return style
                    .withClickEvent(new ClickEvent.RunCommand(command))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal(command)));
            });
        }
        return italic
            ? Text.literal(coordinates).formatted(Formatting.GRAY, Formatting.ITALIC)
            : Text.literal(coordinates).formatted(Formatting.GRAY);
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

    private String extractMessage(StoredEventRecord event) {
        if (event.payload() != null && !event.payload().isBlank()) {
            return event.payload();
        }
        return event.target() == null ? "" : event.target();
    }

    private String extractSignMessage(StoredEventRecord event) {
        LoggedSignState signState = LoggedSignState.parse(event.payload());
        if (signState != null) {
            String joined = joinNonBlank(signState.lines(), " ");
            if (!joined.isBlank()) {
                return joined;
            }
        }
        return event.target() == null ? "" : event.target();
    }

    private String extractUsername(StoredEventRecord event) {
        if (event.payload() != null && !event.payload().isBlank()) {
            String[] names = event.payload().split("\\R", -1);
            if (names.length >= 2 && !names[1].isBlank()) {
                return names[1].trim();
            }
        }
        if (event.target() != null && event.target().contains("->")) {
            return event.target().substring(event.target().indexOf("->") + 2).trim();
        }
        return event.target() == null ? "" : event.target();
    }

    private String itemLabel(LoggedItemChange itemChange) {
        if (itemChange == null) {
            return "unknown_item";
        }
        String itemKey = itemChange.item().simplifiedItemKey();
        if (itemKey != null && !itemKey.isBlank()) {
            return itemKey;
        }
        if (itemChange.item().displayName() != null && !itemChange.item().displayName().isBlank()) {
            return itemChange.item().displayName();
        }
        return "unknown_item";
    }

    private ContainerDelta parseContainerDelta(StoredEventRecord event) {
        ContainerDelta structuredDelta = parseStructuredContainerDelta(event.payload());
        if (structuredDelta != null) {
            return structuredDelta;
        }
        return new ContainerDelta(simplifyTarget(event.target()), 1, true, LoggedItemChange.parse(event.target(), event.payload()));
    }

    private ContainerDelta parseStructuredContainerDelta(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }

        Boolean added = null;
        for (String line : payload.split("\\R", -1)) {
            if (line.startsWith("added=")) {
                added = Boolean.parseBoolean(line.substring("added=".length()).trim());
                break;
            }
        }
        if (added == null) {
            return null;
        }

        LoggedItemChange change = LoggedItemChange.parse(null, payload);
        if (change == null || change.item() == null || change.item().itemKey() == null || change.item().itemKey().isBlank()) {
            return null;
        }
        return new ContainerDelta(itemLabel(change), change.count(), added, change);
    }

    private String joinNonBlank(String[] values, String delimiter) {
        if (values == null || values.length == 0) {
            return "";
        }

        StringJoiner joiner = new StringJoiner(delimiter);
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            joiner.add(value);
        }
        return joiner.toString();
    }

    private String resolveEmptyMessage(ServerWorld world, BlockPos pos, List<CoreProtectEventType> eventTypes, String fallback) {
        if (eventTypes != null && !eventTypes.isEmpty()) {
            return fallback;
        }

        String blockName = simplifyTarget(world.getBlockState(pos).getBlock().getTranslationKey().replace("block.minecraft.", "minecraft:"));
        if (!blockName.isBlank() && !"air".equals(blockName) && !"cave_air".equals(blockName)) {
            return COMMAND_PREFIX + Phrase.build(Phrase.NO_DATA, blockName);
        }
        return fallback;
    }

    private Text itemLabelText(LoggedItemChange change, String label, Formatting color) {
        return itemLabelText(change, label, color, false);
    }

    private Text itemLabelText(LoggedItemChange change, String label, Formatting color, boolean strikethrough) {
        if (change == null || (!change.item().hasMetadata() && (change.item().displayName() == null || change.item().displayName().isBlank()))) {
            return styledLookupText(label, color, strikethrough);
        }

        return styledLookupText(label, color, strikethrough).styled(style -> style
            .withHoverEvent(new HoverEvent.ShowText(Text.literal(itemTooltip(change))))
        );
    }

    private MutableText styledLookupText(String value, Formatting color, boolean strikethrough) {
        return Text.literal(value).styled(style -> style
            .withColor(color)
            .withStrikethrough(strikethrough)
        );
    }

    private String itemTooltip(LoggedItemChange change) {
        StringBuilder tooltip = new StringBuilder();
        if (change.item().displayName() != null && !change.item().displayName().isBlank()) {
            tooltip.append(change.item().displayName());
        }
        if (change.item().componentChanges() != null && !change.item().componentChanges().isBlank()) {
            if (!tooltip.isEmpty()) {
                tooltip.append('\n');
            }
            tooltip.append(abbreviate(change.item().componentChanges(), 320));
        }
        if (change.contextLabel() != null && !change.contextLabel().isBlank()) {
            if (!tooltip.isEmpty()) {
                tooltip.append('\n');
            }
            tooltip.append("via ").append(change.contextLabel());
        }
        return tooltip.isEmpty() ? change.item().itemKey() : tooltip.toString();
    }

    private String formatTimestamp(long timestamp) {
        return TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
    }

    private String coordinateCommand(String worldKey, BlockPos pos) {
        String world = displayWorldName(worldKey);
        return "/co teleport "
            + world + " "
            + formatHoverCoordinate(pos.getX() + 0.5D) + " "
            + pos.getY() + " "
            + formatHoverCoordinate(pos.getZ() + 0.5D);
    }

    private String formatHoverCoordinate(double value) {
        return new DecimalFormat("#.##", new DecimalFormatSymbols(java.util.Locale.ROOT)).format(value);
    }

    private String displayWorldName(String worldKey) {
        if (worldKey == null || worldKey.isBlank()) {
            return "world";
        }
        String normalized = worldKey.toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "minecraft:overworld" -> "world";
            case "minecraft:the_nether" -> "world_nether";
            case "minecraft:the_end" -> "world_the_end";
            default -> worldKey.contains(":") ? worldKey.substring(worldKey.indexOf(':') + 1) : worldKey;
        };
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private record ContainerDelta(String target, int amount, boolean added, LoggedItemChange change) {
    }

    private boolean hoverEventsEnabled(String worldKey) {
        return CoreProtectFabricMod.getRuntime() != null
            && CoreProtectFabricMod.getRuntime().config(worldKey) != null
            && CoreProtectFabricMod.getRuntime().config(worldKey).hoverEvents();
    }

    private String verb(CoreProtectEventType type) {
        switch (type) {
            case BLOCK_PLACE:
            case ENTITY_PLACE:
                return selectorWord(Phrase.LOOKUP_BLOCK, Selector.FIRST, "placed");
            case ITEM_PICKUP:
                return selectorWord(Phrase.LOOKUP_ITEM, Selector.FIRST, "picked up");
            case ITEM_THROW:
                return selectorWord(Phrase.LOOKUP_PROJECTILE, Selector.FIRST, "threw");
            case ITEM_BUY:
                return selectorWord(Phrase.LOOKUP_ITEM, Selector.FIRST, "picked up");
            case ITEM_CREATE:
                return selectorWord(Phrase.LOOKUP_ITEM, Selector.FIRST, "picked up");
            case BLOCK_BREAK:
            case ENTITY_BREAK:
                return selectorWord(Phrase.LOOKUP_BLOCK, Selector.SECOND, "broke");
            case ITEM_DROP:
                return selectorWord(Phrase.LOOKUP_ITEM, Selector.SECOND, "dropped");
            case ITEM_SHOOT:
                return selectorWord(Phrase.LOOKUP_PROJECTILE, Selector.SECOND, "shot");
            case ITEM_SELL:
                return selectorWord(Phrase.LOOKUP_ITEM, Selector.SECOND, "dropped");
            case ITEM_DESTROY:
                return selectorWord(Phrase.LOOKUP_ITEM, Selector.SECOND, "dropped");
            case BLOCK_USE:
            case ENTITY_USE:
                return selectorWord(Phrase.LOOKUP_INTERACTION, Selector.FIRST, "clicked");
            case ENTITY_KILL:
                return selectorWord(Phrase.LOOKUP_INTERACTION, Selector.SECOND, "killed");
            case SIGN_CHANGE:
                return "edited";
            case CONTAINER_TRANSACTION:
                return selectorWord(Phrase.LOOKUP_CONTAINER, Selector.FIRST, "added");
            case PLAYER_CHAT:
                return PhraseService.getInstance().phrase("lookup.chat.1", "said");
            case PLAYER_COMMAND:
                return PhraseService.getInstance().phrase("lookup.command.1", "ran");
            case PLAYER_JOIN:
                return selectorWord(Phrase.LOOKUP_LOGIN, Selector.FIRST, "in");
            case PLAYER_QUIT:
                return selectorWord(Phrase.LOOKUP_LOGIN, Selector.SECOND, "out");
            case USERNAME_CHANGE:
                return "logged in as";
            case SERVER_START:
                return PhraseService.getInstance().phrase("lookup.server.1", "started");
            case SERVER_STOP:
                return PhraseService.getInstance().phrase("lookup.server.2", "stopped");
            default:
                throw new IllegalStateException("Unhandled event type: " + type);
        }
    }

    private String selectorWord(Phrase phrase, String selector, String fallback) {
        String selected = Phrase.getPhraseSelector(phrase, selector);
        return selected == null || selected.isBlank() ? fallback : selected;
    }

    private String timeAgo(long ageMs) {
        long seconds = Math.max(0L, ageMs / 1000L);
        double timeSince = seconds;

        timeSince = timeSince / 60.0D;
        if (timeSince < 60.0D) {
            return Phrase.build(Phrase.LOOKUP_TIME, ELAPSED_DECIMAL.format(timeSince) + Phrase.build(Phrase.TIME_UNITS, Selector.FIRST));
        }

        timeSince = timeSince / 60.0D;
        if (timeSince < 24.0D) {
            return Phrase.build(Phrase.LOOKUP_TIME, ELAPSED_DECIMAL.format(timeSince) + Phrase.build(Phrase.TIME_UNITS, Selector.SECOND));
        }

        timeSince = timeSince / 24.0D;
        return Phrase.build(Phrase.LOOKUP_TIME, ELAPSED_DECIMAL.format(timeSince) + Phrase.build(Phrase.TIME_UNITS, Selector.THIRD));
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
