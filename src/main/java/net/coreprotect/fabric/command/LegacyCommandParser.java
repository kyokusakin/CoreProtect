package net.coreprotect.fabric.command;

import net.coreprotect.fabric.log.CoreProtectEventType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LegacyCommandParser {
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(mo|y|w|d|h|m|s)", Pattern.CASE_INSENSITIVE);

    private LegacyCommandParser() {
    }

    public static LegacyCommandOptions parse(String input) {
        if (input == null || input.isBlank()) {
            return emptyOptions();
        }

        Integer minimumSeconds = null;
        Integer seconds = null;
        Integer radius = null;
        Integer radiusX = null;
        Integer radiusY = null;
        Integer radiusZ = null;
        String coordinates = null;
        String worldFilter = null;
        boolean globalScope = false;
        Integer limit = null;
        Set<String> actorNames = new LinkedHashSet<>();
        Set<CoreProtectEventType> actionFilter = new LinkedHashSet<>();
        Set<String> includeTargets = new LinkedHashSet<>();
        Set<String> rawExcludes = new LinkedHashSet<>();
        boolean preview = false;
        boolean previewCancel = false;
        boolean count = false;
        boolean silent = false;
        boolean verbose = false;

        Mode mode = Mode.NONE;
        String[] rawTokens = input.trim().split("\\s+");
        for (String rawToken : rawTokens) {
            String token = normalize(rawToken);
            if (token.isBlank()) {
                continue;
            }

            if (isCountFlag(token)) {
                count = true;
                mode = Mode.NONE;
                continue;
            }
            if (isPreviewFlag(token)) {
                preview = true;
                mode = Mode.NONE;
                continue;
            }
            if (isPreviewCancelFlag(token)) {
                previewCancel = true;
                mode = Mode.NONE;
                continue;
            }
            if (isSilentFlag(token)) {
                silent = true;
                mode = Mode.NONE;
                continue;
            }
            if (isVerboseFlag(token)) {
                verbose = true;
                mode = Mode.NONE;
                continue;
            }

            PrefixToken prefixToken = parsePrefixToken(token);
            if (prefixToken != null) {
                mode = prefixToken.mode();
                if (prefixToken.value().isBlank()) {
                    continue;
                }
                token = prefixToken.value();
            }

            switch (mode) {
                case TIME -> {
                    TimeRange range = parseTimeRange(token);
                    if (range != null) {
                        minimumSeconds = range.minimumSeconds();
                        seconds = range.maximumSeconds();
                    }
                    mode = token.endsWith(",") ? Mode.TIME : Mode.NONE;
                }
                case RADIUS -> {
                    RadiusSpec parsedRadius = parseRadius(token);
                    if (parsedRadius != null) {
                        radius = parsedRadius.maxRadius();
                        radiusX = parsedRadius.xRadius();
                        radiusY = parsedRadius.yRadius();
                        radiusZ = parsedRadius.zRadius();
                        worldFilter = null;
                        globalScope = false;
                    }
                    else {
                        String parsedWorldFilter = parseWorldFilter(token);
                        if (parsedWorldFilter != null) {
                            radius = null;
                            worldFilter = parsedWorldFilter;
                            globalScope = isGlobalWorldFilter(parsedWorldFilter);
                        }
                    }
                    mode = token.endsWith(",") ? Mode.RADIUS : Mode.NONE;
                }
                case COORDINATE -> {
                    String parsedCoordinates = parseCoordinates(token);
                    if (parsedCoordinates != null) {
                        coordinates = parsedCoordinates;
                    }
                    mode = token.endsWith(",") ? Mode.COORDINATE : Mode.NONE;
                }
                case LIMIT -> {
                    Integer parsedLimit = parseInteger(token);
                    if (parsedLimit != null) {
                        limit = parsedLimit;
                    }
                    mode = token.endsWith(",") ? Mode.LIMIT : Mode.NONE;
                }
                case USER -> mode = appendCsvValues(actorNames, token) ? Mode.USER : Mode.NONE;
                case ACTION -> mode = appendActions(actionFilter, token) ? Mode.ACTION : Mode.NONE;
                case INCLUDE -> mode = appendCsvValues(includeTargets, token) ? Mode.INCLUDE : Mode.NONE;
                case EXCLUDE -> mode = appendCsvValues(rawExcludes, token) ? Mode.EXCLUDE : Mode.NONE;
                case NONE -> {
                    if (!looksLikeBareUser(token)) {
                        continue;
                    }
                    actorNames.add(stripTrailingComma(token));
                }
                default -> mode = Mode.NONE;
            }
        }

        List<String> excludeTargets = new ArrayList<>();
        List<String> excludeActorNames = new ArrayList<>();
        classifyExcludeValues(rawExcludes, actionFilter, excludeTargets, excludeActorNames);

        return new LegacyCommandOptions(
            minimumSeconds,
            seconds,
            radius,
            radiusX,
            radiusY,
            radiusZ,
            coordinates,
            worldFilter,
            globalScope,
            limit,
            actorNames.isEmpty() ? null : new ArrayList<>(actorNames),
            excludeActorNames.isEmpty() ? null : excludeActorNames,
            actionFilter.isEmpty() ? null : new ArrayList<>(actionFilter),
            includeTargets.isEmpty() ? null : new ArrayList<>(includeTargets),
            excludeTargets.isEmpty() ? null : excludeTargets,
            preview,
            previewCancel,
            count,
            silent,
            verbose
        );
    }

    private static LegacyCommandOptions emptyOptions() {
        return new LegacyCommandOptions(null, null, null, null, null, null, null, null, false, null, null, null, null, null, null, false, false, false, false, false);
    }

    private static boolean appendCsvValues(Set<String> target, String token) {
        boolean continueMode = false;
        for (String value : splitCsvToken(token)) {
            if (!value.isBlank()) {
                target.add(value);
            }
        }
        if (token.endsWith(",")) {
            continueMode = true;
        }
        return continueMode;
    }

    private static boolean appendActions(Set<CoreProtectEventType> target, String token) {
        String cleaned = stripTrailingComma(token);
        for (String rawAction : cleaned.split(",")) {
            target.addAll(parseAction(rawAction));
        }
        return token.endsWith(",");
    }

    private static void classifyExcludeValues(
        Set<String> rawExcludes,
        Set<CoreProtectEventType> actionFilter,
        List<String> excludeTargets,
        List<String> excludeActorNames
    ) {
        for (String value : rawExcludes) {
            if (value.isBlank()) {
                continue;
            }

            if (isLikelyTargetExclude(value, actionFilter)) {
                excludeTargets.add(value);
            }
            else {
                excludeActorNames.add(value);
            }
        }
    }

    private static boolean isLikelyTargetExclude(String value, Set<CoreProtectEventType> actionFilter) {
        String cleaned = value.trim().toLowerCase(Locale.ROOT);
        if (cleaned.isBlank()) {
            return false;
        }
        if (isKnownTargetTag(cleaned)) {
            return true;
        }
        if (looksLikeRegisteredIdentifier(cleaned)) {
            return true;
        }
        if (actionFilter == null || actionFilter.isEmpty()) {
            return cleaned.contains(":");
        }
        boolean actorOnly = true;
        for (CoreProtectEventType eventType : actionFilter) {
            if (eventType == CoreProtectEventType.PLAYER_CHAT
                || eventType == CoreProtectEventType.PLAYER_COMMAND
                || eventType == CoreProtectEventType.PLAYER_JOIN
                || eventType == CoreProtectEventType.PLAYER_QUIT
                || eventType == CoreProtectEventType.USERNAME_CHANGE) {
                continue;
            }
            actorOnly = false;
            break;
        }
        if (actorOnly) {
            return false;
        }
        return cleaned.contains(":");
    }

    private static boolean isKnownTargetTag(String value) {
        return "#button".equals(value)
            || "#container".equals(value)
            || "#door".equals(value)
            || "#natural".equals(value)
            || "#pressure_plate".equals(value)
            || "#shulker_box".equals(value);
    }

    private static boolean looksLikeRegisteredIdentifier(String value) {
        Identifier direct = Identifier.tryParse(value);
        if (direct != null && isRegisteredIdentifier(direct)) {
            return true;
        }

        Identifier vanilla = Identifier.tryParse("minecraft:" + value);
        return vanilla != null && isRegisteredIdentifier(vanilla);
    }

    private static boolean isRegisteredIdentifier(Identifier identifier) {
        return Registries.BLOCK.containsId(identifier)
            || Registries.ITEM.containsId(identifier)
            || Registries.ENTITY_TYPE.containsId(identifier);
    }

    private static PrefixToken parsePrefixToken(String token) {
        if (startsWithAny(token, "t:", "time:")) {
            return new PrefixToken(Mode.TIME, stripPrefix(token, "t:", "time:"));
        }
        if (startsWithAny(token, "r:", "radius:", "w:", "world:")) {
            return new PrefixToken(Mode.RADIUS, stripPrefix(token, "r:", "radius:", "w:", "world:"));
        }
        if (startsWithAny(token, "c:", "coord:", "coords:", "coordinate:", "coordinates:", "cord:", "cords:", "cordinate:", "cordinates:", "position:", "location:")) {
            return new PrefixToken(Mode.COORDINATE, stripPrefix(token, "c:", "coord:", "coords:", "coordinate:", "coordinates:", "cord:", "cords:", "cordinate:", "cordinates:", "position:", "location:"));
        }
        if (startsWithAny(token, "rows:", "row:", "limit:", "l:")) {
            return new PrefixToken(Mode.LIMIT, stripPrefix(token, "rows:", "row:", "limit:", "l:"));
        }
        if (startsWithAny(token, "u:", "p:", "user:", "users:")) {
            return new PrefixToken(Mode.USER, stripPrefix(token, "u:", "p:", "user:", "users:"));
        }
        if (startsWithAny(token, "a:", "action:")) {
            return new PrefixToken(Mode.ACTION, stripPrefix(token, "a:", "action:"));
        }
        if (startsWithAny(token, "i:", "include:", "item:", "items:", "b:", "block:", "blocks:")) {
            return new PrefixToken(Mode.INCLUDE, stripPrefix(token, "i:", "include:", "item:", "items:", "b:", "block:", "blocks:"));
        }
        if (startsWithAny(token, "e:", "exclude:")) {
            return new PrefixToken(Mode.EXCLUDE, stripPrefix(token, "e:", "exclude:"));
        }
        return null;
    }

    private static boolean looksLikeBareUser(String token) {
        String cleaned = stripTrailingComma(token);
        if (cleaned.isBlank()) {
            return false;
        }
        if (cleaned.startsWith("#")) {
            return false;
        }
        if (cleaned.contains(":")) {
            return false;
        }
        if (cleaned.matches("\\d+")) {
            return false;
        }
        return !isCountFlag(cleaned)
            && !isPreviewFlag(cleaned)
            && !isSilentFlag(cleaned)
            && !isVerboseFlag(cleaned);
    }

    private static boolean isCountFlag(String token) {
        String cleaned = token.toLowerCase(Locale.ROOT);
        return "#count".equals(cleaned) || "#sum".equals(cleaned) || "count".equals(cleaned) || "sum".equals(cleaned);
    }

    private static boolean isPreviewFlag(String token) {
        String cleaned = token.toLowerCase(Locale.ROOT);
        return "#preview".equals(cleaned) || "preview".equals(cleaned);
    }

    private static boolean isPreviewCancelFlag(String token) {
        String cleaned = token.toLowerCase(Locale.ROOT);
        return "#preview_cancel".equals(cleaned)
            || "#preview-cancel".equals(cleaned)
            || "preview_cancel".equals(cleaned)
            || "preview-cancel".equals(cleaned);
    }

    private static boolean isSilentFlag(String token) {
        return "#silent".equalsIgnoreCase(token) || "silent".equalsIgnoreCase(token);
    }

    private static boolean isVerboseFlag(String token) {
        String cleaned = token.toLowerCase(Locale.ROOT);
        return "n".equals(cleaned)
            || "noisy".equals(cleaned)
            || "v".equals(cleaned)
            || "verbose".equals(cleaned)
            || "#v".equals(cleaned)
            || "#verbose".equals(cleaned);
    }

    private static String normalize(String token) {
        return token.trim().replace("\\", "").replace("'", "").replace("\"", "");
    }

    private static boolean startsWithAny(String input, String... prefixes) {
        for (String prefix : prefixes) {
            if (input.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    private static String stripPrefix(String input, String... prefixes) {
        for (String prefix : prefixes) {
            if (input.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return input.substring(prefix.length());
            }
        }
        return input;
    }

    private static String stripTrailingComma(String input) {
        return input == null ? "" : input.replaceAll(",+$", "").trim();
    }

    private static List<String> splitCsvToken(String token) {
        List<String> values = new ArrayList<>();
        String cleaned = stripTrailingComma(token);
        if (cleaned.isBlank()) {
            return values;
        }
        for (String rawValue : cleaned.split(",")) {
            String value = rawValue.trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static Integer parseInteger(String input) {
        String cleaned = stripTrailingComma(input);
        if (cleaned.isBlank() || !cleaned.matches("-?\\d+")) {
            return null;
        }
        try {
            return Integer.parseInt(cleaned);
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private static RadiusSpec parseRadius(String input) {
        String cleaned = stripTrailingComma(input);
        if (cleaned.isBlank()) {
            return null;
        }

        String[] parts = cleaned.split("x", -1);
        if (parts.length == 1) {
            Integer value = parseInteger(cleaned);
            return value == null ? null : new RadiusSpec(value, null, value, value);
        }
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }

        Integer xRadius = parseInteger(parts[0]);
        Integer second = parseInteger(parts[1]);
        if (xRadius == null || second == null) {
            return null;
        }

        Integer yRadius = null;
        Integer zRadius = second;
        if (parts.length == 3) {
            yRadius = second;
            zRadius = parseInteger(parts[2]);
            if (zRadius == null) {
                return null;
            }
        }

        int maxRadius = Math.max(xRadius, Math.max(yRadius == null ? 0 : yRadius, zRadius));
        return new RadiusSpec(maxRadius, yRadius, xRadius, zRadius);
    }

    private static String parseCoordinates(String input) {
        String cleaned = stripTrailingComma(input);
        if (cleaned.isBlank()) {
            return null;
        }

        String[] parts = cleaned.split(",", -1);
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }

        List<String> numeric = new ArrayList<>();
        for (String part : parts) {
            String value = part.replaceAll("[^0-9.\\-]", "").trim();
            if (value.isBlank() || ".".equals(value) || "-".equals(value) || value.indexOf('.') != value.lastIndexOf('.')) {
                return null;
            }
            try {
                Double.parseDouble(value);
            }
            catch (NumberFormatException exception) {
                return null;
            }
            numeric.add(value);
        }

        return String.join(",", numeric);
    }

    private static Integer parseDuration(String input) {
        String cleaned = stripTrailingComma(input).toLowerCase(Locale.ROOT);
        if (cleaned.isBlank()) {
            return null;
        }
        if (cleaned.matches("\\d+")) {
            try {
                return Integer.parseInt(cleaned);
            }
            catch (NumberFormatException exception) {
                return null;
            }
        }

        Matcher matcher = DURATION_PART.matcher(cleaned);
        double totalSeconds = 0.0D;
        int cursor = 0;
        boolean matched = false;
        while (matcher.find()) {
            String separator = cleaned.substring(cursor, matcher.start());
            if (!separator.isEmpty() && !",".equals(separator)) {
                return null;
            }

            matched = true;
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            totalSeconds += switch (unit) {
                case "y" -> value * 31536000.0D;
                case "mo" -> value * 2592000.0D;
                case "w" -> value * 604800.0D;
                case "d" -> value * 86400.0D;
                case "h" -> value * 3600.0D;
                case "m" -> value * 60.0D;
                case "s" -> value;
                default -> 0.0D;
            };
            cursor = matcher.end();
        }

        if (!matched || cursor != cleaned.length()) {
            return null;
        }
        return (int) Math.round(totalSeconds);
    }

    private static TimeRange parseTimeRange(String input) {
        String cleaned = stripTrailingComma(input);
        if (cleaned.isBlank()) {
            return null;
        }

        int separator = cleaned.indexOf('-');
        if (separator < 0) {
            Integer maximumSeconds = parseDuration(cleaned);
            return maximumSeconds == null ? null : new TimeRange(0, maximumSeconds);
        }

        Integer firstSeconds = parseDuration(cleaned.substring(0, separator));
        Integer secondSeconds = parseDuration(cleaned.substring(separator + 1));
        if (firstSeconds == null || secondSeconds == null) {
            return null;
        }
        return new TimeRange(Math.min(firstSeconds, secondSeconds), Math.max(firstSeconds, secondSeconds));
    }

    private static String parseWorldFilter(String input) {
        String cleaned = stripTrailingComma(input);
        if (cleaned.isBlank()) {
            return null;
        }
        return cleaned.startsWith("#") ? cleaned : "#" + cleaned;
    }

    private static boolean isGlobalWorldFilter(String input) {
        if (input == null) {
            return false;
        }
        String cleaned = input.startsWith("#") ? input.substring(1) : input;
        return "global".equalsIgnoreCase(cleaned);
    }

    private static List<CoreProtectEventType> parseAction(String rawAction) {
        List<CoreProtectEventType> actions = new ArrayList<>();
        String action = rawAction == null ? "" : rawAction.trim().toLowerCase(Locale.ROOT);
        if (action.isBlank()) {
            return actions;
        }

        switch (action) {
            case "broke":
            case "break":
            case "remove":
            case "destroy":
            case "block-break":
            case "block-remove":
            case "-block":
            case "-blocks":
            case "block-":
                actions.add(CoreProtectEventType.BLOCK_BREAK);
                actions.add(CoreProtectEventType.ENTITY_BREAK);
                break;
            case "placed":
            case "place":
            case "block-place":
            case "+block":
            case "+blocks":
            case "block+":
                actions.add(CoreProtectEventType.BLOCK_PLACE);
                actions.add(CoreProtectEventType.ENTITY_PLACE);
                break;
            case "block":
            case "blocks":
            case "block-change":
            case "change":
            case "changes":
                actions.add(CoreProtectEventType.BLOCK_BREAK);
                actions.add(CoreProtectEventType.BLOCK_PLACE);
                actions.add(CoreProtectEventType.ENTITY_BREAK);
                actions.add(CoreProtectEventType.ENTITY_PLACE);
                break;
            case "click":
            case "clicks":
            case "interact":
            case "interaction":
            case "player-interact":
            case "player-interaction":
            case "player-click":
            case "use":
                actions.add(CoreProtectEventType.BLOCK_USE);
                actions.add(CoreProtectEventType.ENTITY_USE);
                break;
            case "death":
            case "deaths":
            case "entity-death":
            case "entity-deaths":
            case "kill":
            case "kills":
            case "entity-kill":
            case "entity-kills":
                actions.add(CoreProtectEventType.ENTITY_KILL);
                break;
            case "container":
            case "container-change":
            case "containers":
            case "chest":
            case "transaction":
            case "transactions":
                actions.add(CoreProtectEventType.CONTAINER_TRANSACTION);
                break;
            case "-container":
            case "container-":
            case "remove-container":
                actions.add(CoreProtectEventType.CONTAINER_TRANSACTION);
                actions.add(CoreProtectEventType.BLOCK_BREAK);
                break;
            case "+container":
            case "container+":
            case "container-add":
            case "add-container":
                actions.add(CoreProtectEventType.CONTAINER_TRANSACTION);
                actions.add(CoreProtectEventType.BLOCK_PLACE);
                break;
            case "chat":
            case "chats":
                actions.add(CoreProtectEventType.PLAYER_CHAT);
                break;
            case "command":
            case "commands":
                actions.add(CoreProtectEventType.PLAYER_COMMAND);
                break;
            case "logins":
            case "login":
            case "+session":
            case "+sessions":
            case "session+":
            case "+connection":
            case "connection+":
                actions.add(CoreProtectEventType.PLAYER_JOIN);
                break;
            case "logout":
            case "logouts":
            case "-session":
            case "-sessions":
            case "session-":
            case "-connection":
            case "connection-":
                actions.add(CoreProtectEventType.PLAYER_QUIT);
                break;
            case "session":
            case "sessions":
            case "connection":
            case "connections":
                actions.add(CoreProtectEventType.PLAYER_JOIN);
                actions.add(CoreProtectEventType.PLAYER_QUIT);
                break;
            case "username":
            case "usernames":
            case "user":
            case "users":
            case "name":
            case "names":
            case "uuid":
            case "uuids":
            case "username-change":
            case "username-changes":
            case "name-change":
            case "name-changes":
                actions.add(CoreProtectEventType.USERNAME_CHANGE);
                break;
            case "sign":
            case "signs":
                actions.add(CoreProtectEventType.SIGN_CHANGE);
                break;
            case "inv":
            case "inventory":
            case "inventories":
                actions.add(CoreProtectEventType.CONTAINER_TRANSACTION);
                addItemActions(actions, true, true);
                break;
            case "-inv":
            case "inv-":
            case "-inventory":
            case "inventory-":
            case "-inventories":
                actions.add(CoreProtectEventType.CONTAINER_TRANSACTION);
                addNegativeItemActions(actions);
                break;
            case "+inv":
            case "inv+":
            case "+inventory":
            case "inventory+":
            case "+inventories":
                actions.add(CoreProtectEventType.CONTAINER_TRANSACTION);
                addPositiveItemActions(actions);
                break;
            case "item":
            case "items":
                addItemActions(actions, true, true);
                break;
            case "-item":
            case "item-":
            case "-items":
            case "items-":
            case "drop":
            case "drops":
            case "deposit":
            case "deposits":
            case "deposited":
                addNegativeItemActions(actions);
                break;
            case "+item":
            case "item+":
            case "+items":
            case "items+":
            case "pickup":
            case "pickups":
            case "withdraw":
            case "withdraws":
            case "withdrew":
                addPositiveItemActions(actions);
                break;
            default:
                break;
        }
        return actions;
    }

    private static void addPositiveItemActions(List<CoreProtectEventType> actions) {
        actions.add(CoreProtectEventType.ITEM_PICKUP);
        actions.add(CoreProtectEventType.ITEM_BUY);
        actions.add(CoreProtectEventType.ITEM_CREATE);
    }

    private static void addNegativeItemActions(List<CoreProtectEventType> actions) {
        actions.add(CoreProtectEventType.ITEM_DROP);
        actions.add(CoreProtectEventType.ITEM_THROW);
        actions.add(CoreProtectEventType.ITEM_SHOOT);
        actions.add(CoreProtectEventType.ITEM_SELL);
        actions.add(CoreProtectEventType.ITEM_DESTROY);
    }

    private static void addItemActions(List<CoreProtectEventType> actions, boolean positive, boolean negative) {
        if (positive) {
            addPositiveItemActions(actions);
        }
        if (negative) {
            addNegativeItemActions(actions);
        }
    }

    private enum Mode {
        NONE,
        TIME,
        RADIUS,
        COORDINATE,
        LIMIT,
        USER,
        ACTION,
        INCLUDE,
        EXCLUDE
    }

    private record PrefixToken(Mode mode, String value) {
    }

    private static final class TimeRange {
        private final int minimumSeconds;
        private final int maximumSeconds;

        private TimeRange(int minimumSeconds, int maximumSeconds) {
            this.minimumSeconds = minimumSeconds;
            this.maximumSeconds = maximumSeconds;
        }

        private int minimumSeconds() {
            return minimumSeconds;
        }

        private int maximumSeconds() {
            return maximumSeconds;
        }
    }

    private record RadiusSpec(int maxRadius, Integer yRadius, int xRadius, int zRadius) {
    }
}
