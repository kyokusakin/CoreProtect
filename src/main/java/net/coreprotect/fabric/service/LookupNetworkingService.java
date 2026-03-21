package net.coreprotect.fabric.service;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.listener.channel.PluginChannelListener;
import net.coreprotect.fabric.util.InteractionAggregatePayload;
import net.coreprotect.fabric.util.LoggedItemChange;
import net.coreprotect.language.Phrase;
import net.coreprotect.language.Selector;
import net.minecraft.server.command.ServerCommandSource;

import java.io.IOException;
import java.util.List;

public final class LookupNetworkingService {
    private LookupNetworkingService() {
    }

    public static void send(ServerCommandSource source, List<StoredEventRecord> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        PluginChannelListener listener = PluginChannelListener.getInstance();
        long now = System.currentTimeMillis();
        for (StoredEventRecord event : events) {
            String actor = event.actorName() == null || event.actorName().isBlank() ? "system" : event.actorName();
            String worldName = event.worldKey() == null || event.worldKey().isBlank() ? "minecraft:overworld" : event.worldKey();
            int x = event.x() == null ? 0 : event.x();
            int y = event.y() == null ? 0 : event.y();
            int z = event.z() == null ? 0 : event.z();
            long timeAgo = Math.max(0L, (now - event.timestamp()) / 1000L);

            try {
                switch (event.type()) {
                    case PLAYER_CHAT:
                    case PLAYER_COMMAND:
                        listener.sendMessageData(source, timeAgo, actor, extractMessagePayload(event), false, x, y, z, worldName);
                        break;
                    case SIGN_CHANGE:
                        listener.sendMessageData(source, timeAgo, actor, extractSignMessagePayload(event), true, x, y, z, worldName);
                        break;
                    case PLAYER_JOIN:
                        listener.sendInfoData(source, timeAgo, selectorWord(Phrase.LOOKUP_LOGIN, Selector.FIRST, "in"), actor, -1, x, y, z, worldName);
                        break;
                    case PLAYER_QUIT:
                        listener.sendInfoData(source, timeAgo, selectorWord(Phrase.LOOKUP_LOGIN, Selector.SECOND, "out"), actor, -1, x, y, z, worldName);
                        break;
                    case USERNAME_CHANGE:
                        listener.sendUsernameData(source, timeAgo, actor, extractUsernamePayload(event));
                        break;
                    default:
                        LookupNetworkPayload payload = toLookupNetworkPayload(event);
                        if (payload == null) {
                            break;
                        }
                        listener.sendData(
                            source,
                            timeAgo,
                            payload.phraseSelector(),
                            actor,
                            payload.target(),
                            payload.amount(),
                            x,
                            y,
                            z,
                            worldName,
                            event.rolledBack() ? "rolled" : "",
                            payload.container(),
                            payload.added()
                        );
                        break;
                }
            }
            catch (IOException exception) {
                CoreProtectFabricMod.LOGGER.warn("CoreProtect Fabric failed to send lookup networking payload", exception);
                return;
            }
        }
    }

    private static LookupNetworkPayload toLookupNetworkPayload(StoredEventRecord event) {
        LoggedItemChange itemChange = LoggedItemChange.parse(event.target(), event.payload());
        String itemTarget = itemChange.item().simplifiedItemKey();
        if (itemTarget == null || itemTarget.isBlank()) {
            itemTarget = simplifyIdentifier(event.target());
        }

        return switch (event.type()) {
            case BLOCK_PLACE, ENTITY_PLACE -> new LookupNetworkPayload(selectorWord(Phrase.LOOKUP_BLOCK, Selector.FIRST, "placed"), simplifyIdentifier(event.target()), -1, false, true);
            case BLOCK_BREAK, ENTITY_BREAK -> new LookupNetworkPayload(selectorWord(Phrase.LOOKUP_BLOCK, Selector.SECOND, "broke"), simplifyIdentifier(event.target()), -1, false, false);
            case BLOCK_USE, ENTITY_USE -> {
                int clickCount = InteractionAggregatePayload.displayCount(event.type(), event.payload());
                yield new LookupNetworkPayload(
                    selectorWord(Phrase.LOOKUP_INTERACTION, Selector.FIRST, "clicked"),
                    simplifyIdentifier(event.target()),
                    clickCount > 1 ? clickCount : -1,
                    false,
                    false
                );
            }
            case ENTITY_KILL -> new LookupNetworkPayload(selectorWord(Phrase.LOOKUP_INTERACTION, Selector.SECOND, "killed"), simplifyIdentifier(event.target()), -1, false, false);
            case CONTAINER_TRANSACTION -> parseContainerPayload(event);
            case ITEM_PICKUP, ITEM_BUY, ITEM_CREATE -> new LookupNetworkPayload(selectorWord(Phrase.LOOKUP_ITEM, Selector.FIRST, "picked up"), itemTarget, itemChange.count(), false, true);
            case ITEM_DROP, ITEM_SELL, ITEM_DESTROY -> new LookupNetworkPayload(selectorWord(Phrase.LOOKUP_ITEM, Selector.SECOND, "dropped"), itemTarget, itemChange.count(), false, false);
            case ITEM_THROW -> new LookupNetworkPayload(selectorWord(Phrase.LOOKUP_PROJECTILE, Selector.FIRST, "threw"), itemTarget, itemChange.count(), false, false);
            case ITEM_SHOOT -> new LookupNetworkPayload(selectorWord(Phrase.LOOKUP_PROJECTILE, Selector.SECOND, "shot"), itemTarget, itemChange.count(), false, false);
            default -> null;
        };
    }

    private static LookupNetworkPayload parseContainerPayload(StoredEventRecord event) {
        LookupNetworkPayload structuredPayload = parseStructuredContainerPayload(event.payload());
        if (structuredPayload != null) {
            return structuredPayload;
        }

        return new LookupNetworkPayload(
                selectorWord(Phrase.LOOKUP_CONTAINER, Selector.FIRST, "added"),
                simplifyIdentifier(event.target()),
                1,
            true,
                true
        );
    }

    private static LookupNetworkPayload parseStructuredContainerPayload(String payload) {
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

        return new LookupNetworkPayload(
            selectorWord(Phrase.LOOKUP_CONTAINER, added ? Selector.FIRST : Selector.SECOND, added ? "added" : "removed"),
            simplifyIdentifier(change.lookupTarget()),
            Math.max(1, change.count()),
            true,
            added
        );
    }

    private static String extractMessagePayload(StoredEventRecord event) {
        if (event.payload() != null && !event.payload().isBlank()) {
            return event.payload();
        }
        if (event.target() != null && !event.target().isBlank()) {
            return event.target();
        }
        return "";
    }

    private static String extractSignMessagePayload(StoredEventRecord event) {
        if (event.payload() == null || event.payload().isBlank()) {
            return event.target() == null ? "" : event.target();
        }

        String[] lines = event.payload().split("\\R", -1);
        int start = lines.length > 0 && ("front".equalsIgnoreCase(lines[0]) || "back".equalsIgnoreCase(lines[0])) ? 1 : 0;
        StringBuilder message = new StringBuilder();
        for (int index = start; index < lines.length; index++) {
            if (lines[index].isBlank()) {
                continue;
            }
            if (!message.isEmpty()) {
                message.append('\n');
            }
            message.append(lines[index]);
        }
        if (!message.isEmpty()) {
            return message.toString();
        }
        return event.target() == null ? "" : event.target();
    }

    private static String extractUsernamePayload(StoredEventRecord event) {
        if (event.payload() != null && !event.payload().isBlank()) {
            String[] names = event.payload().split("\\R", -1);
            if (names.length >= 2 && !names[1].isBlank()) {
                return names[1].trim();
            }
        }

        String target = event.target();
        if (target == null || target.isBlank()) {
            return "";
        }
        int arrowIndex = target.indexOf("->");
        if (arrowIndex >= 0) {
            return target.substring(arrowIndex + 2).trim();
        }
        return target;
    }

    private static String simplifyIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value.trim();
        return cleaned.startsWith("minecraft:") ? cleaned.substring("minecraft:".length()) : cleaned;
    }

    private static String selectorWord(Phrase phrase, String selector, String fallback) {
        String selected = Phrase.getPhraseSelector(phrase, selector);
        return selected == null || selected.isBlank() ? fallback : selected;
    }

    private record LookupNetworkPayload(String phraseSelector, String target, int amount, boolean container, boolean added) {
    }
}
