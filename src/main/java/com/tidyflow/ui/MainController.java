package com.tidyflow.ui;

import com.tidyflow.app.AutostartService;
import com.tidyflow.app.WindowsRegistryAutostartService;
import com.tidyflow.core.dedupe.DedupeProgress;
import com.tidyflow.core.dedupe.DedupeRemovalSummary;
import com.tidyflow.core.dedupe.DedupeScanOptions;
import com.tidyflow.core.dedupe.DedupeScanResult;
import com.tidyflow.core.dedupe.DuplicateFinder;
import com.tidyflow.core.dedupe.DuplicateGroup;
import com.tidyflow.core.dedupe.DuplicateGroupSelection;
import com.tidyflow.core.dedupe.DuplicateRemover;
import com.tidyflow.core.dedupe.FileEntry;
import com.tidyflow.core.dedupe.KeeperStrategy;
import com.tidyflow.core.executor.BatchExecutionResult;
import com.tidyflow.core.executor.FileExecutor;
import com.tidyflow.core.executor.UndoManager;
import com.tidyflow.core.executor.UndoSummary;
import com.tidyflow.core.rules.DefaultRules;
import com.tidyflow.core.rules.PlannedOperation;
import com.tidyflow.core.rules.Rule;
import com.tidyflow.core.rules.RuleEngine;
import com.tidyflow.core.rules.RulePriorityManager;
import com.tidyflow.core.rules.RuleValidator;
import com.tidyflow.core.scanner.FileItem;
import com.tidyflow.core.scanner.FolderScanner;
import com.tidyflow.core.watcher.ActivityLogEntry;
import com.tidyflow.core.watcher.AutomationListener;
import com.tidyflow.core.watcher.AutomationPipeline;
import com.tidyflow.core.watcher.DailySchedulerService;
import com.tidyflow.core.watcher.FolderWatcherService;
import com.tidyflow.core.watcher.WatchedFolderStatus;
import com.tidyflow.data.BatchRecord;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DashboardStats;
import com.tidyflow.data.OperationActionType;
import com.tidyflow.data.OperationRecord;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.OperationStatus;
import com.tidyflow.data.RuleCount;
import com.tidyflow.data.StatsRepository;
import com.tidyflow.data.TimeBucketCount;
import com.tidyflow.data.config.AppSettings;
import com.tidyflow.data.config.RuleLoadResult;
import com.tidyflow.data.config.RuleRepository;
import com.tidyflow.data.config.ScheduleConfig;
import com.tidyflow.data.config.SettingsLoadResult;
import com.tidyflow.data.config.SettingsRepository;
import com.tidyflow.data.config.SettingsValidator;
import com.tidyflow.data.config.ThemeMode;
import com.tidyflow.data.config.WatchMode;
import com.tidyflow.data.config.WatchedFolderConfig;
import com.tidyflow.util.AppVersion;
import com.tidyflow.util.ByteFormatter;
import com.tidyflow.util.FileNameUtils;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controller for the main TidyFlow window (Dashboard, Organize, Duplicates, Rules, Automation, and History tabs).
 */
public class MainController {

    private static final String RECYCLE_BIN_TOOLTIP = "Restore from the Windows Recycle Bin";
    private static final String AUTOSTART_UNAVAILABLE_TOOLTIP = "Available in the installed version";
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter SHORT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter ACTIVITY_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    @FXML
    private TabPane mainTabPane;
    @FXML
    private Tab dashboardTab;
    @FXML
    private Tab organizeTab;
    @FXML
    private Tab duplicatesTab;
    @FXML
    private Tab rulesTab;
    @FXML
    private Tab automationTab;
    @FXML
    private Tab historyTab;

    // Dashboard tab controls
    @FXML
    private ToggleGroup dashboardRangeGroup;
    @FXML
    private ToggleButton range7DaysButton;
    @FXML
    private ToggleButton range30DaysButton;
    @FXML
    private ToggleButton range90DaysButton;
    @FXML
    private ToggleButton rangeAllTimeButton;
    @FXML
    private VBox dashboardEmptyStateBox;
    @FXML
    private GridPane dashboardContentGrid;
    @FXML
    private Label statFilesOrganizedLabel;
    @FXML
    private Label statSpaceOrganizedLabel;
    @FXML
    private Label statDuplicatesRemovedLabel;
    @FXML
    private Label statSpaceFreedLabel;
    @FXML
    private Label statBatchesLabel;
    @FXML
    private Label statLastActivityLabel;
    @FXML
    private Label filesPerPeriodChartTitle;
    @FXML
    private BarChart<String, Number> filesPerPeriodChart;
    @FXML
    private BarChart<Number, String> filesByRuleChart;
    @FXML
    private ListView<String> dashboardRecentBatchesListView;

    // Organize tab controls
    @FXML
    private Button chooseFolderButton;
    @FXML
    private Label folderPathLabel;
    @FXML
    private Button scanButton;
    @FXML
    private Button applyButton;
    @FXML
    private TableView<PlannedOperation> previewTable;
    @FXML
    private TableColumn<PlannedOperation, String> fileNameColumn;
    @FXML
    private TableColumn<PlannedOperation, String> ruleColumn;
    @FXML
    private TableColumn<PlannedOperation, String> destinationColumn;

    // Duplicates tab controls
    @FXML
    private Button dedupeChooseFolderButton;
    @FXML
    private Label dedupeFolderPathLabel;
    @FXML
    private CheckBox dedupeRecursiveCheckBox;
    @FXML
    private TextField dedupeMinSizeField;
    @FXML
    private ComboBox<String> dedupeSizeUnitCombo;
    @FXML
    private ComboBox<KeeperStrategy> dedupeStrategyCombo;
    @FXML
    private Button findDuplicatesButton;
    @FXML
    private Button cancelDedupeButton;
    @FXML
    private ProgressBar dedupeProgressBar;
    @FXML
    private Label dedupeProgressLabel;
    @FXML
    private TreeTableView<DedupeTreeRow> dedupeTreeTable;
    @FXML
    private TreeTableColumn<DedupeTreeRow, DedupeTreeRow> dedupeRemoveColumn;
    @FXML
    private TreeTableColumn<DedupeTreeRow, String> dedupeNameColumn;
    @FXML
    private TreeTableColumn<DedupeTreeRow, String> dedupeFolderColumn;
    @FXML
    private TreeTableColumn<DedupeTreeRow, String> dedupeSizeColumn;
    @FXML
    private TreeTableColumn<DedupeTreeRow, String> dedupeModifiedColumn;
    @FXML
    private Label dedupeSummaryLabel;
    @FXML
    private Button removeDuplicatesButton;

    // Rules tab controls
    @FXML
    private Button addRuleButton;
    @FXML
    private Button editRuleButton;
    @FXML
    private Button duplicateRuleButton;
    @FXML
    private Button deleteRuleButton;
    @FXML
    private Button moveRuleUpButton;
    @FXML
    private Button moveRuleDownButton;
    @FXML
    private Button resetRulesButton;
    @FXML
    private TableView<Rule> rulesTable;
    @FXML
    private TableColumn<Rule, Rule> ruleEnabledColumn;
    @FXML
    private TableColumn<Rule, String> rulePriorityColumn;
    @FXML
    private TableColumn<Rule, String> ruleNameColumn;
    @FXML
    private TableColumn<Rule, String> ruleConditionsColumn;
    @FXML
    private TableColumn<Rule, String> ruleTargetColumn;
    @FXML
    private TextField tryFileNameField;
    @FXML
    private TextField trySizeField;
    @FXML
    private ComboBox<String> trySizeUnitCombo;
    @FXML
    private TextField tryAgeDaysField;
    @FXML
    private Label tryResultLabel;

    // Automation tab controls
    @FXML
    private Button pauseResumeButton;
    @FXML
    private Label automationStatusLabel;
    @FXML
    private Button addWatchedFolderButton;
    @FXML
    private Button addDownloadsFolderButton;
    @FXML
    private Button removeWatchedFolderButton;
    @FXML
    private TableView<WatchedFolderConfig> watchedFoldersTable;
    @FXML
    private TableColumn<WatchedFolderConfig, String> watchedPathColumn;
    @FXML
    private TableColumn<WatchedFolderConfig, WatchedFolderConfig> watchedModeColumn;
    @FXML
    private TableColumn<WatchedFolderConfig, String> watchedStatusColumn;
    @FXML
    private ListView<String> activityListView;
    @FXML
    private TextField settleSecondsField;
    @FXML
    private TextField massMoveGuardField;
    @FXML
    private CheckBox showNotificationsCheckBox;
    @FXML
    private CheckBox minimizeToTrayCheckBox;
    @FXML
    private StackPane startWithWindowsWrapper;
    @FXML
    private CheckBox startWithWindowsCheckBox;
    @FXML
    private CheckBox scheduleEnabledCheckBox;
    @FXML
    private TextField scheduleTimeField;
    @FXML
    private Button saveAutomationSettingsButton;
    @FXML
    private Label automationValidationLabel;
    @FXML
    private ComboBox<ThemeMode> themeCombo;
    @FXML
    private Label appVersionLabel;

    // History tab controls
    @FXML
    private StackPane undoBatchWrapper;
    @FXML
    private Button undoBatchButton;
    @FXML
    private StackPane undoFileWrapper;
    @FXML
    private Button undoFileButton;
    @FXML
    private Button refreshHistoryButton;
    @FXML
    private TableView<BatchRecord> batchesTable;
    @FXML
    private TableColumn<BatchRecord, String> batchDateColumn;
    @FXML
    private TableColumn<BatchRecord, String> batchFilesColumn;
    @FXML
    private TableColumn<BatchRecord, String> batchSizeColumn;
    @FXML
    private TableView<OperationRecord> operationsTable;
    @FXML
    private TableColumn<OperationRecord, String> opFileNameColumn;
    @FXML
    private TableColumn<OperationRecord, String> opRuleColumn;
    @FXML
    private TableColumn<OperationRecord, String> opFromColumn;
    @FXML
    private TableColumn<OperationRecord, String> opToColumn;
    @FXML
    private TableColumn<OperationRecord, String> opStatusColumn;

    @FXML
    private Label statusLabel;

    private final FolderScanner scanner;
    private RuleEngine ruleEngine;
    private final FileExecutor executor;
    private final BatchRepository batchRepository;
    private final OperationRepository operationRepository;
    private final UndoManager undoManager;
    private final DuplicateFinder duplicateFinder;
    private final DuplicateRemover duplicateRemover;
    private final RuleRepository ruleRepository;
    private final SettingsRepository settingsRepository;
    private final AutomationPipeline automationPipeline;
    private final FolderWatcherService folderWatcherService;
    private final DailySchedulerService dailySchedulerService;
    private final AutostartService autostartService;
    private final StatsRepository statsRepository;
    private final RuleValidator ruleValidator = new RuleValidator();
    private final SettingsValidator settingsValidator = new SettingsValidator();

    private SystemTrayManager systemTrayManager;
    private Runnable showWindowCallback;
    private volatile AppSettings currentSettings = AppSettings.defaults();
    private boolean updatingThemeCombo = false;

    private Path selectedFolder;
    private Path selectedDedupeFolder;
    private List<DuplicateGroup> currentDuplicateGroups = List.of();
    private Task<DedupeScanResult> currentDedupeTask;
    private final AtomicBoolean dedupeCancelRequested = new AtomicBoolean(false);

    private Task<DashboardStats> currentDashboardTask;
    private final AtomicLong dashboardQuerySequence = new AtomicLong(0);

    private final ObservableList<PlannedOperation> plannedOperations = FXCollections.observableArrayList();
    private final ObservableList<Rule> currentRules = FXCollections.observableArrayList();
    private final ObservableList<WatchedFolderConfig> watchedFolders = FXCollections.observableArrayList();
    private final ObservableList<String> activityItems = FXCollections.observableArrayList();
    private final ObservableList<String> recentBatchItems = FXCollections.observableArrayList();
    private final ObservableList<BatchRecord> batches = FXCollections.observableArrayList();
    private final ObservableList<OperationRecord> batchOperations = FXCollections.observableArrayList();

    public MainController() {
        this(
                new FolderScanner(),
                new RuleEngine(DefaultRules.create()),
                new FileExecutor(),
                null,
                null,
                null,
                new DuplicateFinder(),
                null,
                null,
                null,
                null,
                null,
                null,
                new WindowsRegistryAutostartService(),
                null
        );
    }

    public MainController(
            FolderScanner scanner,
            RuleEngine ruleEngine,
            FileExecutor executor,
            BatchRepository batchRepository,
            OperationRepository operationRepository,
            UndoManager undoManager,
            DuplicateFinder duplicateFinder,
            DuplicateRemover duplicateRemover
    ) {
        this(scanner, ruleEngine, executor, batchRepository, operationRepository, undoManager, duplicateFinder, duplicateRemover, null);
    }

    public MainController(
            FolderScanner scanner,
            RuleEngine ruleEngine,
            FileExecutor executor,
            BatchRepository batchRepository,
            OperationRepository operationRepository,
            UndoManager undoManager,
            DuplicateFinder duplicateFinder,
            DuplicateRemover duplicateRemover,
            RuleRepository ruleRepository
    ) {
        this(
                scanner,
                ruleEngine,
                executor,
                batchRepository,
                operationRepository,
                undoManager,
                duplicateFinder,
                duplicateRemover,
                ruleRepository,
                null,
                null,
                null,
                null,
                new WindowsRegistryAutostartService(),
                null
        );
    }

    public MainController(
            FolderScanner scanner,
            RuleEngine ruleEngine,
            FileExecutor executor,
            BatchRepository batchRepository,
            OperationRepository operationRepository,
            UndoManager undoManager,
            DuplicateFinder duplicateFinder,
            DuplicateRemover duplicateRemover,
            RuleRepository ruleRepository,
            SettingsRepository settingsRepository,
            AutomationPipeline automationPipeline,
            FolderWatcherService folderWatcherService,
            DailySchedulerService dailySchedulerService,
            AutostartService autostartService
    ) {
        this(
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
                null
        );
    }

    public MainController(
            FolderScanner scanner,
            RuleEngine ruleEngine,
            FileExecutor executor,
            BatchRepository batchRepository,
            OperationRepository operationRepository,
            UndoManager undoManager,
            DuplicateFinder duplicateFinder,
            DuplicateRemover duplicateRemover,
            RuleRepository ruleRepository,
            SettingsRepository settingsRepository,
            AutomationPipeline automationPipeline,
            FolderWatcherService folderWatcherService,
            DailySchedulerService dailySchedulerService,
            AutostartService autostartService,
            StatsRepository statsRepository
    ) {
        this.scanner = scanner;
        this.ruleEngine = ruleEngine;
        this.executor = executor;
        this.batchRepository = batchRepository;
        this.operationRepository = operationRepository;
        this.undoManager = undoManager;
        this.duplicateFinder = duplicateFinder;
        this.duplicateRemover = duplicateRemover;
        this.ruleRepository = ruleRepository;
        this.settingsRepository = settingsRepository;
        this.automationPipeline = automationPipeline;
        this.folderWatcherService = folderWatcherService;
        this.dailySchedulerService = dailySchedulerService;
        this.autostartService = autostartService != null ? autostartService : new WindowsRegistryAutostartService();
        this.statsRepository = statsRepository;
    }

    public void setSystemTrayManager(SystemTrayManager systemTrayManager) {
        this.systemTrayManager = systemTrayManager;
    }

    public void setShowWindowCallback(Runnable showWindowCallback) {
        this.showWindowCallback = showWindowCallback;
    }

    public List<Rule> getCurrentRulesSnapshot() {
        return List.copyOf(currentRules);
    }

    public AppSettings getCurrentSettings() {
        return currentSettings;
    }

    @FXML
    public void initialize() {
        initDashboardTab();
        initOrganizeTab();
        initDuplicatesTab();
        initRulesTab();
        initAutomationTab();
        initHistoryTab();
        refreshDashboard();
    }

    private void initDashboardTab() {
        if (range7DaysButton == null) {
            return;
        }
        if (dashboardRangeGroup != null) {
            // Prevent deselecting all range buttons
            dashboardRangeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
                if (newToggle == null && oldToggle != null) {
                    oldToggle.setSelected(true);
                }
            });
        }
        if (dashboardRecentBatchesListView != null) {
            dashboardRecentBatchesListView.setItems(recentBatchItems);
        }
    }

    @FXML
    private void onDashboardTabSelected() {
        if (dashboardTab != null && dashboardTab.isSelected()) {
            refreshDashboard();
        }
    }

    @FXML
    private void onDashboardRangeChanged() {
        refreshDashboard();
    }

    @FXML
    private void onDashboardScanFolder() {
        openOrganizeTab();
    }

    @FXML
    private void onDashboardSetupAutomation() {
        if (mainTabPane != null && automationTab != null) {
            mainTabPane.getSelectionModel().select(automationTab);
        }
    }

    @FXML
    private void onOpenHistoryFromDashboard() {
        openHistoryTab();
    }

    public void refreshDashboard() {
        if (statsRepository == null || range30DaysButton == null) {
            return;
        }

        if (currentDashboardTask != null && currentDashboardTask.isRunning()) {
            currentDashboardTask.cancel(true);
        }

        final long requestId = dashboardQuerySequence.incrementAndGet();
        final ZoneId localZone = ZoneId.systemDefault();
        final LocalDate today = LocalDate.now(localZone);
        final Instant startInclusive;

        if (range7DaysButton.isSelected()) {
            startInclusive = today.minusDays(6).atStartOfDay(localZone).toInstant();
        } else if (range90DaysButton.isSelected()) {
            startInclusive = today.minusDays(89).atStartOfDay(localZone).toInstant();
        } else if (rangeAllTimeButton.isSelected()) {
            startInclusive = null;
        } else {
            // Default: Last 30 days
            startInclusive = today.minusDays(29).atStartOfDay(localZone).toInstant();
        }

        Task<DashboardStats> task = new Task<>() {
            @Override
            protected DashboardStats call() throws Exception {
                return statsRepository.loadStats(startInclusive);
            }
        };

        task.setOnSucceeded(event -> {
            if (requestId != dashboardQuerySequence.get()) {
                return; // Ignore stale result
            }
            DashboardStats stats = task.getValue();
            applyDashboardStatsToUi(stats);
        });

        task.setOnFailed(event -> {
            if (requestId != dashboardQuerySequence.get()) {
                return;
            }
            Throwable ex = task.getException();
            if (!(ex instanceof CancellationException)) {
                statusLabel.setText("Dashboard refresh failed: " + (ex != null ? ex.getMessage() : "unknown error"));
            }
        });

        currentDashboardTask = task;
        Thread worker = new Thread(task, "tidyflow-dashboard-stats-thread");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyDashboardStatsToUi(DashboardStats stats) {
        if (stats == null) {
            return;
        }

        NumberFormat intFormat = NumberFormat.getIntegerInstance(Locale.getDefault());
        statFilesOrganizedLabel.setText(intFormat.format(stats.filesOrganized()));
        statSpaceOrganizedLabel.setText(ByteFormatter.format(stats.spaceOrganizedBytes()));
        statDuplicatesRemovedLabel.setText(intFormat.format(stats.duplicatesRemoved()));
        statSpaceFreedLabel.setText(ByteFormatter.format(stats.spaceFreedBytes()));
        statBatchesLabel.setText(intFormat.format(stats.batchesCount()));
        statLastActivityLabel.setText(formatRelativeOrDate(stats.lastActivity()));

        boolean empty = stats.isEmpty() && batches.isEmpty();
        dashboardEmptyStateBox.setVisible(empty);
        dashboardEmptyStateBox.setManaged(empty);
        dashboardContentGrid.setVisible(!empty);
        dashboardContentGrid.setManaged(!empty);

        if (empty) {
            return;
        }

        filesPerPeriodChartTitle.setText(stats.monthlyBuckets() ? "Files organized per month" : "Files organized per day");

        XYChart.Series<String, Number> timeSeries = new XYChart.Series<>();
        for (TimeBucketCount bucket : stats.filesPerPeriod()) {
            timeSeries.getData().add(new XYChart.Data<>(bucket.label(), bucket.count()));
        }
        filesPerPeriodChart.getData().setAll(List.of(timeSeries));

        List<RuleCount> topRules = new ArrayList<>(stats.topRulesWithOther());
        Collections.reverse(topRules); // Reverse so the highest rule appears at the top of the horizontal BarChart
        XYChart.Series<Number, String> ruleSeries = new XYChart.Series<>();
        for (RuleCount rc : topRules) {
            ruleSeries.getData().add(new XYChart.Data<>(rc.count(), rc.ruleName()));
        }
        filesByRuleChart.getData().setAll(List.of(ruleSeries));

        List<String> recentLines = new ArrayList<>();
        for (BatchRecord b : stats.recentBatches()) {
            Instant batchInstant = b.finishedAt() != null ? b.finishedAt() : b.startedAt();
            String formattedDate = batchInstant != null ? SHORT_DATE_FORMATTER.format(batchInstant) : "Unknown date";
            String line = String.format(
                    "%s%s  —  %s file(s), %s",
                    formatBatchKindPrefix(b.kind()),
                    formattedDate,
                    intFormat.format(b.filesCount()),
                    ByteFormatter.format(b.bytesMoved())
            );
            recentLines.add(line);
        }
        recentBatchItems.setAll(recentLines);
    }

    public static String formatRelativeOrDate(Instant timestamp) {
        if (timestamp == null) {
            return "No activity yet";
        }
        Duration diff = Duration.between(timestamp, Instant.now());
        if (diff.isNegative() || diff.toSeconds() < 60) {
            return "Just now";
        }
        long minutes = diff.toMinutes();
        if (minutes < 60) {
            return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
        }
        long hours = diff.toHours();
        if (hours < 24) {
            return hours == 1 ? "1 hour ago" : hours + " hours ago";
        }
        long days = diff.toDays();
        if (days < 7) {
            return days == 1 ? "Yesterday" : days + " days ago";
        }
        return SHORT_DATE_FORMATTER.format(timestamp);
    }

    private void initOrganizeTab() {
        fileNameColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().source().getFileName().toString()));
        ruleColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().ruleName()));
        destinationColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().destination().toString()));
        previewTable.setItems(plannedOperations);
    }

    private void initDuplicatesTab() {
        dedupeSizeUnitCombo.setItems(FXCollections.observableArrayList("KB", "MB"));
        dedupeSizeUnitCombo.getSelectionModel().select("KB");

        dedupeStrategyCombo.setItems(FXCollections.observableArrayList(KeeperStrategy.values()));
        dedupeStrategyCombo.getSelectionModel().select(KeeperStrategy.OLDEST_MODIFIED);
        dedupeStrategyCombo.valueProperty().addListener((obs, oldStrategy, newStrategy) -> {
            if (newStrategy != null && !currentDuplicateGroups.isEmpty()) {
                List<DuplicateGroup> restyled = currentDuplicateGroups.stream()
                        .map(newStrategy::apply)
                        .toList();
                populateDedupeTree(restyled);
            }
        });

        dedupeTreeTable.setRoot(new TreeItem<>());

        dedupeRemoveColumn.setCellValueFactory(param -> new SimpleObjectProperty<>(param.getValue().getValue()));
        dedupeRemoveColumn.setCellFactory(col -> new TreeTableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private final Label keepBadge = new Label("Keep");
            private final HBox box = new HBox(6, checkBox, keepBadge);

            {
                keepBadge.getStyleClass().add("keep-badge");
                box.setAlignment(Pos.CENTER_LEFT);
                checkBox.setOnAction(event -> {
                    DedupeTreeRow row = getItem();
                    if (row == null || row.isGroupRow()) {
                        return;
                    }
                    TreeItem<DedupeTreeRow> treeItem = getTableRow().getTreeItem();
                    TreeItem<DedupeTreeRow> groupItem = treeItem != null ? treeItem.getParent() : null;
                    if (checkBox.isSelected() && groupItem != null && wouldSelectAllInGroup(groupItem, row)) {
                        checkBox.setSelected(false);
                        row.setSelectedForRemoval(false);
                        statusLabel.setText("Cannot select all copies in a group: at least one file must be kept.");
                        Alert warn = new Alert(Alert.AlertType.WARNING);
                        warn.setTitle("Keeper Required");
                        warn.setHeaderText(null);
                        warn.setContentText("At least one copy in each duplicate group must be kept.");
                        styleAlert(warn);
                        warn.showAndWait();
                        return;
                    }
                    row.setSelectedForRemoval(checkBox.isSelected());
                    keepBadge.setVisible(!checkBox.isSelected());
                    keepBadge.setManaged(!checkBox.isSelected());
                    updateDedupeSummary();
                });
            }

            @Override
            protected void updateItem(DedupeTreeRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null || row.isGroupRow()) {
                    setGraphic(null);
                } else {
                    checkBox.setSelected(row.isSelectedForRemoval());
                    keepBadge.setVisible(!row.isSelectedForRemoval());
                    keepBadge.setManaged(!row.isSelectedForRemoval());
                    setGraphic(box);
                }
            }
        });

        dedupeNameColumn.setCellValueFactory(param -> {
            DedupeTreeRow row = param.getValue().getValue();
            if (row == null) {
                return new SimpleStringProperty("");
            }
            if (row.isGroupRow()) {
                return new SimpleStringProperty(String.format(
                        "Group (%d files, %s each)",
                        row.group().files().size(),
                        ByteFormatter.format(row.group().sizeBytes())
                ));
            }
            return new SimpleStringProperty(row.fileEntry().fileName());
        });

        dedupeFolderColumn.setCellValueFactory(param -> {
            DedupeTreeRow row = param.getValue().getValue();
            if (row == null) {
                return new SimpleStringProperty("");
            }
            if (row.isGroupRow()) {
                return new SimpleStringProperty("SHA-256: " + row.group().sha256().substring(0, 12) + "...");
            }
            return new SimpleStringProperty(row.fileEntry().parentFolder());
        });

        dedupeSizeColumn.setCellValueFactory(param -> {
            DedupeTreeRow row = param.getValue().getValue();
            if (row == null) {
                return new SimpleStringProperty("");
            }
            if (row.isGroupRow()) {
                return new SimpleStringProperty(ByteFormatter.format(row.group().wastedBytes()) + " wasted");
            }
            return new SimpleStringProperty(ByteFormatter.format(row.fileEntry().sizeBytes()));
        });

        dedupeModifiedColumn.setCellValueFactory(param -> {
            DedupeTreeRow row = param.getValue().getValue();
            if (row == null || row.isGroupRow()) {
                return new SimpleStringProperty("");
            }
            return new SimpleStringProperty(DATE_FORMATTER.format(row.fileEntry().lastModified()));
        });
    }

    private void initRulesTab() {
        ruleEnabledColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        ruleEnabledColumn.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();

            {
                checkBox.setOnAction(e -> {
                    Rule current = getItem();
                    if (current == null) {
                        return;
                    }
                    int idx = getIndex();
                    if (idx >= 0 && idx < currentRules.size()) {
                        Rule toggled = new Rule(
                                current.name(),
                                checkBox.isSelected(),
                                current.priority(),
                                current.conditions(),
                                current.action()
                        );
                        List<Rule> updated = new ArrayList<>(currentRules);
                        updated.set(idx, toggled);
                        saveAndApplyRules(updated, idx);
                    }
                });
            }

            @Override
            protected void updateItem(Rule rule, boolean empty) {
                super.updateItem(rule, empty);
                if (empty || rule == null) {
                    setGraphic(null);
                } else {
                    checkBox.setSelected(rule.enabled());
                    setGraphic(checkBox);
                }
            }
        });

        rulePriorityColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(String.valueOf(cell.getValue().priority())));
        ruleNameColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().name()));
        ruleConditionsColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(RulePriorityManager.summarizeConditions(cell.getValue())));
        ruleTargetColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(RulePriorityManager.summarizeAction(cell.getValue())));

        rulesTable.setItems(currentRules);
        rulesTable.getSelectionModel().selectedIndexProperty().addListener((obs, oldIdx, newIdx) ->
                updateRuleButtonsState());

        trySizeUnitCombo.setItems(FXCollections.observableArrayList("KB", "MB"));
        trySizeUnitCombo.getSelectionModel().select("KB");

        tryFileNameField.textProperty().addListener((obs, o, n) -> evaluateTryItBox());
        trySizeField.textProperty().addListener((obs, o, n) -> evaluateTryItBox());
        trySizeUnitCombo.valueProperty().addListener((obs, o, n) -> evaluateTryItBox());
        tryAgeDaysField.textProperty().addListener((obs, o, n) -> evaluateTryItBox());

        loadInitialRules();
    }

    private void loadInitialRules() {
        if (ruleRepository == null) {
            List<Rule> defaults = RulePriorityManager.normalizePriorities(DefaultRules.create());
            currentRules.setAll(defaults);
            this.ruleEngine = new RuleEngine(defaults);
            evaluateTryItBox();
            return;
        }
        try {
            RuleLoadResult result = ruleRepository.load();
            List<Rule> normalized = RulePriorityManager.normalizePriorities(result.rules());
            currentRules.setAll(normalized);
            this.ruleEngine = new RuleEngine(normalized);
            evaluateTryItBox();

            if (result.recoveredFromBackup()) {
                Platform.runLater(() -> {
                    Alert warn = new Alert(Alert.AlertType.WARNING);
                    warn.setTitle("Rules Config Recovered");
                    warn.setHeaderText("Restored Default Rules");
                    warn.setContentText(result.warningMessage());
                    styleAlert(warn);
                    warn.showAndWait();
                });
            }
        } catch (Exception e) {
            List<Rule> defaults = RulePriorityManager.normalizePriorities(DefaultRules.create());
            currentRules.setAll(defaults);
            this.ruleEngine = new RuleEngine(defaults);
            evaluateTryItBox();
        }
    }

    @FXML
    private void onAddRule() {
        int nextPriority = currentRules.size() + 1;
        RuleEditorDialog dialog = new RuleEditorDialog(null, nextPriority, List.copyOf(currentRules), ruleValidator);
        Optional<Rule> created = dialog.showAndWait(addRuleButton.getScene().getWindow());
        if (created.isPresent()) {
            List<Rule> updated = new ArrayList<>(currentRules);
            updated.add(created.get());
            List<Rule> renumbered = RulePriorityManager.renumberSequential(updated);
            saveAndApplyRules(renumbered, renumbered.size() - 1);
        }
    }

    @FXML
    private void onEditRule() {
        int idx = rulesTable.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= currentRules.size()) {
            return;
        }
        Rule target = currentRules.get(idx);
        List<Rule> others = new ArrayList<>(currentRules);
        others.remove(idx);

        RuleEditorDialog dialog = new RuleEditorDialog(target, target.priority(), others, ruleValidator);
        Optional<Rule> edited = dialog.showAndWait(editRuleButton.getScene().getWindow());
        if (edited.isPresent()) {
            List<Rule> updated = new ArrayList<>(currentRules);
            updated.set(idx, edited.get());
            saveAndApplyRules(RulePriorityManager.renumberSequential(updated), idx);
        }
    }

    @FXML
    private void onDuplicateRule() {
        int idx = rulesTable.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= currentRules.size()) {
            return;
        }
        Rule source = currentRules.get(idx);
        String copyName = generateUniqueCopyName(source.name());
        Rule copy = new Rule(
                copyName,
                source.enabled(),
                currentRules.size() + 1,
                source.conditions(),
                source.action()
        );
        List<Rule> updated = new ArrayList<>(currentRules);
        updated.add(idx + 1, copy);
        List<Rule> renumbered = RulePriorityManager.renumberSequential(updated);
        saveAndApplyRules(renumbered, idx + 1);
    }

    @FXML
    private void onDeleteRule() {
        int idx = rulesTable.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= currentRules.size()) {
            return;
        }
        Rule target = currentRules.get(idx);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Delete Rule");
        confirm.setHeaderText("Delete rule '" + target.name() + "'?");
        confirm.setContentText("Are you sure you want to delete this rule?");
        styleAlert(confirm);

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        List<Rule> updated = new ArrayList<>(currentRules);
        updated.remove(idx);
        List<Rule> renumbered = RulePriorityManager.renumberSequential(updated);
        saveAndApplyRules(renumbered, Math.min(idx, renumbered.size() - 1));
    }

    @FXML
    private void onMoveRuleUp() {
        int idx = rulesTable.getSelectionModel().getSelectedIndex();
        if (idx <= 0 || idx >= currentRules.size()) {
            return;
        }
        List<Rule> moved = RulePriorityManager.moveUp(currentRules, idx);
        saveAndApplyRules(moved, idx - 1);
    }

    @FXML
    private void onMoveRuleDown() {
        int idx = rulesTable.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= currentRules.size() - 1) {
            return;
        }
        List<Rule> moved = RulePriorityManager.moveDown(currentRules, idx);
        saveAndApplyRules(moved, idx + 1);
    }

    @FXML
    private void onResetRulesToDefaults() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Reset Rules");
        confirm.setHeaderText("Reset all rules to built-in defaults?");
        confirm.setContentText("Any custom rules or edits will be replaced by the default rules.");
        styleAlert(confirm);

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        List<Rule> defaults = RulePriorityManager.normalizePriorities(DefaultRules.create());
        saveAndApplyRules(defaults, 0);
    }

    private void saveAndApplyRules(List<Rule> updatedRules, int selectIndex) {
        try {
            if (ruleRepository != null) {
                ruleRepository.save(updatedRules);
            }
            currentRules.setAll(updatedRules);
            this.ruleEngine = new RuleEngine(updatedRules);
            if (selectIndex >= 0 && selectIndex < currentRules.size()) {
                rulesTable.getSelectionModel().select(selectIndex);
            }
            updateRuleButtonsState();
            evaluateTryItBox();
            statusLabel.setText("Saved " + updatedRules.size() + " rule(s).");
        } catch (Exception e) {
            showError("Failed to save rules", e.getMessage());
        }
    }

    private void updateRuleButtonsState() {
        int idx = rulesTable.getSelectionModel().getSelectedIndex();
        boolean hasSelection = idx >= 0 && idx < currentRules.size();
        editRuleButton.setDisable(!hasSelection);
        duplicateRuleButton.setDisable(!hasSelection);
        deleteRuleButton.setDisable(!hasSelection);
        moveRuleUpButton.setDisable(!hasSelection || idx == 0);
        moveRuleDownButton.setDisable(!hasSelection || idx == currentRules.size() - 1);
    }

    private String generateUniqueCopyName(String baseName) {
        int counter = 1;
        while (true) {
            String candidate = counter == 1 ? baseName + " (Copy)" : baseName + " (Copy " + counter + ")";
            boolean exists = currentRules.stream()
                    .anyMatch(r -> r.name().equalsIgnoreCase(candidate));
            if (!exists) {
                return candidate;
            }
            counter++;
        }
    }

    private void evaluateTryItBox() {
        if (tryFileNameField == null || tryResultLabel == null) {
            return;
        }
        String fileName = tryFileNameField.getText() != null ? tryFileNameField.getText().trim() : "";
        if (fileName.isEmpty()) {
            tryResultLabel.setText("no rule matches");
            return;
        }

        long sizeVal = parseLongOrZero(trySizeField.getText());
        long multiplier = "MB".equalsIgnoreCase(trySizeUnitCombo.getValue()) ? 1024L * 1024L : 1024L;
        long sizeBytes = Math.max(0L, sizeVal * multiplier);

        long ageDays = Math.max(0L, parseLongOrZero(tryAgeDaysField.getText()));
        Instant syntheticModified = Instant.now().minus(Duration.ofDays(ageDays)).minusSeconds(60);

        FileItem sample = new FileItem(
                Path.of(fileName),
                fileName,
                FileNameUtils.getExtension(fileName),
                sizeBytes,
                syntheticModified
        );

        List<PlannedOperation> matches = ruleEngine.evaluate(List.of(sample));
        if (matches.isEmpty()) {
            tryResultLabel.setText("no rule matches");
        } else {
            PlannedOperation match = matches.get(0);
            tryResultLabel.setText(match.ruleName() + " -> " + match.destination());
        }
    }

    private static long parseLongOrZero(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void initAutomationTab() {
        watchedPathColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().path().toString()));

        watchedModeColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        watchedModeColumn.setCellFactory(col -> new TableCell<>() {
            private final ComboBox<WatchMode> modeCombo = new ComboBox<>(
                    FXCollections.observableArrayList(WatchMode.OFF, WatchMode.ASK, WatchMode.AUTO)
            );
            private boolean updatingGraphic = false;

            {
                modeCombo.setPrefWidth(100.0);
                modeCombo.setOnAction(e -> {
                    if (updatingGraphic) {
                        return;
                    }
                    WatchedFolderConfig current = getItem();
                    if (current == null) {
                        return;
                    }
                    WatchMode chosenMode = modeCombo.getValue();
                    if (chosenMode == null || chosenMode == current.mode()) {
                        return;
                    }
                    if (chosenMode == WatchMode.AUTO && current.mode() != WatchMode.AUTO) {
                        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                        confirm.setTitle("Confirm Auto Mode");
                        confirm.setHeaderText("Switch '" + current.path().getFileName() + "' to AUTO mode?");
                        confirm.setContentText("Files in this folder will be moved automatically using your saved rules.\n"
                                + "Every batch is recorded and can be undone from the History tab.");
                        styleAlert(confirm);
                        Optional<ButtonType> result = confirm.showAndWait();
                        if (result.isEmpty() || result.get() != ButtonType.OK) {
                            updatingGraphic = true;
                            modeCombo.setValue(current.mode());
                            updatingGraphic = false;
                            return;
                        }
                    }

                    int idx = getIndex();
                    if (idx >= 0 && idx < watchedFolders.size()) {
                        WatchedFolderConfig updatedEntry = new WatchedFolderConfig(
                                current.path(),
                                chosenMode,
                                chosenMode != WatchMode.OFF
                        );
                        List<WatchedFolderConfig> updatedList = new ArrayList<>(watchedFolders);
                        updatedList.set(idx, updatedEntry);
                        applyAndSaveWatchedFolders(updatedList, idx);
                    }
                });
            }

            @Override
            protected void updateItem(WatchedFolderConfig item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    updatingGraphic = true;
                    modeCombo.setValue(item.mode());
                    updatingGraphic = false;
                    setGraphic(modeCombo);
                }
            }
        });

        watchedStatusColumn.setCellValueFactory(cell -> {
            WatchedFolderConfig cfg = cell.getValue();
            if (cfg == null) {
                return new SimpleStringProperty("");
            }
            if (folderWatcherService != null) {
                return new SimpleStringProperty(folderWatcherService.statusFor(cfg.path()).displayName());
            }
            if (!cfg.enabled() || cfg.mode() == WatchMode.OFF) {
                return new SimpleStringProperty(WatchedFolderStatus.OFF.displayName());
            }
            if (automationPipeline != null && automationPipeline.isPaused()) {
                return new SimpleStringProperty(WatchedFolderStatus.PAUSED.displayName());
            }
            return new SimpleStringProperty(WatchedFolderStatus.WATCHING.displayName());
        });

        watchedFoldersTable.setItems(watchedFolders);
        watchedFoldersTable.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) ->
                removeWatchedFolderButton.setDisable(n == null || n.intValue() < 0));

        activityListView.setItems(activityItems);

        if (themeCombo != null) {
            themeCombo.setItems(FXCollections.observableArrayList(ThemeMode.values()));
        }
        if (appVersionLabel != null) {
            appVersionLabel.setText("TidyFlow " + AppVersion.load());
        }

        if (!autostartService.isAvailable()) {
            startWithWindowsCheckBox.setDisable(true);
            Tooltip tip = new Tooltip(AUTOSTART_UNAVAILABLE_TOOLTIP);
            startWithWindowsCheckBox.setTooltip(tip);
            Tooltip.install(startWithWindowsWrapper, tip);
        }

        if (automationPipeline != null) {
            automationPipeline.addListener(new AutomationListener() {
                @Override
                public void onActivityAdded(ActivityLogEntry entry) {
                    Platform.runLater(() -> refreshActivityList());
                }

                @Override
                public void onAutoBatchCompleted(String batchKind, Path folder, int movedCount, Long batchId) {
                    Platform.runLater(() -> {
                        refreshHistory();
                        refreshDashboard();
                        if (systemTrayManager != null) {
                            systemTrayManager.notifyBatchOrganized(movedCount);
                        }
                    });
                }

                @Override
                public void onFilesReadyForReview(Path folder, int readyCount, boolean triggeredByGuard, int guardThreshold) {
                    Platform.runLater(() -> {
                        if (systemTrayManager != null) {
                            systemTrayManager.notifyFilesReadyForReview(folder, readyCount, triggeredByGuard, guardThreshold);
                        }
                    });
                }

                @Override
                public void onStatusChanged() {
                    Platform.runLater(() -> updateAutomationStatusUi());
                }
            });
        }

        loadInitialSettings();
    }

    private void loadInitialSettings() {
        if (settingsRepository == null) {
            applySettingsToUiAndServices(AppSettings.defaults());
            return;
        }
        try {
            SettingsLoadResult loadResult = settingsRepository.load();
            applySettingsToUiAndServices(loadResult.settings());
            if (loadResult.recoveredFromBackup()) {
                Platform.runLater(() -> {
                    Alert warn = new Alert(Alert.AlertType.WARNING);
                    warn.setTitle("Settings Config Recovered");
                    warn.setHeaderText("Restored Default Settings");
                    warn.setContentText(loadResult.warningMessage());
                    styleAlert(warn);
                    warn.showAndWait();
                });
            }
        } catch (Exception e) {
            applySettingsToUiAndServices(AppSettings.defaults());
        }
    }

    private void applySettingsToUiAndServices(AppSettings settings) {
        this.currentSettings = settings;
        watchedFolders.setAll(settings.watchedFolders());
        settleSecondsField.setText(String.valueOf(settings.settleSeconds()));
        massMoveGuardField.setText(String.valueOf(settings.massMoveGuardThreshold()));
        showNotificationsCheckBox.setSelected(settings.showNotifications());
        minimizeToTrayCheckBox.setSelected(settings.minimizeToTray());
        startWithWindowsCheckBox.setSelected(settings.startWithWindows() && autostartService.isAvailable());
        scheduleEnabledCheckBox.setSelected(settings.schedule().enabled());
        scheduleTimeField.setText(settings.schedule().timeOfDay());
        automationValidationLabel.setText("");

        if (themeCombo != null) {
            updatingThemeCombo = true;
            themeCombo.setValue(settings.theme());
            updatingThemeCombo = false;
        }
        ThemeManager.applyTheme(settings.theme());

        if (automationPipeline != null) {
            automationPipeline.setMassMoveGuardThreshold(settings.massMoveGuardThreshold());
        }
        if (folderWatcherService != null) {
            folderWatcherService.updateConfiguration(
                    settings.watchedFolders(),
                    Duration.ofSeconds(settings.settleSeconds())
            );
        }
        if (dailySchedulerService != null) {
            dailySchedulerService.updateSettings(settings);
        }
        refreshActivityList();
        updateAutomationStatusUi();
    }

    @FXML
    private void onThemeChanged() {
        if (updatingThemeCombo || themeCombo == null) {
            return;
        }
        ThemeMode newTheme = themeCombo.getValue();
        if (newTheme == null || newTheme == currentSettings.theme()) {
            return;
        }
        applyThemeAndPersist(newTheme);
    }

    private void applyThemeAndPersist(ThemeMode newTheme) {
        AppSettings updated = new AppSettings(
                currentSettings.watchedFolders(),
                currentSettings.settleSeconds(),
                currentSettings.massMoveGuardThreshold(),
                currentSettings.showNotifications(),
                currentSettings.minimizeToTray(),
                currentSettings.startWithWindows(),
                currentSettings.schedule(),
                newTheme
        );
        this.currentSettings = updated;
        ThemeManager.applyTheme(newTheme);

        Thread saveWorker = new Thread(() -> {
            try {
                if (settingsRepository != null) {
                    settingsRepository.save(updated);
                }
                Platform.runLater(() -> statusLabel.setText("Theme updated to " + newTheme + "."));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to save theme: " + e.getMessage()));
            }
        }, "tidyflow-theme-save-thread");
        saveWorker.setDaemon(true);
        saveWorker.start();
    }

    @FXML
    private void onTogglePauseWatching() {
        if (automationPipeline == null) {
            return;
        }
        boolean nextPaused = !automationPipeline.isPaused();
        automationPipeline.setPaused(nextPaused);
        if (systemTrayManager != null) {
            systemTrayManager.updatePausedState(nextPaused);
        }
        updateAutomationStatusUi();
    }

    @FXML
    private void onAddWatchedFolder() {
        Path chosen = chooseDirectory(addWatchedFolderButton);
        if (chosen != null) {
            addCandidateWatchedFolder(chosen);
        }
    }

    @FXML
    private void onAddDownloadsFolder() {
        Path downloads = Path.of(System.getProperty("user.home", "."), "Downloads").toAbsolutePath().normalize();
        addCandidateWatchedFolder(downloads);
    }

    private void addCandidateWatchedFolder(Path folder) {
        List<String> errors = settingsValidator.validateNewWatchedFolder(folder, watchedFolders);
        if (!errors.isEmpty()) {
            automationValidationLabel.setText(String.join("; ", errors));
            return;
        }
        List<WatchedFolderConfig> updated = new ArrayList<>(watchedFolders);
        updated.add(WatchedFolderConfig.newDefault(folder.toAbsolutePath().normalize()));
        applyAndSaveWatchedFolders(updated, updated.size() - 1);
    }

    @FXML
    private void onRemoveWatchedFolder() {
        int idx = watchedFoldersTable.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= watchedFolders.size()) {
            return;
        }
        List<WatchedFolderConfig> updated = new ArrayList<>(watchedFolders);
        updated.remove(idx);
        applyAndSaveWatchedFolders(updated, Math.min(idx, updated.size() - 1));
    }

    private void applyAndSaveWatchedFolders(List<WatchedFolderConfig> updatedFolders, int selectIndex) {
        AppSettings candidate = new AppSettings(
                updatedFolders,
                currentSettings.settleSeconds(),
                currentSettings.massMoveGuardThreshold(),
                currentSettings.showNotifications(),
                currentSettings.minimizeToTray(),
                currentSettings.startWithWindows(),
                currentSettings.schedule(),
                currentSettings.theme()
        );
        validateAndPersistSettingsAsync(candidate, selectIndex);
    }

    @FXML
    private void onSaveAutomationSettings() {
        int settleSecs = parseIntOrInvalid(settleSecondsField.getText());
        int guardThreshold = parseIntOrInvalid(massMoveGuardField.getText());
        String scheduleTime = scheduleTimeField.getText() != null ? scheduleTimeField.getText().trim() : "";
        ThemeMode selectedTheme = themeCombo != null && themeCombo.getValue() != null
                ? themeCombo.getValue()
                : currentSettings.theme();

        AppSettings candidate = new AppSettings(
                List.copyOf(watchedFolders),
                settleSecs,
                guardThreshold,
                showNotificationsCheckBox.isSelected(),
                minimizeToTrayCheckBox.isSelected(),
                startWithWindowsCheckBox.isSelected() && autostartService.isAvailable(),
                new ScheduleConfig(scheduleEnabledCheckBox.isSelected(), scheduleTime),
                selectedTheme
        );
        validateAndPersistSettingsAsync(candidate, watchedFoldersTable.getSelectionModel().getSelectedIndex());
    }

    private void validateAndPersistSettingsAsync(AppSettings candidate, int selectFolderIndex) {
        List<String> errors = settingsValidator.validate(candidate);
        if (!errors.isEmpty()) {
            automationValidationLabel.setText(String.join("; ", errors));
            return;
        }

        automationValidationLabel.setText("");
        applySettingsToUiAndServices(candidate);
        if (selectFolderIndex >= 0 && selectFolderIndex < watchedFolders.size()) {
            watchedFoldersTable.getSelectionModel().select(selectFolderIndex);
        }

        Thread saveWorker = new Thread(() -> {
            try {
                if (settingsRepository != null) {
                    settingsRepository.save(candidate);
                }
                if (autostartService.isAvailable()) {
                    autostartService.setEnabled(candidate.startWithWindows());
                }
                Platform.runLater(() -> statusLabel.setText("Automation settings saved."));
            } catch (Exception e) {
                Platform.runLater(() -> automationValidationLabel.setText("Save failed: " + e.getMessage()));
            }
        }, "tidyflow-settings-save-thread");
        saveWorker.setDaemon(true);
        saveWorker.start();
    }

    private void updateAutomationStatusUi() {
        boolean paused = automationPipeline != null && automationPipeline.isPaused();
        pauseResumeButton.setText(paused ? "Resume Watching" : "Pause Watching");

        int watchingCount = 0;
        if (folderWatcherService != null) {
            watchingCount = folderWatcherService.activeWatchingCount();
        } else {
            watchingCount = (int) watchedFolders.stream()
                    .filter(f -> f.enabled() && f.mode() != WatchMode.OFF)
                    .count();
        }

        if (paused) {
            automationStatusLabel.setText("Paused");
        } else if (watchingCount == 1) {
            automationStatusLabel.setText("Watching 1 folder");
        } else {
            automationStatusLabel.setText("Watching " + watchingCount + " folders");
        }
        watchedFoldersTable.refresh();
    }

    private void refreshActivityList() {
        if (automationPipeline == null) {
            return;
        }
        List<String> formatted = automationPipeline.recentActivities().stream()
                .map(entry -> ACTIVITY_TIME_FORMATTER.format(entry.timestamp()) + " " + entry.message())
                .toList();
        activityItems.setAll(formatted);
    }

    public void openOrganizeTab() {
        if (showWindowCallback != null) {
            showWindowCallback.run();
        }
        if (mainTabPane != null && organizeTab != null) {
            mainTabPane.getSelectionModel().select(organizeTab);
        }
    }

    public void openOrganizeTabForFolder(Path folder) {
        openOrganizeTab();
        if (folder != null && Files.isDirectory(folder)) {
            selectedFolder = folder;
            folderPathLabel.setText(selectedFolder.toString());
            scanButton.setDisable(false);
            onScan();
        }
    }

    public void openHistoryTab() {
        if (showWindowCallback != null) {
            showWindowCallback.run();
        }
        if (mainTabPane != null && historyTab != null) {
            mainTabPane.getSelectionModel().select(historyTab);
        }
        refreshHistory();
    }

    public void togglePauseFromTray() {
        onTogglePauseWatching();
    }

    private static int parseIntOrInvalid(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void initHistoryTab() {
        batchDateColumn.setCellValueFactory(cell -> {
            BatchRecord b = cell.getValue();
            String prefix = formatBatchKindPrefix(b.kind());
            return new SimpleStringProperty(prefix + DATE_FORMATTER.format(b.startedAt()));
        });
        batchFilesColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(String.valueOf(cell.getValue().filesCount())));
        batchSizeColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(ByteFormatter.format(cell.getValue().bytesMoved())));
        batchesTable.setItems(batches);

        opFileNameColumn.setCellValueFactory(cell -> {
            Path src = cell.getValue().sourcePath().getFileName();
            return new SimpleStringProperty(src != null ? src.toString() : cell.getValue().sourcePath().toString());
        });
        opRuleColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().ruleName()));
        opFromColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().sourcePath().toString()));
        opToColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().destPath().toString()));
        opStatusColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().status().name()));
        operationsTable.setItems(batchOperations);

        batchesTable.getSelectionModel().selectedItemProperty().addListener((obs, oldBatch, newBatch) -> {
            loadOperationsForBatch(newBatch);
            updateHistoryButtonsAndTooltips();
        });

        operationsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldOp, newOp) ->
                updateHistoryButtonsAndTooltips());

        refreshHistory();
    }

    @FXML
    private void onChooseFolder() {
        Path chosen = chooseDirectory(chooseFolderButton);
        if (chosen != null) {
            selectedFolder = chosen;
            folderPathLabel.setText(selectedFolder.toString());
            scanButton.setDisable(false);
            plannedOperations.clear();
            applyButton.setDisable(true);
            statusLabel.setText("Folder selected. Click Scan to preview.");
        }
    }

    @FXML
    private void onScan() {
        if (selectedFolder == null) {
            return;
        }
        try {
            List<FileItem> scannedFiles = scanner.scan(selectedFolder);
            List<PlannedOperation> plans = ruleEngine.evaluate(scannedFiles);
            plannedOperations.setAll(plans);
            applyButton.setDisable(plans.isEmpty());
            statusLabel.setText(String.format("Scanned %d files, %d matched rules.", scannedFiles.size(), plans.size()));
        } catch (Exception e) {
            showError("Scan failed", e.getMessage());
        }
    }

    @FXML
    private void onApply() {
        if (plannedOperations.isEmpty()) {
            return;
        }

        List<PlannedOperation> snapshot = List.copyOf(plannedOperations);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Organization");
        confirm.setHeaderText("Apply planned file moves?");
        confirm.setContentText("Move " + snapshot.size() + " file(s) to their target folders?");
        styleAlert(confirm);

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        setOrganizeControlsDisabled(true);
        statusLabel.setText("Moving files...");

        Task<BatchExecutionResult> executeTask = new Task<>() {
            @Override
            protected BatchExecutionResult call() {
                return executor.executeBatch(snapshot, "MANUAL");
            }
        };

        executeTask.setOnSucceeded(event -> {
            BatchExecutionResult batchResult = executeTask.getValue();
            String summary = String.format("%d moved, %d failed",
                    batchResult.movedCount(), batchResult.failedCount());
            statusLabel.setText("Completed: " + summary);
            plannedOperations.clear();
            setOrganizeControlsDisabled(false);
            applyButton.setDisable(true);
            refreshHistory();
            refreshDashboard();

            if (!batchResult.databaseSaved()) {
                showError(
                        "Database Save Error",
                        "Files were moved (" + summary + "), but saving history to SQLite failed: "
                                + batchResult.databaseError()
                                + "\nMoved files were recorded in fallback log:\n"
                                + batchResult.fallbackLogPath()
                );
            } else {
                Alert summaryAlert = new Alert(Alert.AlertType.INFORMATION);
                summaryAlert.setTitle("Organization Complete");
                summaryAlert.setHeaderText("Execution Summary");
                summaryAlert.setContentText(summary);
                styleAlert(summaryAlert);
                summaryAlert.showAndWait();
            }
        });

        executeTask.setOnFailed(event -> {
            setOrganizeControlsDisabled(false);
            Throwable ex = executeTask.getException();
            String message = ex != null ? ex.getMessage() : "Unknown error";
            statusLabel.setText("Execution failed: " + message);
            showError("Execution failed", message);
        });

        Thread worker = new Thread(executeTask, "tidyflow-executor-thread");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void onChooseDedupeFolder() {
        Path chosen = chooseDirectory(dedupeChooseFolderButton);
        if (chosen != null) {
            selectedDedupeFolder = chosen;
            dedupeFolderPathLabel.setText(selectedDedupeFolder.toString());
            findDuplicatesButton.setDisable(false);
            statusLabel.setText("Folder selected for duplicate scan.");
        }
    }

    @FXML
    private void onFindDuplicates() {
        if (selectedDedupeFolder == null || duplicateFinder == null) {
            return;
        }

        long minSizeBytes = parseMinSizeBytes();
        boolean recursive = dedupeRecursiveCheckBox.isSelected();
        KeeperStrategy strategy = dedupeStrategyCombo.getValue() != null
                ? dedupeStrategyCombo.getValue()
                : KeeperStrategy.OLDEST_MODIFIED;
        DedupeScanOptions options = new DedupeScanOptions(recursive, minSizeBytes, true, strategy);

        dedupeCancelRequested.set(false);
        setDedupeControlsScanning(true);
        dedupeProgressBar.setVisible(true);
        dedupeProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        dedupeProgressLabel.setText("Starting scan...");

        currentDedupeTask = new Task<>() {
            @Override
            protected DedupeScanResult call() {
                return duplicateFinder.findDuplicates(
                        selectedDedupeFolder,
                        options,
                        this::reportProgress,
                        () -> isCancelled() || dedupeCancelRequested.get()
                );
            }

            private void reportProgress(DedupeProgress progress) {
                Platform.runLater(() -> {
                    if (progress.totalCount() > 0) {
                        dedupeProgressBar.setProgress((double) progress.processedCount() / progress.totalCount());
                        dedupeProgressLabel.setText(String.format(
                                "%s (%d / %d)",
                                progress.stageName(),
                                progress.processedCount(),
                                progress.totalCount()
                        ));
                    } else {
                        dedupeProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                        dedupeProgressLabel.setText(progress.stageName());
                    }
                });
            }
        };

        currentDedupeTask.setOnSucceeded(event -> {
            DedupeScanResult result = currentDedupeTask.getValue();
            setDedupeControlsScanning(false);
            dedupeProgressBar.setVisible(false);
            dedupeProgressLabel.setText("");
            populateDedupeTree(result.groups());
            statusLabel.setText(String.format(
                    "Found %d duplicate group(s) (%d duplicate files, %s wasted).",
                    result.groupCount(),
                    result.duplicateFileCount(),
                    ByteFormatter.format(result.wastedBytes())
            ));
        });

        currentDedupeTask.setOnCancelled(event -> {
            setDedupeControlsScanning(false);
            dedupeProgressBar.setVisible(false);
            dedupeProgressLabel.setText("Scan cancelled");
            statusLabel.setText("Duplicate scan cancelled.");
        });

        currentDedupeTask.setOnFailed(event -> {
            setDedupeControlsScanning(false);
            dedupeProgressBar.setVisible(false);
            Throwable ex = currentDedupeTask.getException();
            if (ex instanceof CancellationException) {
                dedupeProgressLabel.setText("Scan cancelled");
                statusLabel.setText("Duplicate scan cancelled.");
            } else {
                String msg = ex != null ? ex.getMessage() : "Unknown error";
                dedupeProgressLabel.setText("");
                statusLabel.setText("Duplicate scan failed: " + msg);
                showError("Duplicate Scan Failed", msg);
            }
        });

        Thread worker = new Thread(currentDedupeTask, "tidyflow-dedupe-scan-thread");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void onCancelDedupe() {
        dedupeCancelRequested.set(true);
        if (currentDedupeTask != null) {
            currentDedupeTask.cancel(true);
        }
    }

    @FXML
    private void onRemoveSelectedDuplicates() {
        if (duplicateRemover == null) {
            return;
        }
        List<DuplicateGroupSelection> selections = collectDedupeSelections();
        int totalFiles = selections.stream().mapToInt(s -> s.selectedPaths().size()).sum();
        long totalBytes = computeSelectedBytesToFree();
        if (totalFiles == 0) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Move to Recycle Bin");
        confirm.setHeaderText("Move selected duplicate files to the Windows Recycle Bin?");
        confirm.setContentText(String.format(
                "Send %d selected file(s) (%s) to the Recycle Bin?",
                totalFiles,
                ByteFormatter.format(totalBytes)
        ));
        styleAlert(confirm);

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        setDedupeControlsScanning(true);
        cancelDedupeButton.setDisable(true);
        statusLabel.setText("Moving selected duplicates to the Recycle Bin...");

        Task<DedupeRemovalSummary> removeTask = new Task<>() {
            @Override
            protected DedupeRemovalSummary call() throws Exception {
                return duplicateRemover.removeSelected(selections);
            }
        };

        removeTask.setOnSucceeded(event -> {
            setDedupeControlsScanning(false);
            DedupeRemovalSummary summary = removeTask.getValue();
            String formatted = summary.formatSummary();
            statusLabel.setText("Dedupe complete: " + formatted);
            refreshHistory();
            refreshDashboard();

            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Duplicate Removal Complete");
            info.setHeaderText("Recycle Bin Summary");
            String body = formatted;
            if (!summary.details().isEmpty()) {
                body += "\n\nDetails:\n" + String.join("\n", summary.details());
            }
            info.setContentText(body);
            styleAlert(info);
            info.showAndWait();

            onFindDuplicates();
        });

        removeTask.setOnFailed(event -> {
            setDedupeControlsScanning(false);
            Throwable ex = removeTask.getException();
            String msg = ex != null ? ex.getMessage() : "Unknown error";
            statusLabel.setText("Duplicate removal failed: " + msg);
            showError("Duplicate Removal Failed", msg);
        });

        Thread worker = new Thread(removeTask, "tidyflow-dedupe-remove-thread");
        worker.setDaemon(true);
        worker.start();
    }

    private void populateDedupeTree(List<DuplicateGroup> groups) {
        currentDuplicateGroups = List.copyOf(groups);
        TreeItem<DedupeTreeRow> root = new TreeItem<>();
        for (DuplicateGroup group : groups) {
            TreeItem<DedupeTreeRow> groupItem = new TreeItem<>(DedupeTreeRow.forGroup(group));
            groupItem.setExpanded(true);
            for (FileEntry file : group.files()) {
                DedupeTreeRow fileRow = DedupeTreeRow.forFile(group, file);
                groupItem.getChildren().add(new TreeItem<>(fileRow));
            }
            root.getChildren().add(groupItem);
        }
        dedupeTreeTable.setRoot(root);
        dedupeTreeTable.refresh();
        updateDedupeSummary();
    }

    private boolean wouldSelectAllInGroup(TreeItem<DedupeTreeRow> groupItem, DedupeTreeRow targetRow) {
        for (TreeItem<DedupeTreeRow> child : groupItem.getChildren()) {
            DedupeTreeRow childRow = child.getValue();
            if (childRow != targetRow && !childRow.isSelectedForRemoval()) {
                return false;
            }
        }
        return true;
    }

    private void updateDedupeSummary() {
        TreeItem<DedupeTreeRow> root = dedupeTreeTable.getRoot();
        if (root == null) {
            dedupeSummaryLabel.setText("0 groups | 0 files selected | 0 B to free");
            removeDuplicatesButton.setDisable(true);
            return;
        }
        int groupCount = root.getChildren().size();
        int selectedFiles = 0;
        long bytesToFree = 0L;

        for (TreeItem<DedupeTreeRow> groupItem : root.getChildren()) {
            for (TreeItem<DedupeTreeRow> fileItem : groupItem.getChildren()) {
                DedupeTreeRow row = fileItem.getValue();
                if (row != null && !row.isGroupRow() && row.isSelectedForRemoval()) {
                    selectedFiles++;
                    bytesToFree += row.fileEntry().sizeBytes();
                }
            }
        }

        dedupeSummaryLabel.setText(String.format(
                "%d groups | %d files selected | %s to free",
                groupCount,
                selectedFiles,
                ByteFormatter.format(bytesToFree)
        ));
        removeDuplicatesButton.setDisable(selectedFiles == 0);
    }

    private List<DuplicateGroupSelection> collectDedupeSelections() {
        TreeItem<DedupeTreeRow> root = dedupeTreeTable.getRoot();
        if (root == null) {
            return List.of();
        }
        List<DuplicateGroupSelection> selections = new ArrayList<>();
        for (TreeItem<DedupeTreeRow> groupItem : root.getChildren()) {
            DuplicateGroup group = groupItem.getValue().group();
            Set<Path> selected = new LinkedHashSet<>();
            for (TreeItem<DedupeTreeRow> fileItem : groupItem.getChildren()) {
                DedupeTreeRow row = fileItem.getValue();
                if (row != null && !row.isGroupRow() && row.isSelectedForRemoval()) {
                    selected.add(row.fileEntry().path());
                }
            }
            if (!selected.isEmpty()) {
                selections.add(new DuplicateGroupSelection(group, selected));
            }
        }
        return selections;
    }

    private long computeSelectedBytesToFree() {
        TreeItem<DedupeTreeRow> root = dedupeTreeTable.getRoot();
        if (root == null) {
            return 0L;
        }
        long bytes = 0L;
        for (TreeItem<DedupeTreeRow> groupItem : root.getChildren()) {
            for (TreeItem<DedupeTreeRow> fileItem : groupItem.getChildren()) {
                DedupeTreeRow row = fileItem.getValue();
                if (row != null && !row.isGroupRow() && row.isSelectedForRemoval()) {
                    bytes += row.fileEntry().sizeBytes();
                }
            }
        }
        return bytes;
    }

    private long parseMinSizeBytes() {
        long raw = parseLongOrZero(dedupeMinSizeField.getText());
        if (raw <= 0L) {
            return 1L;
        }
        String unit = dedupeSizeUnitCombo.getValue();
        long multiplier = "MB".equalsIgnoreCase(unit) ? 1024L * 1024L : 1024L;
        return Math.max(1L, raw * multiplier);
    }

    private void setDedupeControlsScanning(boolean scanning) {
        dedupeChooseFolderButton.setDisable(scanning);
        dedupeRecursiveCheckBox.setDisable(scanning);
        dedupeMinSizeField.setDisable(scanning);
        dedupeSizeUnitCombo.setDisable(scanning);
        dedupeStrategyCombo.setDisable(scanning);
        findDuplicatesButton.setDisable(scanning || selectedDedupeFolder == null);
        cancelDedupeButton.setDisable(!scanning);
        if (scanning) {
            removeDuplicatesButton.setDisable(true);
        } else {
            updateDedupeSummary();
        }
    }

    @FXML
    private void onHistoryTabSelected() {
        if (batchesTable != null) {
            refreshHistory();
        }
    }

    @FXML
    private void onRefreshHistory() {
        refreshHistory();
    }

    @FXML
    private void onUndoBatch() {
        BatchRecord selectedBatch = batchesTable.getSelectionModel().getSelectedItem();
        if (selectedBatch == null || undoManager == null) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Batch Undo");
        confirm.setHeaderText("Undo entire batch?");
        confirm.setContentText("Restore eligible files from batch #" + selectedBatch.id() + " back to their original locations?");
        styleAlert(confirm);

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        long batchId = selectedBatch.id();
        runUndoTask(() -> undoManager.undoBatch(batchId), batchId);
    }

    @FXML
    private void onUndoSelectedFile() {
        OperationRecord selectedOp = operationsTable.getSelectionModel().getSelectedItem();
        if (selectedOp == null || undoManager == null) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm File Undo");
        confirm.setHeaderText("Undo selected file move?");
        confirm.setContentText("Restore " + selectedOp.destPath().getFileName() + " back to " + selectedOp.sourcePath() + "?");
        styleAlert(confirm);

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        long opId = selectedOp.id();
        long batchId = selectedOp.batchId();
        runUndoTask(() -> undoManager.undoOperation(opId), batchId);
    }

    private void runUndoTask(UndoCallable action, long batchIdToReselect) {
        setHistoryControlsDisabled(true);
        statusLabel.setText("Undoing file operations...");

        Task<UndoSummary> task = new Task<>() {
            @Override
            protected UndoSummary call() throws Exception {
                return action.call();
            }
        };

        task.setOnSucceeded(event -> {
            UndoSummary summary = task.getValue();
            String formatted = summary.formatSummary();
            statusLabel.setText("Undo complete: " + formatted);
            setHistoryControlsDisabled(false);
            refreshHistoryAndReselect(batchIdToReselect);
            refreshDashboard();

            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Undo Complete");
            info.setHeaderText("Undo Summary");
            String body = formatted;
            if (!summary.messages().isEmpty()) {
                body += "\n\nDetails:\n" + String.join("\n", summary.messages());
            }
            info.setContentText(body);
            styleAlert(info);
            info.showAndWait();
        });

        task.setOnFailed(event -> {
            setHistoryControlsDisabled(false);
            Throwable ex = task.getException();
            String message = ex != null ? ex.getMessage() : "Unknown error";
            statusLabel.setText("Undo failed: " + message);
            showError("Undo failed", message);
        });

        Thread worker = new Thread(task, "tidyflow-undo-thread");
        worker.setDaemon(true);
        worker.start();
    }

    private void refreshHistory() {
        if (batchRepository == null) {
            return;
        }
        try {
            BatchRecord previous = batchesTable.getSelectionModel().getSelectedItem();
            List<BatchRecord> latest = batchRepository.findAllNewestFirst();
            batches.setAll(latest);
            if (previous != null) {
                reselectBatch(previous.id());
            } else if (!latest.isEmpty()) {
                batchesTable.getSelectionModel().selectFirst();
            } else {
                batchOperations.clear();
            }
            updateHistoryButtonsAndTooltips();
        } catch (Exception e) {
            statusLabel.setText("Failed to load history: " + e.getMessage());
        }
    }

    private void refreshHistoryAndReselect(long batchId) {
        if (batchRepository == null) {
            return;
        }
        try {
            batches.setAll(batchRepository.findAllNewestFirst());
            reselectBatch(batchId);
            updateHistoryButtonsAndTooltips();
        } catch (Exception e) {
            statusLabel.setText("Failed to refresh history: " + e.getMessage());
        }
    }

    private void reselectBatch(long batchId) {
        for (BatchRecord b : batches) {
            if (b.id() == batchId) {
                batchesTable.getSelectionModel().select(b);
                loadOperationsForBatch(b);
                return;
            }
        }
    }

    private void loadOperationsForBatch(BatchRecord batch) {
        if (batch == null || operationRepository == null) {
            batchOperations.clear();
            return;
        }
        try {
            batchOperations.setAll(operationRepository.findByBatchId(batch.id()));
        } catch (Exception e) {
            showError("Failed to load operations", e.getMessage());
        }
    }

    private void updateHistoryButtonsAndTooltips() {
        BatchRecord selectedBatch = batchesTable.getSelectionModel().getSelectedItem();
        boolean batchHasOnlyTrash = !batchOperations.isEmpty()
                && batchOperations.stream().allMatch(op -> op.actionType() == OperationActionType.TRASH);
        boolean batchCanUndo = selectedBatch != null && !batchHasOnlyTrash
                && batchOperations.stream().anyMatch(op -> op.actionType() == OperationActionType.MOVE
                && op.status() == OperationStatus.SUCCESS);

        undoBatchButton.setDisable(!batchCanUndo);
        if (selectedBatch != null && batchHasOnlyTrash) {
            Tooltip batchTooltip = new Tooltip(RECYCLE_BIN_TOOLTIP);
            undoBatchButton.setTooltip(batchTooltip);
            Tooltip.install(undoBatchWrapper, batchTooltip);
        } else {
            undoBatchButton.setTooltip(null);
            Tooltip.uninstall(undoBatchWrapper, undoBatchButton.getTooltip());
        }

        OperationRecord selectedOp = operationsTable.getSelectionModel().getSelectedItem();
        boolean isTrashOp = selectedOp != null && selectedOp.actionType() == OperationActionType.TRASH;
        boolean fileCanUndo = selectedOp != null && !isTrashOp && selectedOp.status() == OperationStatus.SUCCESS;

        undoFileButton.setDisable(!fileCanUndo);
        if (isTrashOp) {
            Tooltip fileTooltip = new Tooltip(RECYCLE_BIN_TOOLTIP);
            undoFileButton.setTooltip(fileTooltip);
            Tooltip.install(undoFileWrapper, fileTooltip);
        } else {
            undoFileButton.setTooltip(null);
            Tooltip.uninstall(undoFileWrapper, undoFileButton.getTooltip());
        }
    }

    private Path chooseDirectory(Button sourceButton) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Folder");

        Path defaultDownloads = Path.of(System.getProperty("user.home", "."), "Downloads");
        if (Files.isDirectory(defaultDownloads)) {
            chooser.setInitialDirectory(defaultDownloads.toFile());
        }

        Window window = sourceButton.getScene().getWindow();
        File chosen = chooser.showDialog(window);
        return chosen != null ? chosen.toPath() : null;
    }

    private void setOrganizeControlsDisabled(boolean disabled) {
        chooseFolderButton.setDisable(disabled);
        scanButton.setDisable(disabled || selectedFolder == null);
        applyButton.setDisable(disabled || plannedOperations.isEmpty());
    }

    private void setHistoryControlsDisabled(boolean disabled) {
        if (disabled) {
            undoBatchButton.setDisable(true);
            undoFileButton.setDisable(true);
            refreshHistoryButton.setDisable(true);
        } else {
            refreshHistoryButton.setDisable(false);
            updateHistoryButtonsAndTooltips();
        }
    }

    private void styleAlert(Alert alert) {
        if (alert != null) {
            ThemeManager.styleDialog(alert);
        }
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        styleAlert(alert);
        alert.showAndWait();
    }

    public static String formatBatchKindPrefix(String kind) {
        if (kind == null) {
            return "[Organize] ";
        }
        return switch (kind.toUpperCase(Locale.ROOT)) {
            case "AUTO" -> "[Auto] ";
            case "SCHEDULED" -> "[Scheduled] ";
            case "DEDUPE" -> "[Dedupe] ";
            default -> "[Organize] ";
        };
    }

    @FunctionalInterface
    private interface UndoCallable {
        UndoSummary call() throws Exception;
    }
}
