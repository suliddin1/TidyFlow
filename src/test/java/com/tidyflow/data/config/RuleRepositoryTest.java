package com.tidyflow.data.config;

import com.tidyflow.core.rules.Condition;
import com.tidyflow.core.rules.DefaultRules;
import com.tidyflow.core.rules.ExtensionCondition;
import com.tidyflow.core.rules.MinSizeCondition;
import com.tidyflow.core.rules.MoveAction;
import com.tidyflow.core.rules.OlderThanDaysCondition;
import com.tidyflow.core.rules.PlannedOperation;
import com.tidyflow.core.rules.RegexFilenameCondition;
import com.tidyflow.core.rules.Rule;
import com.tidyflow.core.rules.RuleEngine;
import com.tidyflow.core.rules.RuleValidator;
import com.tidyflow.core.scanner.FileItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleRepositoryTest {

    @Test
    void jsonRoundTripPreservesAllConditionTypesAndMoveAction(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("rules.json");
        RuleRepository repo = new RuleRepository(configFile);

        Rule complexRule = new Rule(
                "Old Large Invoices",
                true,
                1,
                List.of(
                        new ExtensionCondition(List.of("pdf", "docx")),
                        new RegexFilenameCondition("^invoice_\\d{4}\\..+$"),
                        new MinSizeCondition(50_000L),
                        new OlderThanDaysCondition(30L)
                ),
                new MoveAction("Archive\\Invoices")
        );

        repo.save(List.of(complexRule));
        RuleLoadResult loaded = repo.load();

        assertFalse(loaded.recoveredFromBackup());
        assertEquals(1, loaded.rules().size());
        Rule r = loaded.rules().get(0);
        assertEquals("Old Large Invoices", r.name());
        assertTrue(r.enabled());
        assertEquals(1, r.priority());
        assertEquals(4, r.conditions().size());

        Condition c0 = r.conditions().get(0);
        Condition c1 = r.conditions().get(1);
        Condition c2 = r.conditions().get(2);
        Condition c3 = r.conditions().get(3);

        assertTrue(c0 instanceof ExtensionCondition);
        assertEquals(List.of("pdf", "docx"), ((ExtensionCondition) c0).extensionList());

        assertTrue(c1 instanceof RegexFilenameCondition);
        assertEquals("^invoice_\\d{4}\\..+$", ((RegexFilenameCondition) c1).regex());

        assertTrue(c2 instanceof MinSizeCondition);
        assertEquals(50_000L, ((MinSizeCondition) c2).minBytes());

        assertTrue(c3 instanceof OlderThanDaysCondition);
        assertEquals(30L, ((OlderThanDaysCondition) c3).days());

        assertTrue(r.action() instanceof MoveAction);
        assertEquals("Archive\\Invoices", ((MoveAction) r.action()).rawTargetFolder());
    }

    @Test
    void missingFileCreatesDefaultRulesAndMatchesInCodeDefaultsInRuleEngine(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("rules.json");
        RuleRepository repo = new RuleRepository(configFile);

        RuleLoadResult result = repo.load();

        assertFalse(result.recoveredFromBackup());
        assertTrue(Files.exists(configFile));
        assertEquals(DefaultRules.create().size(), result.rules().size());

        List<FileItem> sampleFiles = List.of(
                new FileItem(tempDir.resolve("pic.jpg"), "pic.jpg", "jpg", 100, Instant.now()),
                new FileItem(tempDir.resolve("doc.pdf"), "doc.pdf", "pdf", 200, Instant.now()),
                new FileItem(tempDir.resolve("app.exe"), "app.exe", "exe", 300, Instant.now()),
                new FileItem(tempDir.resolve("bundle.zip"), "bundle.zip", "zip", 400, Instant.now()),
                new FileItem(tempDir.resolve("clip.mp4"), "clip.mp4", "mp4", 500, Instant.now()),
                new FileItem(tempDir.resolve("other.xyz"), "other.xyz", "xyz", 600, Instant.now())
        );

        List<PlannedOperation> expectedPlans = new RuleEngine(DefaultRules.create()).evaluate(sampleFiles);
        List<PlannedOperation> jsonPlans = new RuleEngine(result.rules()).evaluate(sampleFiles);

        assertEquals(expectedPlans, jsonPlans);
    }

    @Test
    void corruptFileIsBackedUpAndDefaultsLoad(@TempDir Path tempDir) throws Exception {
        Path configFile = Files.writeString(tempDir.resolve("rules.json"), "{ not valid json !!!");
        RuleRepository repo = new RuleRepository(configFile);

        RuleLoadResult result = repo.load();

        assertTrue(result.recoveredFromBackup());
        assertNotNull(result.backupPath());
        assertTrue(Files.exists(result.backupPath()));
        assertTrue(result.backupPath().getFileName().toString().startsWith("rules.json.bak-"));
        assertEquals("{ not valid json !!!", Files.readString(result.backupPath()));
        assertEquals(DefaultRules.create().size(), result.rules().size());
    }

    @Test
    void unknownVersionIsBackedUpAndDefaultsLoad(@TempDir Path tempDir) throws Exception {
        String unknownVersionJson = """
                {
                  "version": 99,
                  "rules": []
                }
                """;
        Path configFile = Files.writeString(tempDir.resolve("rules.json"), unknownVersionJson);
        RuleRepository repo = new RuleRepository(configFile);

        RuleLoadResult result = repo.load();

        assertTrue(result.recoveredFromBackup());
        assertNotNull(result.backupPath());
        assertTrue(Files.exists(result.backupPath()));
        assertTrue(result.warningMessage().contains("99"));
        assertEquals(DefaultRules.create().size(), result.rules().size());
    }

    @Test
    void atomicSavePreservesExistingValidFileWhenInterruptedBeforeMove(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("rules.json");
        RuleRepository normalRepo = new RuleRepository(configFile);
        normalRepo.load(); // Creates valid default rules.json (5 rules)

        // Simulate a failure right after writing rules.json.tmp, before replacing rules.json
        RuleRepository interruptedRepo = new RuleRepository(
                configFile,
                new RuleValidator(),
                (tempFile, targetFile) -> {
                    throw new IOException("Simulated crash before atomic move");
                }
        );

        Rule singleNewRule = new Rule(
                "OnlyOneRule",
                true,
                1,
                List.of(new ExtensionCondition(List.of("txt"))),
                new MoveAction("TextFiles")
        );

        assertThrows(IOException.class, () -> interruptedRepo.save(List.of(singleNewRule)));

        // The existing rules.json must still be valid and contain all 5 original default rules
        RuleLoadResult afterCrash = normalRepo.load();
        assertFalse(afterCrash.recoveredFromBackup());
        assertEquals(5, afterCrash.rules().size());
    }
}
