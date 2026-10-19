package com.tidyflow.core.watcher;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Default NIO implementation of FileStatProvider that checks file attributes and verifies
 * the file can be opened for reading without sharing violations.
 */
public class NioFileStatProvider implements FileStatProvider {

    @Override
    public FileStat stat(Path path) {
        if (path == null || !Files.exists(path)) {
            return FileStat.missing();
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) {
                return new FileStat(true, false, false, attrs.size(), attrs.lastModifiedTime().toInstant(), false);
            }
            boolean hidden = path.getFileName().toString().startsWith(".") || Files.isHidden(path);
            boolean readable = canOpenForReading(path);
            return new FileStat(
                    true,
                    true,
                    hidden,
                    attrs.size(),
                    attrs.lastModifiedTime().toInstant(),
                    readable
            );
        } catch (IOException e) {
            return FileStat.missing();
        }
    }

    private boolean canOpenForReading(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return channel.isOpen();
        } catch (IOException e) {
            return false;
        }
    }
}
