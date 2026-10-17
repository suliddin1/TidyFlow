package com.tidyflow.core.rules;

import com.tidyflow.core.scanner.FileItem;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Action that moves a matching file into a target directory.
 * If the target directory is relative, it is resolved against the file's parent folder.
 */
public final class MoveAction implements Action {

    private final String rawTargetFolder;
    private final Path targetFolder;

    public MoveAction(Path targetFolder) {
        this.targetFolder = Objects.requireNonNull(targetFolder, "targetFolder must not be null");
        this.rawTargetFolder = targetFolder.toString();
    }

    public MoveAction(String targetFolderName) {
        this.rawTargetFolder = Objects.requireNonNull(targetFolderName, "targetFolderName must not be null");
        Path parsed = null;
        if (!targetFolderName.isBlank()) {
            try {
                parsed = Path.of(targetFolderName.trim());
            } catch (InvalidPathException ignored) {
                parsed = null;
            }
        }
        this.targetFolder = parsed;
    }

    public String rawTargetFolder() {
        return rawTargetFolder;
    }

    public Path targetFolder() {
        return targetFolder;
    }

    @Override
    public Path resolveDestination(FileItem file) {
        Objects.requireNonNull(file, "file must not be null");
        if (targetFolder == null) {
            throw new IllegalStateException("MoveAction has an invalid targetFolder: " + rawTargetFolder);
        }
        Path baseDir = targetFolder;
        if (!targetFolder.isAbsolute() && file.path().getParent() != null) {
            baseDir = file.path().getParent().resolve(targetFolder);
        }
        return baseDir.resolve(file.name());
    }
}
