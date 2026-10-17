package com.tidyflow.core.dedupe;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Production TrashService implementation backed by java.awt.Desktop#moveToTrash.
 */
public class DesktopTrashService implements TrashService {

    @Override
    public void moveToTrash(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            throw new IOException("Moving files to the Windows Recycle Bin is not supported on this system");
        }
        boolean moved = Desktop.getDesktop().moveToTrash(path.toFile());
        if (!moved) {
            throw new IOException("Windows Recycle Bin rejected file: " + path);
        }
    }
}
