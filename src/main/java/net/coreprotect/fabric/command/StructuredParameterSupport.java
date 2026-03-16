package net.coreprotect.fabric.command;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class StructuredParameterSupport {
    static final int MAX_PARAMS = 16;

    private static final List<ActionSuggestion> LOOKUP_ACTIONS = List.of(
        new ActionSuggestion("block", "Lookup block place/break history"),
        new ActionSuggestion("+block", "Lookup block/entity place history"),
        new ActionSuggestion("-block", "Lookup block/entity break history"),
        new ActionSuggestion("click", "Lookup interaction history"),
        new ActionSuggestion("container", "Lookup container transactions"),
        new ActionSuggestion("inventory", "Lookup inventory transactions"),
        new ActionSuggestion("item", "Lookup item transactions"),
        new ActionSuggestion("kill", "Lookup kill history"),
        new ActionSuggestion("chat", "Lookup chat history"),
        new ActionSuggestion("command", "Lookup command history"),
        new ActionSuggestion("sign", "Lookup sign history"),
        new ActionSuggestion("session", "Lookup login/logout history"),
        new ActionSuggestion("username", "Lookup username history")
    );

    private static final List<String> LOOKUP_USER_TAGS = List.of("#container", "#hopper", "#tnt", "#creeper", "#enderman");
    private static final List<String> TARGET_TAGS = List.of("#button", "#container", "#door", "#natural", "#pressure_plate", "#shulker_box");
    private static final List<String> LOOKUP_FLAGS = List.of("#count");
    private static final List<String> ROLLBACK_FLAGS = List.of("#preview", "#preview_cancel", "#count", "#silent", "#verbose");
    private static final List<String> PURGE_FLAGS = List.of("#optimize");
    private static final List<String> TIME_EXAMPLES = List.of("30m", "1h", "1d", "7d", "30d", "1h-2h");
    private static final List<String> LIMIT_EXAMPLES = List.of("10", "20", "50", "100");
    private static final List<String> RADIUS_EXAMPLES = List.of("10", "5x5", "5x20x5", "#global", "#worldedit");

    private StructuredParameterSupport() {
    }

    enum CommandKind {
        LOOKUP,
        ROLLBACK,
        PURGE
    }

    static String paramName(int index) {
        return "param" + index;
    }

    static String joinArguments(CommandContext<ServerCommandSource> context, int depth) {
        List<String> tokens = new ArrayList<>();
        for (int index = 1; index <= depth; index++) {
            String name = paramName(index);
            try {
                tokens.add(StringArgumentType.getString(context, name));
            }
            catch (IllegalArgumentException ignored) {
                break;
            }
        }
        return String.join(" ", tokens);
    }

    static CompletableFuture<Suggestions> suggest(CommandKind kind, CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        String token = remaining;
        int lastSpace = token.lastIndexOf(' ');
        if (lastSpace >= 0) {
            token = token.substring(lastSpace + 1);
        }
        Set<String> existing = existingTokens(context);

        if (token.isBlank() || !token.contains(":") && !token.startsWith("#")) {
            suggestBaseTokens(kind, builder, remaining, existing);
            return builder.buildFuture();
        }

        if (token.startsWith("#")) {
            suggestFlags(kind, builder, remaining, existing);
            return builder.buildFuture();
        }

        int separator = token.indexOf(':');
        if (separator < 0) {
            suggestBaseTokens(kind, builder, remaining, existing);
            return builder.buildFuture();
        }

        String prefix = token.substring(0, separator + 1);
        String value = token.substring(separator + 1);
        switch (prefix) {
            case "u:" -> suggestUsers(builder, context.getSource(), value, remaining);
            case "t:" -> suggestExamples(builder, "t:", value, TIME_EXAMPLES, remaining);
            case "r:" -> suggestRadius(builder, context.getSource(), value, remaining);
            case "c:" -> suggestCoordinates(builder, context.getSource(), value, remaining);
            case "a:" -> suggestActions(builder, value, remaining);
            case "i:", "e:" -> suggestTargets(builder, prefix, value, remaining);
            case "l:" -> suggestExamples(builder, "l:", value, LIMIT_EXAMPLES, remaining);
            default -> suggestBaseTokens(kind, builder, remaining, existing);
        }
        return builder.buildFuture();
    }

    private static Set<String> existingTokens(CommandContext<ServerCommandSource> context) {
        Set<String> tokens = new LinkedHashSet<>();
        for (int index = 1; index <= MAX_PARAMS; index++) {
            try {
                tokens.add(StringArgumentType.getString(context, paramName(index)).toLowerCase(Locale.ROOT));
            }
            catch (IllegalArgumentException ignored) {
                break;
            }
        }
        return tokens;
    }

    private static void suggestBaseTokens(CommandKind kind, SuggestionsBuilder builder, String remaining, Set<String> existing) {
        if (kind == CommandKind.PURGE) {
            suggestToken(builder, "t:", "Specify the amount of time to purge.", remaining);
            suggestToken(builder, "r:", "Specify the target world to purge.", remaining);
            suggestToken(builder, "i:", "Include specific targets in the purge.", remaining);
            suggestFlags(kind, builder, remaining, existing);
            return;
        }

        suggestToken(builder, "u:", "Specify the user(s) to lookup/rollback/restore.", remaining);
        suggestToken(builder, "t:", "Specify the amount of time to lookup/rollback/restore.", remaining);
        suggestToken(builder, "r:", "Specify a radius/world selection.", remaining);
        suggestToken(builder, "a:", "Restrict the command to a certain action.", remaining);
        suggestToken(builder, "i:", "Include specific blocks/entities in the command.", remaining);
        suggestToken(builder, "e:", "Exclude specific blocks/entities/users.", remaining);
        suggestToken(builder, "c:", "Specify explicit lookup center coordinates.", remaining);
        suggestToken(builder, "l:", "Specify the page/row limit.", remaining);

        suggestFlags(kind, builder, remaining, existing);
    }

    private static void suggestFlags(CommandKind kind, SuggestionsBuilder builder, String remaining, Set<String> existing) {
        List<String> flags = switch (kind) {
            case LOOKUP -> LOOKUP_FLAGS;
            case ROLLBACK -> ROLLBACK_FLAGS;
            case PURGE -> PURGE_FLAGS;
        };

        for (String flag : flags) {
            if (existing.contains(flag.toLowerCase(Locale.ROOT))) {
                continue;
            }
            suggestToken(builder, flag, "Hashtag flag", remaining);
        }
    }

    private static void suggestUsers(SuggestionsBuilder builder, ServerCommandSource source, String value, String remaining) {
        String lowered = value.toLowerCase(Locale.ROOT);
        for (String tag : LOOKUP_USER_TAGS) {
            suggestToken(builder, "u:" + tag, "Special user/tag filter", remaining);
        }
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            suggestToken(builder, "u:" + player.getName().getString(), "Online player", remaining);
        }
    }

    private static void suggestRadius(SuggestionsBuilder builder, ServerCommandSource source, String value, String remaining) {
        suggestExamples(builder, "r:", value, RADIUS_EXAMPLES, remaining);
        for (ServerWorld world : source.getServer().getWorlds()) {
            String worldName = world.getRegistryKey().getValue().toString();
            String alias = "#" + worldName.substring(worldName.indexOf(':') + 1);
            suggestToken(builder, "r:" + alias, "World filter", remaining);
        }
    }

    private static void suggestCoordinates(SuggestionsBuilder builder, ServerCommandSource source, String value, String remaining) {
        int x = (int) Math.floor(source.getPosition().x);
        int y = (int) Math.floor(source.getPosition().y);
        int z = (int) Math.floor(source.getPosition().z);
        suggestToken(builder, "c:" + x + "," + z, "Current X/Z position", remaining);
        suggestToken(builder, "c:" + x + "," + y + "," + z, "Current X/Y/Z position", remaining);
    }

    private static void suggestActions(SuggestionsBuilder builder, String value, String remaining) {
        for (ActionSuggestion action : LOOKUP_ACTIONS) {
            suggestToken(builder, "a:" + action.value(), action.description(), remaining);
        }
    }

    private static void suggestTargets(SuggestionsBuilder builder, String prefix, String value, String remaining) {
        String lowered = value.toLowerCase(Locale.ROOT);
        for (String tag : TARGET_TAGS) {
            suggestToken(builder, prefix + tag, "Special target filter", remaining);
        }

        suggestRegistryIds(builder, prefix, lowered, Registries.BLOCK.getIds().stream().map(id -> id.getPath()).toList());
        suggestRegistryIds(builder, prefix, lowered, Registries.ITEM.getIds().stream().map(id -> id.getPath()).toList());
        suggestRegistryIds(builder, prefix, lowered, Registries.ENTITY_TYPE.getIds().stream().map(id -> id.getPath()).toList());
    }

    private static void suggestRegistryIds(SuggestionsBuilder builder, String prefix, String lowered, List<String> ids) {
        int suggested = 0;
        for (String id : ids) {
            if (!id.startsWith(lowered)) {
                continue;
            }
            builder.suggest(prefix + id);
            suggested++;
            if (suggested >= 20) {
                break;
            }
        }
    }

    private static void suggestExamples(SuggestionsBuilder builder, String prefix, String value, List<String> examples, String remaining) {
        for (String example : examples) {
            suggestToken(builder, prefix + example, "Example", remaining);
        }
    }

    private static void suggestToken(SuggestionsBuilder builder, String token, String description, String remaining) {
        String loweredRemaining = remaining.toLowerCase(Locale.ROOT);
        int lastSpace = loweredRemaining.lastIndexOf(' ');
        String leading = lastSpace >= 0 ? loweredRemaining.substring(0, lastSpace + 1) : "";
        String candidate = leading + token.toLowerCase(Locale.ROOT);
        if (!candidate.startsWith(loweredRemaining)) {
            return;
        }
        builder.suggest(leading + token, new LiteralMessage(description));
    }

    private record ActionSuggestion(String value, String description) {
    }
}
