package com.tidyflow.ui;

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
import com.tidyflow.data.BatchRecord;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.OperationActionType;
import com.tidyflow.data.OperationRecord;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.OperationStatus;
import com.tidyflow.data.config.RuleLoadResult;
import com.tidyflow.data.config.RuleRepository;
import com.tidyflow.util.FileNameUtils;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller for the main TidyFlow window (Organize, Duplicates, Rules, and History tabs).
 */
public class MainController {

    private static final String RECYCLE_BIN_TOOLTIP = "Restore from the Windows Recycle Bin";
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

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
    private final RuleValidator ruleValidator = new RuleValidator();

    private Path selectedFolder;
    private Path selectedDedupeFolder;
    private List<DuplicateGroup> currentDuplicateGroups = List.of();
    private Task<DedupeScanResult> currentDedupeTask;
    private final AtomicBoolean dedupeCancelRequested = new AtomicBoolean(false);

    private final ObservableList<PlannedOperation> plannedOperations = FXCollections.observableArrayList();
    private final ObservableList<Rule> currentRules = FXCollections.observableArrayList();
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
        this.scanner = scanner;
        this.ruleEngine = ruleEngine;
        this.executor = executor;
        this.batchRepository = batchRepository;
        this.operationRepository = operationRepository;
        this.undoManager = undoManager;
        this.duplicateFinder = duplicateFinder;
        this.duplicateRemover = duplicateRemover;
        this.ruleRepository = ruleRepository;
    }

    @FXML
    public void initialize() {
        initOrganizeTab();
        initDuplicatesTab();
        initRulesTab();
        initHistoryTab();
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
                        formatBytes(row.group().sizeBytes())
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
                return new SimpleStringProperty(formatBytes(row.group().wastedBytes()) + " wasted");
            }
            return new SimpleStringProperty(formatBytes(row.fileEntry().sizeBytes()));
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

    private void initHistoryTab() {
        batchDateColumn.setCellValueFactory(cell -> {
            BatchRecord b = cell.getValue();
            String prefix = "DEDUPE".equalsIgnoreCase(b.kind()) ? "[Dedupe] " : "[Organize] ";
            return new SimpleStringProperty(prefix + DATE_FORMATTER.format(b.startedAt()));
        });
        batchFilesColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(String.valueOf(cell.getValue().filesCount())));
        batchSizeColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(formatBytes(cell.getValue().bytesMoved())));
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
                    formatBytes(result.wastedBytes())
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
                formatBytes(totalBytes)
        ));

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

            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Duplicate Removal Complete");
            info.setHeaderText("Recycle Bin Summary");
            String body = formatted;
            if (!summary.details().isEmpty()) {
                body += "\n\nDetails:\n" + String.join("\n", summary.details());
            }
            info.setContentText(body);
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
                formatBytes(bytesToFree)
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

            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Undo Complete");
            info.setHeaderText("Undo Summary");
            String body = formatted;
            if (!summary.messages().isEmpty()) {
                body += "\n\nDetails:\n" + String.join("\n", summary.messages());
            }
            info.setContentText(body);
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

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    @FunctionalInterface
    private interface UndoCallable {
        UndoSummary call() throws Exception;
    }
}
