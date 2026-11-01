package com.tidyflow.app;

import com.tidyflow.core.dedupe.DesktopTrashService;
import com.tidyflow.core.dedupe.DuplicateFinder;
import com.tidyflow.core.dedupe.DuplicateRemover;
import com.tidyflow.core.executor.FileExecutor;
import com.tidyflow.core.executor.UndoManager;
import com.tidyflow.core.rules.DefaultRules;
import com.tidyflow.core.rules.RuleEngine;
import com.tidyflow.core.scanner.FolderScanner;
import com.tidyflow.core.watcher.AutomationPipeline;
import com.tidyflow.core.watcher.DailySchedulerService;
import com.tidyflow.core.watcher.FolderWatcherService;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.FileIndexRepository;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.StatsRepository;
import com.tidyflow.data.config.AppSettings;
import com.tidyflow.data.config.RuleRepository;
import com.tidyflow.data.config.SettingsRepository;
import com.tidyflow.data.config.ThemeMode;
import com.tidyflow.ui.MainController;
import com.tidyflow.ui.ScreenshotCapturer;
import com.tidyflow.ui.SystemTrayManager;
import com.tidyflow.ui.ThemeManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JavaFX Application entry point that enforces a single running instance, initializes SQLite migrations,
 * starts background folder watching and scheduling, manages the system tray lifecycle, applies themes and icons,
 * and wires services into the UI.
 */
public class TidyFlowApp extends Application {

    private SingleInstanceLock instanceLock;
    private FolderWatcherService folderWatcherService;
    private DailySchedulerService dailySchedulerService;
    private SystemTrayManager systemTrayManager;
    private Path tempCaptureDataDir;

    @Override
    public void start(Stage primaryStage) throws Exception {
        AppArguments appArgs = AppArguments.parse(getParameters().getRaw().toArray(String[]::new));
        boolean screenshotMode = appArgs.captureScreenshotsDir().isPresent();

        if (!screenshotMode) {
            instanceLock = new SingleInstanceLock();
            if (!instanceLock.tryAcquire()) {
                if (!appArgs.background()) {
                    ThemeManager.applyTheme(ThemeMode.SYSTEM);
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("TidyFlow");
                    alert.setHeaderText(null);
                    alert.setContentText("TidyFlow is already running (check the system tray)");
                    ThemeManager.styleDialog(alert);
                    alert.showAndWait();
                }
                Platform.exit();
                return;
            }
        } else {
            tempCaptureDataDir = Files.createTempDirectory("tidyflow-screenshots-");
        }

        DatabaseManager dbManager = screenshotMode
                ? new DatabaseManager(tempCaptureDataDir.resolve("tidyflow.db"))
                : new DatabaseManager();
        dbManager.migrate();

        BatchRepository batchRepository = new BatchRepository(dbManager);
        OperationRepository operationRepository = new OperationRepository(dbManager);
        FileIndexRepository fileIndexRepository = new FileIndexRepository(dbManager);
        StatsRepository statsRepository = new StatsRepository(dbManager);
        RuleRepository ruleRepository = screenshotMode
                ? new RuleRepository(tempCaptureDataDir.resolve("rules.json"))
                : new RuleRepository();
        SettingsRepository settingsRepository = screenshotMode
                ? new SettingsRepository(tempCaptureDataDir.resolve("settings.json"))
                : new SettingsRepository();
        AutostartService autostartService = screenshotMode
                ? new WindowsRegistryAutostartService(() -> null)
                : new WindowsRegistryAutostartService();

        if (screenshotMode) {
            ScreenshotCapturer.seedDemoData(
                    tempCaptureDataDir,
                    dbManager,
                    batchRepository,
                    operationRepository,
                    settingsRepository
            );
        }

        FolderScanner scanner = new FolderScanner();
        RuleEngine ruleEngine = new RuleEngine(DefaultRules.create());
        FileExecutor executor = new FileExecutor(batchRepository, operationRepository);
        UndoManager undoManager = new UndoManager(operationRepository);
        DuplicateFinder duplicateFinder = new DuplicateFinder(fileIndexRepository);
        DuplicateRemover duplicateRemover = new DuplicateRemover(
                new DesktopTrashService(),
                batchRepository,
                operationRepository
        );

        AtomicReference<MainController> controllerRef = new AtomicReference<>();
        AutomationPipeline automationPipeline = new AutomationPipeline(
                executor,
                () -> {
                    MainController ctrl = controllerRef.get();
                    return ctrl != null ? ctrl.getCurrentRulesSnapshot() : DefaultRules.create();
                }
        );

        folderWatcherService = new FolderWatcherService(
                scanner,
                executor,
                automationPipeline,
                Duration.ofSeconds(AppSettings.DEFAULT_SETTLE_SECONDS)
        );
        dailySchedulerService = new DailySchedulerService(scanner, automationPipeline);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/tidyflow/ui/main-view.fxml"));
        loader.setControllerFactory(type -> {
            if (type == MainController.class) {
                MainController controller = new MainController(
                        scanner,
                        ruleEngine,
                        executor,
                        batchRepository,
                        operationRepository,
                        undoManager,
                        duplicateFinder,
                        duplicateRemover,
                        ruleRepository,
                        settingsRepository,
                        automationPipeline,
                        folderWatcherService,
                        dailySchedulerService,
                        autostartService,
                        statsRepository
                );
                controllerRef.set(controller);
                return controller;
            }
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate controller: " + type, e);
            }
        });

        Parent root = loader.load();
        MainController controller = controllerRef.get();

        Runnable showWindowAction = () -> {
            if (!primaryStage.isShowing()) {
                primaryStage.show();
            }
            if (primaryStage.isIconified()) {
                primaryStage.setIconified(false);
            }
            primaryStage.toFront();
            primaryStage.requestFocus();
        };
        controller.setShowWindowCallback(showWindowAction);

        if (!screenshotMode) {
            systemTrayManager = new SystemTrayManager(
                    showWindowAction,
                    controller::togglePauseFromTray,
                    controller::openOrganizeTab,
                    controller::openOrganizeTabForFolder,
                    controller::openHistoryTab,
                    this::shutdownAndExit,
                    () -> controller.getCurrentSettings().showNotifications()
            );
            controller.setSystemTrayManager(systemTrayManager);
            systemTrayManager.initialize();

            if (autostartService.isAvailable()) {
                try {
                    autostartService.setEnabled(controller.getCurrentSettings().startWithWindows());
                } catch (Exception ignored) {
                }
            }
        }

        folderWatcherService.start();

        primaryStage.setOnCloseRequest(event -> {
            if (!screenshotMode
                    && controller.getCurrentSettings().minimizeToTray()
                    && systemTrayManager != null
                    && systemTrayManager.isSupportedAndInstalled()) {
                event.consume();
                primaryStage.hide();
                systemTrayManager.notifyMinimizedToTrayOnce();
            } else {
                shutdownAndExit();
            }
        });

        Scene scene = screenshotMode ? new Scene(root, 1100, 700) : new Scene(root);
        ThemeManager.applyTheme(controller.getCurrentSettings().theme());
        ThemeManager.applyToScene(scene);
        ThemeManager.applyWindowIcons(primaryStage);

        primaryStage.setTitle("TidyFlow");
        primaryStage.setMinWidth(960);
        primaryStage.setMinHeight(640);
        primaryStage.setScene(scene);

        if (screenshotMode) {
            primaryStage.show();
            ScreenshotCapturer.runCaptureSequence(
                    scene,
                    controller,
                    automationPipeline,
                    appArgs.captureScreenshotsDir().orElseThrow(),
                    this::shutdownAndExit
            );
            return;
        }

        boolean startHidden = AppArguments.shouldStartHidden(
                appArgs.background(),
                systemTrayManager != null && systemTrayManager.isSupportedAndInstalled(),
                controller.getCurrentSettings().minimizeToTray()
        );
        if (!startHidden) {
            primaryStage.show();
        }
    }

    private void shutdownAndExit() {
        cleanupResources();
        Platform.exit();
    }

    @Override
    public void stop() {
        cleanupResources();
    }

    private void cleanupResources() {
        if (folderWatcherService != null) {
            folderWatcherService.close();
            folderWatcherService = null;
        }
        if (dailySchedulerService != null) {
            dailySchedulerService.close();
            dailySchedulerService = null;
        }
        if (systemTrayManager != null) {
            systemTrayManager.close();
            systemTrayManager = null;
        }
        if (instanceLock != null) {
            instanceLock.close();
            instanceLock = null;
        }
        if (tempCaptureDataDir != null) {
            try {
                if (Files.exists(tempCaptureDataDir)) {
                    Files.walkFileTree(tempCaptureDataDir, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                            Files.deleteIfExists(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            } catch (IOException ignored) {
            }
            tempCaptureDataDir = null;
        }
    }
}
