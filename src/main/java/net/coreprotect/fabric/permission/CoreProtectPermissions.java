package net.coreprotect.fabric.permission;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CoreProtectPermissions {
    public static final String ALL = "coreprotect.*";
    public static final String STATUS = "coreprotect.status";
    public static final String INSPECT = "coreprotect.inspect";
    public static final String HELP = "coreprotect.help";
    public static final String LOOKUP = "coreprotect.lookup";
    public static final String LOOKUP_BLOCK = "coreprotect.lookup.block";
    public static final String LOOKUP_ENTITY = "coreprotect.lookup.entity";
    public static final String LOOKUP_NEAR = "coreprotect.lookup.near";
    public static final String LOOKUP_CHAT = "coreprotect.lookup.chat";
    public static final String LOOKUP_COMMAND = "coreprotect.lookup.command";
    public static final String LOOKUP_SESSION = "coreprotect.lookup.session";
    public static final String LOOKUP_CLICK = "coreprotect.lookup.click";
    public static final String LOOKUP_KILL = "coreprotect.lookup.kill";
    public static final String LOOKUP_SIGN = "coreprotect.lookup.sign";
    public static final String LOOKUP_CONTAINER = "coreprotect.lookup.container";
    public static final String LOOKUP_INVENTORY = "coreprotect.lookup.inventory";
    public static final String LOOKUP_ITEM = "coreprotect.lookup.item";
    public static final String LOOKUP_USERNAME = "coreprotect.lookup.username";
    public static final String ROLLBACK = "coreprotect.rollback";
    public static final String RESTORE = "coreprotect.restore";
    public static final String TELEPORT = "coreprotect.teleport";
    public static final String PURGE = "coreprotect.purge";
    public static final String RELOAD = "coreprotect.reload";
    public static final String CONSUMER = "coreprotect.consumer";
    public static final String NETWORKING = "coreprotect.networking";
    public static final String MIGRATE = "coreprotect.migrate-db";

    private static final PermissionLevel LOOKUP_FALLBACK_LEVEL = PermissionLevel.fromLevel(2);
    private static final PermissionLevel INSPECT_FALLBACK_LEVEL = PermissionLevel.fromLevel(2);
    private static final PermissionLevel HELP_FALLBACK_LEVEL = PermissionLevel.fromLevel(2);
    private static final PermissionLevel STATUS_FALLBACK_LEVEL = PermissionLevel.fromLevel(2);
    private static final PermissionLevel ROLLBACK_FALLBACK_LEVEL = PermissionLevel.fromLevel(4);
    private static final PermissionLevel ADMIN_FALLBACK_LEVEL = PermissionLevel.fromLevel(4);
    private static final Text NO_PERMISSION = Text.literal("CoreProtect - You do not have permission to do that.");

    private CoreProtectPermissions() {
    }

    public static boolean canUseStatus(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, STATUS_FALLBACK_LEVEL, STATUS, ALL);
    }

    public static boolean canUseInspect(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, INSPECT_FALLBACK_LEVEL, INSPECT, ALL);
    }

    public static boolean canUseInspect(ServerPlayerEntity player, boolean notify) {
        if (checkAny(player.getCommandSource(), false, INSPECT_FALLBACK_LEVEL, INSPECT, ALL)) {
            return true;
        }

        if (notify) {
            player.sendMessage(NO_PERMISSION, false);
        }
        return false;
    }

    public static boolean canUseHelp(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, HELP_FALLBACK_LEVEL, HELP, ALL);
    }

    public static boolean canLookupBlock(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_BLOCK, LOOKUP, ALL);
    }

    public static boolean canLookupEntity(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_ENTITY, LOOKUP_BLOCK, LOOKUP_CLICK, LOOKUP, ALL);
    }

    public static boolean canLookupSign(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_SIGN, LOOKUP, ALL);
    }

    public static boolean canLookupClick(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_CLICK, LOOKUP, ALL);
    }

    public static boolean canLookupChat(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_CHAT, LOOKUP, ALL);
    }

    public static boolean canLookupCommand(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_COMMAND, LOOKUP, ALL);
    }

    public static boolean canLookupSession(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_SESSION, LOOKUP, ALL);
    }

    public static boolean canLookupKill(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_KILL, LOOKUP, ALL);
    }

    public static boolean canLookupContainer(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_CONTAINER, LOOKUP_INVENTORY, LOOKUP, ALL);
    }

    public static boolean canLookupInventory(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_INVENTORY, LOOKUP, ALL);
    }

    public static boolean canLookupItem(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_ITEM, LOOKUP, ALL);
    }

    public static boolean canUseLookupPagination(ServerCommandSource source, boolean notify) {
        return checkAny(
            source,
            notify,
            LOOKUP_FALLBACK_LEVEL,
            LOOKUP_BLOCK,
            LOOKUP_ENTITY,
            LOOKUP_NEAR,
            LOOKUP_CHAT,
            LOOKUP_COMMAND,
            LOOKUP_SESSION,
            LOOKUP_CLICK,
            LOOKUP_KILL,
            LOOKUP_SIGN,
            LOOKUP_CONTAINER,
            LOOKUP_INVENTORY,
            LOOKUP_ITEM,
            LOOKUP_USERNAME,
            LOOKUP,
            ALL
        );
    }

    public static boolean canUseTeleport(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, TELEPORT, ALL);
    }

    public static boolean canLookupNearby(ServerCommandSource source, List<CoreProtectEventType> eventTypes, boolean notify) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_NEAR, LOOKUP, ALL);
        }

        boolean containsItem = containsItemEvents(eventTypes);
        boolean containsContainer = eventTypes.contains(CoreProtectEventType.CONTAINER_TRANSACTION);
        if (containsItem && containsContainer) {
            if (!canLookupInventory(source, notify)) {
                return false;
            }
        }
        else if (containsItem) {
            if (!canLookupItem(source, notify)) {
                return false;
            }
        }
        else if (containsContainer) {
            if (!canLookupContainer(source, notify)) {
                return false;
            }
        }

        Set<CoreProtectEventType> requiredTypes = new LinkedHashSet<>();
        for (CoreProtectEventType eventType : eventTypes) {
            if (eventType == CoreProtectEventType.CONTAINER_TRANSACTION || isItemEvent(eventType)) {
                continue;
            }
            if (lookupPermissionsFor(eventType) != null) {
                requiredTypes.add(eventType);
            }
        }

        if (requiredTypes.isEmpty()) {
            return checkAny(source, notify, LOOKUP_FALLBACK_LEVEL, LOOKUP_NEAR, LOOKUP, ALL);
        }

        for (CoreProtectEventType eventType : requiredTypes) {
            String[] permissions = lookupPermissionsFor(eventType);
            if (permissions == null) {
                continue;
            }
            if (!checkAny(source, false, LOOKUP_FALLBACK_LEVEL, permissions)) {
                if (notify) {
                    source.sendFeedback(() -> NO_PERMISSION, false);
                }
                return false;
            }
        }
        return true;
    }

    public static boolean canRunRollback(ServerCommandSource source, boolean restore, boolean notify) {
        return restore
            ? checkAny(source, notify, ROLLBACK_FALLBACK_LEVEL, RESTORE, ALL)
            : checkAny(source, notify, ROLLBACK_FALLBACK_LEVEL, ROLLBACK, ALL);
    }

    public static boolean canUseUndo(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, ROLLBACK_FALLBACK_LEVEL, RESTORE, ALL);
    }

    public static boolean canUseApplyCancel(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, ROLLBACK_FALLBACK_LEVEL, ROLLBACK, RESTORE, ALL);
    }

    public static boolean canUseReload(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, ADMIN_FALLBACK_LEVEL, RELOAD, ALL);
    }

    public static boolean canUsePurge(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, ADMIN_FALLBACK_LEVEL, PURGE, ALL);
    }

    public static boolean canUseConsumer(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, ADMIN_FALLBACK_LEVEL, CONSUMER, ALL);
    }

    public static boolean canUseMigrate(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, ADMIN_FALLBACK_LEVEL, MIGRATE, ALL);
    }

    public static boolean canUseNetworking(ServerCommandSource source, boolean notify) {
        return checkAny(source, notify, ADMIN_FALLBACK_LEVEL, NETWORKING, ALL);
    }

    private static String[] lookupPermissionsFor(CoreProtectEventType eventType) {
        switch (eventType) {
            case BLOCK_BREAK:
            case BLOCK_PLACE:
            case ENTITY_BREAK:
            case ENTITY_PLACE:
                return new String[] { LOOKUP_BLOCK, LOOKUP, ALL };
            case BLOCK_USE:
            case ENTITY_USE:
                return new String[] { LOOKUP_CLICK, LOOKUP, ALL };
            case ENTITY_KILL:
                return new String[] { LOOKUP_KILL, LOOKUP, ALL };
            case PLAYER_CHAT:
                return new String[] { LOOKUP_CHAT, LOOKUP, ALL };
            case PLAYER_COMMAND:
                return new String[] { LOOKUP_COMMAND, LOOKUP, ALL };
            case PLAYER_JOIN:
            case PLAYER_QUIT:
                return new String[] { LOOKUP_SESSION, LOOKUP, ALL };
            case USERNAME_CHANGE:
                return new String[] { LOOKUP_USERNAME, LOOKUP, ALL };
            case SIGN_CHANGE:
                return new String[] { LOOKUP_SIGN, LOOKUP, ALL };
            case ITEM_PICKUP:
            case ITEM_DROP:
            case ITEM_THROW:
            case ITEM_SHOOT:
            case ITEM_BUY:
            case ITEM_SELL:
            case ITEM_CREATE:
            case ITEM_DESTROY:
                return new String[] { LOOKUP_ITEM, LOOKUP, ALL };
            case CONTAINER_TRANSACTION:
                return new String[] { LOOKUP_CONTAINER, LOOKUP_INVENTORY, LOOKUP, ALL };
            default:
                return null;
        }
    }

    private static boolean containsItemEvents(List<CoreProtectEventType> eventTypes) {
        for (CoreProtectEventType eventType : eventTypes) {
            if (isItemEvent(eventType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isItemEvent(CoreProtectEventType eventType) {
        return eventType == CoreProtectEventType.ITEM_PICKUP
            || eventType == CoreProtectEventType.ITEM_DROP
            || eventType == CoreProtectEventType.ITEM_THROW
            || eventType == CoreProtectEventType.ITEM_SHOOT
            || eventType == CoreProtectEventType.ITEM_BUY
            || eventType == CoreProtectEventType.ITEM_SELL
            || eventType == CoreProtectEventType.ITEM_CREATE
            || eventType == CoreProtectEventType.ITEM_DESTROY;
    }

    private static boolean checkAny(ServerCommandSource source, boolean notify, PermissionLevel fallbackLevel, String... permissions) {
        if (hasAny(source, fallbackLevel, permissions)) {
            return true;
        }

        if (notify) {
            source.sendFeedback(() -> NO_PERMISSION, false);
        }
        return false;
    }

    private static boolean hasAny(ServerCommandSource source, PermissionLevel fallbackLevel, String... permissions) {
        if (permissions.length == 0) {
            return false;
        }

        if (Permissions.check(source, permissions[0], fallbackLevel)) {
            return true;
        }

        for (int index = 1; index < permissions.length; index++) {
            if (Permissions.check(source, permissions[index])) {
                return true;
            }
        }
        return false;
    }
}
