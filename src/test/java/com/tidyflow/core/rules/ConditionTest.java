package com.tidyflow.core.rules;

import com.tidyflow.core.scanner.FileItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionTest {

    private static FileItem sampleFile(String name, String ext, long size, Instant modified) {
        return new FileItem(Path.of("C:\\Downloads", name), name, ext, size, modified);
    }

    @Test
    void extensionConditionMatchesCaseInsensitively() {
        ExtensionCondition condition = new ExtensionCondition(List.of("jpg", ".PNG", "webp"));

        assertTrue(condition.matches(sampleFile("photo.jpg", "jpg", 100, Instant.now())));
        assertTrue(condition.matches(sampleFile("banner.PNG", "PNG", 100, Instant.now())));
        assertFalse(condition.matches(sampleFile("document.pdf", "pdf", 100, Instant.now())));
        assertFalse(condition.matches(sampleFile("README", "", 100, Instant.now())));
    }

    @Test
    void regexFilenameConditionMatchesPattern() {
        RegexFilenameCondition condition = new RegexFilenameCondition("^invoice_\\d{4}\\.pdf$");

        assertTrue(condition.matches(sampleFile("invoice_2026.pdf", "pdf", 500, Instant.now())));
        assertFalse(condition.matches(sampleFile("invoice_26.pdf", "pdf", 500, Instant.now())));
        assertFalse(condition.matches(sampleFile("receipt_2026.pdf", "pdf", 500, Instant.now())));
    }

    @Test
    void minSizeConditionMatchesStrictlyGreaterSize() {
        MinSizeCondition condition = new MinSizeCondition(1_024);

        assertTrue(condition.matches(sampleFile("large.zip", "zip", 1_025, Instant.now())));
        assertFalse(condition.matches(sampleFile("exact.zip", "zip", 1_024, Instant.now())));
        assertFalse(condition.matches(sampleFile("small.zip", "zip", 512, Instant.now())));
    }

    @Test
    void olderThanDaysConditionMatchesFilesBeforeCutoff() {
        Instant fixedNow = Instant.parse("2026-10-17T12:00:00Z");
        Clock fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        OlderThanDaysCondition condition = new OlderThanDaysCondition(7, fixedClock);

        FileItem eightDaysOld = sampleFile("old.log", "log", 100, fixedNow.minus(Duration.ofDays(8)));
        FileItem twoDaysOld = sampleFile("recent.log", "log", 100, fixedNow.minus(Duration.ofDays(2)));

        assertTrue(condition.matches(eightDaysOld));
        assertFalse(condition.matches(twoDaysOld));
    }

    @Test
    void ruleMatchesOnlyWhenAllConditionsMatch() {
        Rule rule = new Rule(
                "Large PDFs",
                true,
                1,
                List.of(
                        new ExtensionCondition(List.of("pdf")),
                        new MinSizeCondition(1_000)
                ),
                new MoveAction("LargeDocs")
        );

        assertTrue(rule.matches(sampleFile("book.pdf", "pdf", 2_000, Instant.now())));
        assertFalse(rule.matches(sampleFile("note.pdf", "pdf", 500, Instant.now())));
        assertFalse(rule.matches(sampleFile("video.mp4", "mp4", 5_000, Instant.now())));
    }
}
