package com.tidyflow.core;

import com.tidyflow.core.executor.BatchExecutionResult;
import com.tidyflow.core.executor.FileExecutor;
import com.tidyflow.core.executor.UndoManager;
import com.tidyflow.core.executor.UndoSummary;
import com.tidyflow.core.rules.DefaultRules;
import com.tidyflow.core.rules.PlannedOperation;
import com.tidyflow.core.rules.RuleEngine;
import com.tidyflow.core.scanner.FileItem;
import com.tidyflow.core.scanner.FolderScanner;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.OperationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyAndUndoIntegrationTest {

    @Test
    void applyDefaultRulesAndUndoBatchRestoresOriginalFiles(@TempDir Path tempDir) throws Exception {
        Path downloadsDir = Files.createDirectories(tempDir.resolve("Downloads"));
        Path dbFile = tempDir.resolve("appdata").resolve("tidyflow.db");

        DatabaseManager dbManager = new DatabaseManager(dbFile);
        dbManager.migrate();
        BatchRepository batchRepo = new BatchRepository(dbManager);
        OperationRepository opRepo = new OperationRepository(dbManager);

        // Populate Downloads folder with sample files
        Path photo = Files.writeString(downloadsDir.resolve("vacation.jpg"), "jpeg-bytes");
        Path resume = Files.writeString(downloadsDir.resolve("resume.pdf"), "pdf-bytes");
        Path installer = Files.writeString(downloadsDir.resolve("setup.exe"), "exe-bytes");
        Path unmatched = Files.writeString(downloadsDir.resolve("notes.custom"), "custom-bytes");

        FolderScanner scanner = new FolderScanner();
        RuleEngine ruleEngine = new RuleEngine(DefaultRules.create());
        FileExecutor executor = new FileExecutor(batchRepo, opRepo, tempDir.resolve("fallback.log"));
        UndoManager undoManager = new UndoManager(opRepo);

        // 1. Scan & preview
        List<FileItem> scannedBefore = scanner.scan(downloadsDir);
        assertEquals(4, scannedBefore.size());

        List<PlannedOperation> plans = ruleEngine.evaluate(scannedBefore);
        assertEquals(3, plans.size());

        // 2. Apply planned operations
        BatchExecutionResult batchResult = executor.executeBatch(plans, "MANUAL");
        assertTrue(batchResult.databaseSaved());
        assertNotNull(batchResult.batchId());
        assertEquals(3L, batchResult.movedCount());

        // Verify files moved out of root into subfolders
        assertFalse(Files.exists(photo));
        assertFalse(Files.exists(resume));
        assertFalse(Files.exists(installer));
        assertTrue(Files.exists(unmatched));
        assertTrue(Files.exists(downloadsDir.resolve("Images").resolve("vacation.jpg")));
        assertTrue(Files.exists(downloadsDir.resolve("Documents").resolve("resume.pdf")));
        assertTrue(Files.exists(downloadsDir.resolve("Installers").resolve("setup.exe")));

        // 3. Undo the entire batch
        UndoSummary undoSummary = undoManager.undoBatch(batchResult.batchId());
        assertEquals(3, undoSummary.restored());
        assertEquals(0, undoSummary.restoredWithNewName());
        assertEquals(0, undoSummary.failed());

        // 4. Assert all original files and contents are back in the root folder
        List<FileItem> scannedAfterUndo = scanner.scan(downloadsDir);
        Set<String> restoredFileNames = scannedAfterUndo.stream()
                .map(FileItem::name)
                .collect(Collectors.toSet());
        assertEquals(Set.of("vacation.jpg", "resume.pdf", "setup.exe", "notes.custom"), restoredFileNames);
        assertEquals("jpeg-bytes", Files.readString(photo));
        assertEquals("pdf-bytes", Files.readString(resume));
        assertEquals("exe-bytes", Files.readString(installer));
        assertEquals("custom-bytes", Files.readString(unmatched));
    }
}
