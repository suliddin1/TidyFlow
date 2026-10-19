package com.tidyflow.ui;

import com.tidyflow.core.rules.Condition;
import com.tidyflow.core.rules.ExtensionCondition;
import com.tidyflow.core.rules.MinSizeCondition;
import com.tidyflow.core.rules.MoveAction;
import com.tidyflow.core.rules.OlderThanDaysCondition;
import com.tidyflow.core.rules.RegexFilenameCondition;
import com.tidyflow.core.rules.Rule;
import com.tidyflow.core.rules.RuleValidator;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Modal dialog for creating or editing a Rule with live RuleValidator feedback.
 */
public final class RuleEditorDialog {

    private enum ConditionKind {
        EXTENSION("Extension in list"),
        REGEX("Filename regex"),
        MIN_SIZE("Size greater than"),
        OLDER_DAYS("Older than N days");

        private final String label;

        ConditionKind(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Rule existingRule;
    private final int assignedPriority;
    private final List<Rule> otherRules;
    private final RuleValidator validator;

    private final TextField nameField = new TextField();
    private final CheckBox enabledCheckBox = new CheckBox("Enabled");
    private final VBox conditionsContainer = new VBox(8);
    private final List<ConditionRow> conditionRows = new ArrayList<>();
    private final TextField targetFolderField = new TextField();
    private final Label errorLabel = new Label();
    private Node saveButtonNode;

    public RuleEditorDialog(Rule existingRule, int assignedPriority, List<Rule> otherRules, RuleValidator validator) {
        this.existingRule = existingRule;
        this.assignedPriority = assignedPriority;
        this.otherRules = otherRules;
        this.validator = validator;
    }

    public Optional<Rule> showAndWait(Window owner) {
        Dialog<Rule> dialog = new Dialog<>();
        dialog.setTitle(existingRule == null ? "Add Rule" : "Edit Rule");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        ButtonType saveButtonType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        saveButtonNode = dialog.getDialogPane().lookupButton(saveButtonType);

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));
        content.setPrefWidth(560);

        GridPane headerGrid = new GridPane();
        headerGrid.setHgap(10);
        headerGrid.setVgap(8);
        headerGrid.add(new Label("Rule Name:"), 0, 0);
        headerGrid.add(nameField, 1, 0);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        headerGrid.add(enabledCheckBox, 2, 0);

        // Conditions header + Add button
        ComboBox<ConditionKind> addTypeCombo = new ComboBox<>(
                FXCollections.observableArrayList(ConditionKind.values()));
        addTypeCombo.getSelectionModel().select(ConditionKind.EXTENSION);
        Button addCondButton = new Button("Add Condition");
        addCondButton.setOnAction(e -> {
            ConditionKind kind = addTypeCombo.getValue();
            if (kind != null) {
                addConditionRow(new ConditionRow(kind, null));
                revalidate();
            }
        });
        HBox condHeader = new HBox(8, new Label("Conditions (all must match):"), addTypeCombo, addCondButton);
        condHeader.setAlignment(Pos.CENTER_LEFT);

        ScrollPane condScroll = new ScrollPane(conditionsContainer);
        condScroll.setFitToWidth(true);
        condScroll.setPrefHeight(170);
        conditionsContainer.setPadding(new Insets(6));

        // Action target folder row
        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Target Folder");
            File chosen = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (chosen != null) {
                targetFolderField.setText(chosen.getAbsolutePath());
            }
        });
        HBox targetRow = new HBox(8, new Label("Move to folder:"), targetFolderField, browseButton);
        targetRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(targetFolderField, Priority.ALWAYS);

        errorLabel.getStyleClass().add("validation-error");
        errorLabel.setWrapText(true);

        content.getChildren().addAll(headerGrid, condHeader, condScroll, targetRow, errorLabel);
        dialog.getDialogPane().setContent(content);
        ThemeManager.styleDialog(dialog);

        // Populate initial values
        if (existingRule != null) {
            nameField.setText(existingRule.name());
            enabledCheckBox.setSelected(existingRule.enabled());
            for (Condition c : existingRule.conditions()) {
                addConditionRow(createConditionRowFromExisting(c));
            }
            if (existingRule.action() instanceof MoveAction move) {
                targetFolderField.setText(move.rawTargetFolder());
            }
        } else {
            enabledCheckBox.setSelected(true);
            addConditionRow(new ConditionRow(ConditionKind.EXTENSION, null));
        }

        nameField.textProperty().addListener((obs, o, n) -> revalidate());
        enabledCheckBox.selectedProperty().addListener((obs, o, n) -> revalidate());
        targetFolderField.textProperty().addListener((obs, o, n) -> revalidate());

        revalidate();

        dialog.setResultConverter(btn -> {
            if (btn == saveButtonType) {
                return buildCandidateRule();
            }
            return null;
        });

        return dialog.showAndWait();
    }

    private void addConditionRow(ConditionRow row) {
        conditionRows.add(row);
        conditionsContainer.getChildren().add(row.rootNode);
    }

    private void removeConditionRow(ConditionRow row) {
        conditionRows.remove(row);
        conditionsContainer.getChildren().remove(row.rootNode);
        revalidate();
    }

    private void revalidate() {
        Rule candidate = buildCandidateRule();
        List<String> errors = new ArrayList<>();
        for (ConditionRow row : conditionRows) {
            if (row.parseError != null) {
                errors.add(row.parseError);
            }
        }
        errors.addAll(validator.validateRule(candidate, otherRules));

        if (errors.isEmpty()) {
            errorLabel.setText("");
            saveButtonNode.setDisable(false);
        } else {
            errorLabel.setText(String.join("\n", errors));
            saveButtonNode.setDisable(true);
        }
    }

    private Rule buildCandidateRule() {
        List<Condition> conditions = new ArrayList<>();
        for (ConditionRow row : conditionRows) {
            conditions.add(row.toCondition());
        }
        return new Rule(
                nameField.getText() != null ? nameField.getText().trim() : "",
                enabledCheckBox.isSelected(),
                assignedPriority,
                conditions,
                new MoveAction(targetFolderField.getText() != null ? targetFolderField.getText().trim() : "")
        );
    }

    private final class ConditionRow {
        private final ConditionKind kind;
        private final HBox rootNode = new HBox(8);
        private final TextField primaryField = new TextField();
        private final ComboBox<String> unitCombo = new ComboBox<>(
                FXCollections.observableArrayList("KB", "MB", "GB"));
        private String parseError;

        private ConditionRow(ConditionKind kind, Condition initialCondition) {
            this.kind = kind;
            rootNode.setAlignment(Pos.CENTER_LEFT);
            Label typeLabel = new Label(kind.label + ":");
            typeLabel.setMinWidth(135);
            HBox.setHgrow(primaryField, Priority.ALWAYS);

            Button removeBtn = new Button("Remove");
            removeBtn.setOnAction(e -> removeConditionRow(this));

            switch (kind) {
                case EXTENSION -> {
                    primaryField.setPromptText("e.g. jpg, png, gif");
                    if (initialCondition instanceof ExtensionCondition ext) {
                        primaryField.setText(String.join(", ", ext.extensionList()));
                    }
                    rootNode.getChildren().addAll(typeLabel, primaryField, removeBtn);
                }
                case REGEX -> {
                    primaryField.setPromptText("e.g. ^invoice_.*\\.pdf$");
                    if (initialCondition instanceof RegexFilenameCondition rx) {
                        primaryField.setText(rx.regex());
                    }
                    rootNode.getChildren().addAll(typeLabel, primaryField, removeBtn);
                }
                case MIN_SIZE -> {
                    primaryField.setPromptText("e.g. 10");
                    unitCombo.getSelectionModel().select("MB");
                    if (initialCondition instanceof MinSizeCondition sz) {
                        populateSizeField(sz.minBytes());
                    }
                    unitCombo.valueProperty().addListener((obs, o, n) -> revalidate());
                    rootNode.getChildren().addAll(typeLabel, primaryField, unitCombo, removeBtn);
                }
                case OLDER_DAYS -> {
                    primaryField.setPromptText("e.g. 30");
                    if (initialCondition instanceof OlderThanDaysCondition od) {
                        primaryField.setText(String.valueOf(od.days()));
                    }
                    rootNode.getChildren().addAll(typeLabel, primaryField, new Label("days"), removeBtn);
                }
            }

            primaryField.textProperty().addListener((obs, o, n) -> revalidate());
        }

        private void populateSizeField(long bytes) {
            long gb = 1024L * 1024L * 1024L;
            long mb = 1024L * 1024L;
            long kb = 1024L;
            if (bytes >= gb && bytes % gb == 0) {
                unitCombo.getSelectionModel().select("GB");
                primaryField.setText(String.valueOf(bytes / gb));
            } else if (bytes >= mb && bytes % mb == 0) {
                unitCombo.getSelectionModel().select("MB");
                primaryField.setText(String.valueOf(bytes / mb));
            } else {
                unitCombo.getSelectionModel().select("KB");
                primaryField.setText(String.valueOf(Math.max(1L, bytes / kb)));
            }
        }

        private Condition toCondition() {
            parseError = null;
            String text = primaryField.getText() != null ? primaryField.getText().trim() : "";
            return switch (kind) {
                case EXTENSION -> {
                    if (text.isEmpty()) {
                        yield new ExtensionCondition(List.of());
                    }
                    List<String> tokens = Arrays.stream(text.split(","))
                            .map(String::trim)
                            .toList();
                    yield new ExtensionCondition(tokens);
                }
                case REGEX -> new RegexFilenameCondition(text);
                case MIN_SIZE -> {
                    try {
                        long val = Long.parseLong(text);
                        long mult = switch (unitCombo.getValue()) {
                            case "GB" -> 1024L * 1024L * 1024L;
                            case "MB" -> 1024L * 1024L;
                            default -> 1024L;
                        };
                        yield new MinSizeCondition(val * mult);
                    } catch (NumberFormatException e) {
                        parseError = "Minimum size must be a valid integer";
                        yield new MinSizeCondition(0L);
                    }
                }
                case OLDER_DAYS -> {
                    try {
                        long days = Long.parseLong(text);
                        yield new OlderThanDaysCondition(days);
                    } catch (NumberFormatException e) {
                        parseError = "Days threshold must be a valid integer";
                        yield new OlderThanDaysCondition(0L);
                    }
                }
            };
        }
    }

    private ConditionRow createConditionRowFromExisting(Condition c) {
        if (c instanceof ExtensionCondition) {
            return new ConditionRow(ConditionKind.EXTENSION, c);
        } else if (c instanceof RegexFilenameCondition) {
            return new ConditionRow(ConditionKind.REGEX, c);
        } else if (c instanceof MinSizeCondition) {
            return new ConditionRow(ConditionKind.MIN_SIZE, c);
        } else if (c instanceof OlderThanDaysCondition) {
            return new ConditionRow(ConditionKind.OLDER_DAYS, c);
        }
        return new ConditionRow(ConditionKind.EXTENSION, null);
    }
}
