package com.tidyflow.core.rules;

import com.tidyflow.core.scanner.FileItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuleEngineTest {

    @Test
    void selectsFirstEnabledMatchingRuleOrderedByPriority() {
        Path baseDir = Path.of("C:\\Downloads");
        FileItem invoicePdf = new FileItem(
                baseDir.resolve("invoice_2026.pdf"),
                "invoice_2026.pdf",
                "pdf",
                5_000,
                Instant.now()
        );

        Rule genericDocsRule = new Rule(
                "General Documents",
                true,
                20,
                List.of(new ExtensionCondition(List.of("pdf"))),
                new MoveAction("Documents")
        );

        Rule highPriorityInvoicesRule = new Rule(
                "Invoices",
                true,
                5,
                List.of(
                        new ExtensionCondition(List.of("pdf")),
                        new RegexFilenameCondition("^invoice_.*\\.pdf$")
                ),
                new MoveAction("Invoices")
        );

        Rule highestButDisabledRule = new Rule(
                "Disabled Rule",
                false,
                1,
                List.of(new ExtensionCondition(List.of("pdf"))),
                new MoveAction("DisabledFolder")
        );

        RuleEngine engine = new RuleEngine(List.of(genericDocsRule, highPriorityInvoicesRule, highestButDisabledRule));
        List<PlannedOperation> plans = engine.evaluate(List.of(invoicePdf));

        assertEquals(1, plans.size());
        PlannedOperation plan = plans.get(0);
        assertEquals("Invoices", plan.ruleName());
        assertEquals(baseDir.resolve("invoice_2026.pdf"), plan.source());
        assertEquals(baseDir.resolve("Invoices").resolve("invoice_2026.pdf"), plan.destination());
    }

    @Test
    void producesCorrectPlansWithoutTouchingFileSystem(@TempDir Path tempDir) throws Exception {
        Path sourceFile = Files.writeString(tempDir.resolve("photo.png"), "image-bytes");
        FileItem item = new FileItem(sourceFile, "photo.png", "png", 11, Instant.now());

        RuleEngine engine = new RuleEngine(DefaultRules.create());
        List<PlannedOperation> plans = engine.evaluate(List.of(item));

        assertEquals(1, plans.size());
        PlannedOperation plan = plans.get(0);
        assertEquals("Images", plan.ruleName());
        assertEquals(sourceFile, plan.source());
        assertEquals(tempDir.resolve("Images").resolve("photo.png"), plan.destination());

        // Verify dry-run did not create destination directory or move the file
        assertFalse(Files.exists(tempDir.resolve("Images")));
        assertEquals("image-bytes", Files.readString(sourceFile));
    }
}
