package net.coreprotect.fabric.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

final class LegacyCommandParserTest {
    @ParameterizedTest
    @MethodSource("lookupOnlyFlags")
    void findsLookupOnlyFlags(String input, String expectedFlag) {
        assertEquals(expectedFlag, LegacyCommandParser.findLookupOnlyFlag(input));
    }

    private static Stream<Arguments> lookupOnlyFlags() {
        return Stream.of(
            Arguments.of("u:Alex t:1h #count", "#count"),
            Arguments.of("u:Alex t:1h #SUM", "#SUM"),
            Arguments.of("u:Alex t:1h #summary", "#summary"),
            Arguments.of("u:Alex t:1h count", "count"),
            Arguments.of("u:Alex t:1h sum", "sum"),
            Arguments.of("u:Alex t:1h '#count'", "'#count'")
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"u:Alex t:1h", "u:discount t:1h", "i:minecraft:stone"})
    void ignoresCommandsWithoutStandaloneLookupOnlyFlags(String input) {
        assertNull(LegacyCommandParser.findLookupOnlyFlag(input));
    }

    @Test
    void rollbackSuggestionsExcludeLookupOnlyFlags() {
        assertFalse(StructuredParameterSupport.flagsFor(StructuredParameterSupport.CommandKind.ROLLBACK).contains("#count"));
        assertFalse(StructuredParameterSupport.flagsFor(StructuredParameterSupport.CommandKind.ROLLBACK).contains("#sum"));
        assertFalse(StructuredParameterSupport.flagsFor(StructuredParameterSupport.CommandKind.ROLLBACK).contains("#summary"));
    }
}
