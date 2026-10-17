package com.tidyflow.core.scanner;

import com.tidyflow.util.FileNameUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Scans a directory and returns eligible files for organization.
 * Non-recursive by default; skips directories, hidden files, and partial downloads.
 */
public class FolderScanner {

    private static final Set<String> IN_PROGRESS_EXTENSIONS = Set.of("crdownload", "part", "tmp");

    public List<FileItem> scan(Path folderPath) throws IOException {
        if (folderPath == null || !Files.isDirectory(folderPath)) {
            throw new IllegalArgumentException("Provided path must be an existing directory: " + folderPath);
        }

        List<FileItem> items = new ArrayList<>();
        try (Stream<Path> entries = Files.list(folderPath)) {
            for (Path entry : entries.toList()) {
                if (shouldInclude(entry)) {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    String fileName = entry.getFileName().toString();
                    String extension = FileNameUtils.getExtension(fileName);
                    items.add(new FileItem(
                            entry,
                            fileName,
                            extension,
                            attrs.size(),
                            attrs.lastModifiedTime().toInstant()
                    ));
                }
            }
        }

        items.sort(Comparator.comparing(FileItem::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(items);
    }

    private boolean shouldInclude(Path entry) throws IOException {
        if (!Files.isRegularFile(entry)) {
            return false;
        }
        String fileName = entry.getFileName().toString();
        if (fileName.startsWith(".") || Files.isHidden(entry)) {
            return false;
        }
        String extension = FileNameUtils.getExtension(fileName);
        return !IN_PROGRESS_EXTENSIONS.contains(extension);
    }
}
