package net.coreprotect.fabric.service;

import net.coreprotect.fabric.db.EventRecord;
import net.coreprotect.fabric.log.CoreProtectEventType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class BlacklistService {
    private final Set<String> entries;

    private BlacklistService(Set<String> entries) {
        this.entries = Set.copyOf(entries);
    }

    public static BlacklistService load(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return new BlacklistService(Set.of());
        }

        Set<String> entries = new LinkedHashSet<>();
        for (String line : Files.readAllLines(path)) {
            String normalized = normalize(line);
            if (normalized != null) {
                entries.add(normalized);
            }
        }
        return new BlacklistService(entries);
    }

    public boolean shouldSkip(EventRecord record) {
        if (record == null) {
            return false;
        }
        if (isBlacklisted(record.actorName())) {
            return true;
        }

        return switch (record.type()) {
            case BLOCK_BREAK, BLOCK_PLACE -> isBlacklisted(record.target());
            case PLAYER_COMMAND -> isBlacklisted(commandRoot(record.target()));
            default -> false;
        };
    }

    public boolean isBlacklisted(String value) {
        String normalized = normalize(value);
        return normalized != null && entries.contains(normalized);
    }

    private String commandRoot(String command) {
        String normalized = normalize(command);
        if (normalized == null) {
            return null;
        }

        int separator = normalized.indexOf(' ');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.replace(" ", "").trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
