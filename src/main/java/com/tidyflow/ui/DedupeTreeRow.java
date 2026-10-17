package com.tidyflow.ui;

import com.tidyflow.core.dedupe.DuplicateGroup;
import com.tidyflow.core.dedupe.FileEntry;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * Row model for the Duplicates TreeTableView (represents either a group header row or a child file row).
 */
public final class DedupeTreeRow {

    private final DuplicateGroup group;
    private final FileEntry fileEntry;
    private final BooleanProperty selectedForRemoval = new SimpleBooleanProperty(false);

    public static DedupeTreeRow forGroup(DuplicateGroup group) {
        return new DedupeTreeRow(group, null, false);
    }

    public static DedupeTreeRow forFile(DuplicateGroup parentGroup, FileEntry fileEntry) {
        return new DedupeTreeRow(parentGroup, fileEntry, !fileEntry.suggestedKeeper());
    }

    private DedupeTreeRow(DuplicateGroup group, FileEntry fileEntry, boolean removeByDefault) {
        this.group = group;
        this.fileEntry = fileEntry;
        this.selectedForRemoval.set(removeByDefault);
    }

    public boolean isGroupRow() {
        return fileEntry == null;
    }

    public DuplicateGroup group() {
        return group;
    }

    public FileEntry fileEntry() {
        return fileEntry;
    }

    public boolean isSelectedForRemoval() {
        return selectedForRemoval.get();
    }

    public void setSelectedForRemoval(boolean selected) {
        selectedForRemoval.set(selected);
    }

    public BooleanProperty selectedForRemovalProperty() {
        return selectedForRemoval;
    }
}
