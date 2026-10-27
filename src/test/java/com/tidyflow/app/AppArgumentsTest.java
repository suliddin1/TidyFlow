package com.tidyflow.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppArgumentsTest {

    @Test
    void emptyArgsAreValidWithDefaultFlags() {
        AppArguments parsed = AppArguments.parse();
        assertTrue(parsed.isValid());
        assertFalse(parsed.background());
        assertFalse(parsed.selfCheck());
        assertTrue(parsed.selfCheckOutFile().isEmpty());
        assertTrue(parsed.errorMessage().isEmpty());
    }

    @Test
    void parsesBackgroundFlag() {
        AppArguments parsed = AppArguments.parse("--background");
        assertTrue(parsed.isValid());
        assertTrue(parsed.background());
        assertFalse(parsed.selfCheck());
        assertTrue(parsed.selfCheckOutFile().isEmpty());
    }

    @Test
    void parsesSelfCheckWithoutOutFile() {
        AppArguments parsed = AppArguments.parse("--self-check");
        assertTrue(parsed.isValid());
        assertFalse(parsed.background());
        assertTrue(parsed.selfCheck());
        assertTrue(parsed.selfCheckOutFile().isEmpty());
    }

    @Test
    void parsesSelfCheckWithOutFile() {
        AppArguments parsed = AppArguments.parse("--self-check", "--out", "build/check-report.txt");
        assertTrue(parsed.isValid());
        assertFalse(parsed.background());
        assertTrue(parsed.selfCheck());
        assertEquals(Path.of("build/check-report.txt"), parsed.selfCheckOutFile().orElseThrow());
    }

    @Test
    void rejectsUnknownArguments() {
        AppArguments unknownFlag = AppArguments.parse("--unknown");
        assertFalse(unknownFlag.isValid());
        assertTrue(unknownFlag.errorMessage().isPresent());

        AppArguments positional = AppArguments.parse("C:\\Downloads");
        assertFalse(positional.isValid());
        assertTrue(positional.errorMessage().isPresent());
    }

    @Test
    void rejectsMissingValueAfterOutFlag() {
        AppArguments missingAtEnd = AppArguments.parse("--self-check", "--out");
        assertFalse(missingAtEnd.isValid());
        assertTrue(missingAtEnd.errorMessage().orElse("").contains("--out"));

        AppArguments followedByAnotherFlag = AppArguments.parse("--out", "--self-check");
        assertFalse(followedByAnotherFlag.isValid());
        assertTrue(followedByAnotherFlag.errorMessage().orElse("").contains("--out"));

        AppArguments blankValue = AppArguments.parse("--self-check", "--out", "   ");
        assertFalse(blankValue.isValid());
    }

    @Test
    void rejectsOutWithoutSelfCheckOrConflictingModes() {
        AppArguments outAlone = AppArguments.parse("--out", "report.txt");
        assertFalse(outAlone.isValid());

        AppArguments conflicting = AppArguments.parse("--background", "--self-check");
        assertFalse(conflicting.isValid());
    }

    @Test
    void shouldStartHiddenRequiresBackgroundAndTraySupportedAndMinimizeToTrayEnabled() {
        assertTrue(AppArguments.shouldStartHidden(true, true, true));

        assertFalse(AppArguments.shouldStartHidden(false, true, true));
        assertFalse(AppArguments.shouldStartHidden(true, false, true));
        assertFalse(AppArguments.shouldStartHidden(true, true, false));
        assertFalse(AppArguments.shouldStartHidden(true, false, false));
        assertFalse(AppArguments.shouldStartHidden(false, false, false));
    }
}
