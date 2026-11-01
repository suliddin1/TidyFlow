package com.tidyflow.ui;

import com.tidyflow.core.dedupe.FolderExclusionFilter;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dialog for viewing and editing excluded folder names and full directory paths
 * skipped during duplicate scanning.
 */
public final class ExclusionsDialog {

    private final FolderExclusionFilter initialFilter;

    public ExclusionsDialog(FolderExclusionFilter initialFilter) {
        this.initialFilter = initialFilter != null ? initialFilter : FolderExclusionFilter.defaults();
    }

    public Optional<FolderExclusionFilter> showAndWait(Window owner) {
        Dialog<FolderExclusionFilter> dialog = new Dialog<>();
        dialog.setTitle("Duplicate Scan Exclusions");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        ButtonType saveButtonType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        ObservableList<String> items = FXCollections.observableArrayList();
        items.addAll(initialFilter.folderNames());
        items.addAll(initialFilter.fullPaths());

        ListView<String> listView = new ListView<>(items);
        listView.setPrefHeight(240);

        TextField inputField = new TextField();
        inputField.setPromptText("Folder name (e.g. target) or full path (e.g. D:\\Backups)");
        HBox.setHgrow(inputField, Priority.ALWAYS);

        Button addNameButton = new Button("Add");
        addNameButton.getStyleClass().add("button-secondary");
        addNameButton.setOnAction(e -> {
            String value = inputField.getText();
            if (value != null && !value.isBlank()) {
                String trimmed = value.trim();
                boolean exists = items.stream().anyMatch(existing -> existing.equalsIgnoreCase(trimmed));
                if (!exists) {
                    items.add(trimmed);
                }
                inputField.clear();
            }
        });

        Button browseFolderButton = new Button("Add folder...");
        browseFolderButton.getStyleClass().add("button-secondary");
        browseFolderButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Folder to Exclude");
            File chosen = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (chosen != null) {
                String path = chosen.toPath().toAbsolutePath().normalize().toString();
                boolean exists = items.stream().anyMatch(existing -> existing.equalsIgnoreCase(path));
                if (!exists) {
                    items.add(path);
                }
            }
        });

        Button removeButton = new Button("Remove selected");
        removeButton.getStyleClass().add("button-danger");
        removeButton.disableProperty().bind(listView.getSelectionModel().selectedItemProperty().isNull());
        removeButton.setOnAction(e -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                items.remove(selected);
            }
        });

        Button resetButton = new Button("Reset to defaults");
        resetButton.getStyleClass().add("button-secondary");
        resetButton.setOnAction(e -> {
            items.setAll(FolderExclusionFilter.DEFAULT_EXCLUDED_NAMES);
        });

        HBox addRow = new HBox(8, inputField, addNameButton, browseFolderButton);
        addRow.setAlignment(Pos.CENTER_LEFT);

        HBox actionsRow = new HBox(8, removeButton, resetButton);
        actionsRow.setAlignment(Pos.CENTER_LEFT);

        Label subtitle = new Label(
                "Folders matching these names (case-insensitive) or full paths are never entered during a duplicate scan."
        );
        subtitle.setWrapText(true);
        subtitle.getStyleClass().add("muted-text");

        VBox content = new VBox(10, subtitle, listView, addRow, actionsRow);
        content.setPadding(new Insets(12));
        content.setPrefWidth(540);

        dialog.getDialogPane().setContent(content);
        if (owner != null && owner.getScene() != null) {
            dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
            dialog.getDialogPane().getStyleClass().setAll(owner.getScene().getRoot().getStyleClass());
        }

        dialog.setResultConverter(button -> {
            if (button == saveButtonType) {
                List<String> names = new ArrayList<>();
                List<String> paths = new ArrayList<>();
                for (String item : items) {
                    if (item.indexOf('\\') >= 0 || item.indexOf('/') >= 0 || item.indexOf(':') >= 0) {
                        paths.add(item);
                    } else {
                        names.add(item);
                    }
                }
                return new FolderExclusionFilter(names, paths);
            }
            return null;
        });

        return dialog.showAndWait();
    }
}
