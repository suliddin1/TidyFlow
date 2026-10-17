package com.tidyflow.core.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleValidatorTest {

    private final RuleValidator validator = new RuleValidator();

    @Test
    void validRuleWithNormalizedExtensionsPassesValidation() {
        ExtensionCondition extCond = new ExtensionCondition(List.of(".JPG", " PNG ", "WebP"));
        assertEquals(List.of("jpg", "png", "webp"), extCond.extensionList());

        Rule rule = new Rule("Photos", true, 1, List.of(extCond), new MoveAction("Images"));
        List<String> errors = validator.validateRule(rule, List.of());

        assertTrue(errors.isEmpty());
    }

    @Test
    void rejectsBlankRuleName() {
        Rule rule = new Rule("   ", true, 1, List.of(new ExtensionCondition(List.of("jpg"))), new MoveAction("Images"));

        List<String> errors = validator.validateRule(rule, List.of());

        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.toLowerCase().contains("name")));
    }

    @Test
    void rejectsDuplicateRuleNamesCaseInsensitively() {
        Rule existing = new Rule("Invoices", true, 1, List.of(new ExtensionCondition(List.of("pdf"))), new MoveAction("Docs"));
        Rule duplicate = new Rule("invoices", true, 2, List.of(new ExtensionCondition(List.of("pdf"))), new MoveAction("Other"));

        List<String> singleErrors = validator.validateRule(duplicate, List.of(existing));
        assertFalse(singleErrors.isEmpty());
        assertTrue(singleErrors.stream().anyMatch(e -> e.toLowerCase().contains("unique")));

        List<String> batchErrors = validator.validateAll(List.of(existing, duplicate));
        assertFalse(batchErrors.isEmpty());
    }

    @Test
    void rejectsPriorityLessThanOne() {
        Rule zeroPriority = new Rule("Zero", true, 0, List.of(new ExtensionCondition(List.of("jpg"))), new MoveAction("Images"));
        Rule negativePriority = new Rule("Neg", true, -5, List.of(new ExtensionCondition(List.of("jpg"))), new MoveAction("Images"));

        assertFalse(validator.validateRule(zeroPriority, List.of()).isEmpty());
        assertFalse(validator.validateRule(negativePriority, List.of()).isEmpty());
    }

    @Test
    void rejectsRuleWithNoConditions() {
        Rule emptyConditions = new Rule("Empty", true, 1, List.of(), new MoveAction("Images"));

        List<String> errors = validator.validateRule(emptyConditions, List.of());

        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.toLowerCase().contains("condition")));
    }

    @Test
    void rejectsInvalidRegexPattern() {
        Rule invalidRegex = new Rule(
                "BadRegex",
                true,
                1,
                List.of(new RegexFilenameCondition("[unclosed_group")),
                new MoveAction("Docs")
        );

        List<String> errors = validator.validateRule(invalidRegex, List.of());

        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.toLowerCase().contains("regex")));
    }

    @Test
    void rejectsEmptyExtensionListOrExtensionsWithDotsOrSpaces() {
        Rule emptyExts = new Rule("EmptyExt", true, 1, List.of(new ExtensionCondition(List.of("   ", "."))), new MoveAction("Docs"));
        Rule dotInsideExt = new Rule("DotExt", true, 1, List.of(new ExtensionCondition(List.of("tar.gz"))), new MoveAction("Docs"));
        Rule spaceInsideExt = new Rule("SpaceExt", true, 1, List.of(new ExtensionCondition(List.of("my ext"))), new MoveAction("Docs"));

        assertFalse(validator.validateRule(emptyExts, List.of()).isEmpty());
        assertFalse(validator.validateRule(dotInsideExt, List.of()).isEmpty());
        assertFalse(validator.validateRule(spaceInsideExt, List.of()).isEmpty());
    }

    @Test
    void rejectsNonPositiveSizeAndDaysThresholds() {
        Rule zeroSize = new Rule("ZeroSize", true, 1, List.of(new MinSizeCondition(0)), new MoveAction("Large"));
        Rule negativeSize = new Rule("NegSize", true, 1, List.of(new MinSizeCondition(-100)), new MoveAction("Large"));
        Rule zeroDays = new Rule("ZeroDays", true, 1, List.of(new OlderThanDaysCondition(0)), new MoveAction("Old"));
        Rule negativeDays = new Rule("NegDays", true, 1, List.of(new OlderThanDaysCondition(-3)), new MoveAction("Old"));

        assertFalse(validator.validateRule(zeroSize, List.of()).isEmpty());
        assertFalse(validator.validateRule(negativeSize, List.of()).isEmpty());
        assertFalse(validator.validateRule(zeroDays, List.of()).isEmpty());
        assertFalse(validator.validateRule(negativeDays, List.of()).isEmpty());
    }

    @Test
    void rejectsBlankIllegalOrProtectedTargetFolder() {
        Condition validCond = new ExtensionCondition(List.of("txt"));

        Rule blankTarget = new Rule("BlankTarget", true, 1, List.of(validCond), new MoveAction("   "));
        Rule illegalCharsTarget = new Rule("BadChars", true, 1, List.of(validCond), new MoveAction("Folder|With*Illegal?Chars"));
        Rule protectedWindowsTarget = new Rule("WinTarget", true, 1, List.of(validCond), new MoveAction("C:\\Windows\\System32"));
        Rule protectedProgramFilesTarget = new Rule("ProgTarget", true, 1, List.of(validCond), new MoveAction("C:\\Program Files\\MyApp"));

        assertFalse(validator.validateRule(blankTarget, List.of()).isEmpty());
        assertFalse(validator.validateRule(illegalCharsTarget, List.of()).isEmpty());
        assertFalse(validator.validateRule(protectedWindowsTarget, List.of()).isEmpty());
        assertFalse(validator.validateRule(protectedProgramFilesTarget, List.of()).isEmpty());
    }
}
