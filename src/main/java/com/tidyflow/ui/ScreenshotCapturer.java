package com.tidyflow.ui;

import com.tidyflow.core.dedupe.DuplicateGroup;
import com.tidyflow.core.dedupe.FileEntry;
import com.tidyflow.core.watcher.AutomationPipeline;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.OperationActionType;
import com.tidyflow.data.OperationRecord;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.OperationStatus;
import com.tidyflow.data.config.AppSettings;
import com.tidyflow.data.config.ScheduleConfig;
import com.tidyflow.data.config.SettingsRepository;
import com.tidyflow.data.config.ThemeMode;
import com.tidyflow.data.config.WatchMode;
import com.tidyflow.data.config.WatchedFolderConfig;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Automated JavaFX scene snapshot generator (--capture-screenshots) that populates realistic demo data
 * in an isolated temporary directory and captures PNG screenshots of every tab in Light and Dark themes.
 */
public final class ScreenshotCapturer {

    private ScreenshotCapturer() {
    }

    public static void seedDemoData(
            Path tempDataDir,
            DatabaseManager dbManager,
            BatchRepository batchRepository,
            OperationRepository operationRepository,
            SettingsRepository settingsRepository
    ) throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Path downloads = Path.of("C:\\Users\\Alex\\Downloads");

        // Seed 6 realistic batches across the last 14 days
        record BatchSeed(String kind, int daysAgo, String rule, String filePrefix, String ext, long sizeBytes, int count) {
        }
        List<BatchSeed> seeds = List.of(
                new BatchSeed("MANUAL", 12, "Documents", "Invoice_2026_", ".pdf", 640_000L, 6),
                new BatchSeed("AUTO", 9, "Images", "Design_Mockup_", ".png", 2_450_000L, 9),
                new BatchSeed("SCHEDULED", 6, "Archives", "Release_Bundle_", ".zip", 18_500_000L, 4),
                new BatchSeed("AUTO", 3, "Installers", "Setup_Tool_", ".exe", 42_000_000L, 3),
                new BatchSeed("AUTO", 1, "Videos", "Walkthrough_Clip_", ".mp4", 65_000_000L, 5),
                new BatchSeed("MANUAL", 0, "Documents", "Contract_Signed_", ".docx", 920_000L, 7)
        );

        for (BatchSeed seed : seeds) {
            Instant batchTime = now.minus(seed.daysAgo(), ChronoUnit.DAYS).minusSeconds(300);
            List<OperationRecord> ops = new ArrayList<>();
            long totalBytes = 0L;
            for (int i = 1; i <= seed.count(); i++) {
                String fileName = seed.filePrefix() + i + seed.ext();
                ops.add(OperationRecord.unsaved(
                        seed.rule(),
                        downloads.resolve(fileName),
                        downloads.resolve(seed.rule()).resolve(fileName),
                        seed.sizeBytes(),
                        OperationStatus.SUCCESS,
                        null,
                        batchTime.plusSeconds(i)
                ));
                totalBytes += seed.sizeBytes();
            }
            batchRepository.createBatchWithOperations(
                    seed.kind(),
                    batchTime,
                    batchTime.plusSeconds(15),
                    ops.size(),
                    totalBytes,
                    ops,
                    operationRepository
            );
        }

        // Seed 1 duplicate removal batch
        Instant dedupeTime = now.minus(2, ChronoUnit.DAYS);
        List<OperationRecord> trashOps = List.of(
                OperationRecord.unsavedTrash(
                        downloads.resolve("jdk-21_windows-x64_bin (1).msi"),
                        168_420_000L,
                        OperationStatus.TRASHED,
                        null,
                        dedupeTime
                ),
                OperationRecord.unsavedTrash(
                        downloads.resolve("Q3_Presentation_Copy.pptx"),
                        14_800_000L,
                        OperationStatus.TRASHED,
                        null,
                        dedupeTime.plusSeconds(2)
                )
        );
        batchRepository.createBatchWithOperations(
                "DEDUPE",
                dedupeTime,
                dedupeTime.plusSeconds(5),
                trashOps.size(),
                183_220_000L,
                trashOps,
                operationRepository
        );

        Path watchDownloads = tempDataDir.resolve("Downloads");
        Path watchInbox = tempDataDir.resolve("Desktop_Inbox");
        Files.createDirectories(watchDownloads);
        Files.createDirectories(watchInbox);

        AppSettings demoSettings = new AppSettings(
                List.of(
                        new WatchedFolderConfig(watchDownloads, WatchMode.AUTO, true),
                        new WatchedFolderConfig(watchInbox, WatchMode.ASK, true)
                ),
                5,
                50,
                true,
                true,
                false,
                new ScheduleConfig(true, "09:00"),
                ThemeMode.LIGHT
        );
        settingsRepository.save(demoSettings);
    }

    public static List<DuplicateGroup> createSampleDuplicates() {
        Instant t1 = Instant.now().minus(5, ChronoUnit.DAYS);
        Instant t2 = Instant.now().minus(1, ChronoUnit.DAYS);
        return List.of(
                new DuplicateGroup(
                        "a94f82c1e03b719d4458a2bc9012f38471625349810293847561029384756102",
                        28_450_816L,
                        List.of(
                                new FileEntry(Path.of("C:\\Users\\Alex\\Downloads\\Dataset_Backup_2026.zip"), 28_450_816L, t1, true),
                                new FileEntry(Path.of("C:\\Users\\Alex\\Downloads\\Dataset_Backup_2026 (1).zip"), 28_450_816L, t2, false)
                        )
                ),
                new DuplicateGroup(
                        "b72e19d4f88a301c6621e9ab4410c29384756102938475610293847561029384",
                        4_820_400L,
                        List.of(
                                new FileEntry(Path.of("C:\\Users\\Alex\\Downloads\\Architecture_Diagram_HiRes.png"), 4_820_400L, t1, true),
                                new FileEntry(Path.of("C:\\Users\\Alex\\Downloads\\Copy_of_Architecture_Diagram.png"), 4_820_400L, t2, false)
                        )
                )
        );
    }

    public static void runCaptureSequence(
            Scene scene,
            MainController controller,
            AutomationPipeline pipeline,
            Path outputDir,
            Runnable onComplete
    ) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            System.err.println("[TidyFlow] Failed to create screenshot directory: " + e.getMessage());
            onComplete.run();
            return;
        }

        Path sampleDownloads = Path.of("C:\\Users\\Alex\\Downloads");
        controller.populateShowcaseState(sampleDownloads, createSampleDuplicates());

        List<ShotStepRecord> steps = List.of(
                new ShotStepRecord("dashboard-light.png", 0, ThemeMode.LIGHT),
                new ShotStepRecord("dashboard-dark.png", 0, ThemeMode.DARK),
                new ShotStepRecord("organize.png", 1, ThemeMode.LIGHT),
                new ShotStepRecord("duplicates.png", 2, ThemeMode.LIGHT),
                new ShotStepRecord("rules.png", 3, ThemeMode.LIGHT),
                new ShotStepRecord("automation.png", 4, ThemeMode.LIGHT),
                new ShotStepRecord("history.png", 5, ThemeMode.LIGHT)
        );

        runNextStep(scene, controller, outputDir, steps, 0, onComplete);
    }

    private record ShotStepRecord(String fileName, int tabIndex, ThemeMode theme) {
    }

    private static void runNextStep(
            Scene scene,
            MainController controller,
            Path outputDir,
            List<ShotStepRecord> steps,
            int index,
            Runnable onComplete
    ) {
        if (index >= steps.size()) {
            onComplete.run();
            return;
        }
        try {
            ShotStepRecord step = steps.get(index);
            String fileName = step.fileName();
            int tabIndex = step.tabIndex();
            ThemeMode theme = step.theme();

            ThemeManager.applyTheme(theme);
            ThemeManager.applyToScene(scene);
            controller.selectTabByIndex(tabIndex);
            scene.getRoot().applyCss();
            scene.getRoot().layout();

            PauseTransition settle = new PauseTransition(Duration.millis(450));
            settle.setOnFinished(e -> {
                try {
                    scene.getRoot().applyCss();
                    scene.getRoot().layout();
                    WritableImage fxImage = scene.snapshot(null);
                    Path target = outputDir.resolve(fileName);
                    writeWritableImageToPng(fxImage, target);
                    System.out.println("Captured screenshot: " + target.toAbsolutePath());
                } catch (Exception ex) {
                    System.err.println("Failed capturing " + fileName + ": " + ex.getMessage());
                }
                Platform.runLater(() -> runNextStep(scene, controller, outputDir, steps, index + 1, onComplete));
            });
            settle.play();
        } catch (Exception e) {
            onComplete.run();
        }
    }

    private static void writeWritableImageToPng(WritableImage fxImage, Path targetFile) throws IOException {
        int width = (int) Math.round(fxImage.getWidth());
        int height = (int) Math.round(fxImage.getHeight());
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = fxImage.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buffered, "png", targetFile.toFile());
    }
}
