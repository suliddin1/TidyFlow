package com.tidyflow.core.scanner;

import com.tidyflow.util.FileNameUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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
            Iterator<Path> iterator = entries.iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                String fileName = entry.getFileName().toString();
                if (fileName.startsWith(".")) {
                    continue;
                }
                String extension = FileNameUtils.getExtension(fileName);
                if (isInProgressExtension(extension)) {
                    continue;
                }

                BasicFileAttributes attrs;
                boolean hidden;
                try {
                    DosFileAttributes dosAttrs = Files.readAttributes(entry, DosFileAttributes.class);
                    attrs = dosAttrs;
                    hidden = dosAttrs.isHidden();
                } catch (UnsupportedOperationException e) {
                    attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    hidden = Files.isHidden(entry);
                }

                if (!attrs.isRegularFile() || hidden) {
                    continue;
                }

                items.add(new FileItem(
                        entry,
                        fileName,
                        extension,
                        attrs.size(),
                        attrs.lastModifiedTime().toInstant()
                ));
            }
        }

        items.sort(Comparator.comparing(FileItem::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(items);
    }

    public static boolean isInProgressExtension(String extension) {
        return extension != null && IN_PROGRESS_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    public static boolean isInProgressFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        return isInProgressExtension(FileNameUtils.getExtension(fileName));
    }
}
