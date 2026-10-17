package com.tidyflow.core.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FolderScannerTest {

    private final FolderScanner scanner = new FolderScanner();

    @Test
    void scansRegularFilesAndSkipsDirectoriesHiddenAndInProgressDownloads(@TempDir Path tempDir) throws Exception {
        // Eligible files
        Files.writeString(tempDir.resolve("document.pdf"), "12345");
        Files.writeString(tempDir.resolve("archive.zip"), "123");

        // Subdirectory and nested file (must be skipped since scanner is non-recursive)
        Path subDir = Files.createDirectories(tempDir.resolve("Subfolder"));
        Files.writeString(subDir.resolve("nested.jpg"), "nested");

        // Hidden files (dotfile + DOS hidden attribute)
        Files.writeString(tempDir.resolve(".dotfile"), "hidden");
        Path dosHidden = Files.writeString(tempDir.resolve("secret.txt"), "secret");
        try {
            Files.setAttribute(dosHidden, "dos:hidden", true);
        } catch (UnsupportedOperationException ignored) {
        }

        // In-progress downloads
        Files.writeString(tempDir.resolve("video.mp4.crdownload"), "partial");
        Files.writeString(tempDir.resolve("ubuntu.iso.part"), "partial");
        Files.writeString(tempDir.resolve("temp_download.tmp"), "partial");

        List<FileItem> scanned = scanner.scan(tempDir);

        assertEquals(2, scanned.size());
        assertEquals("archive.zip", scanned.get(0).name());
        assertEquals("zip", scanned.get(0).extension());
        assertEquals(3L, scanned.get(0).size());

        assertEquals("document.pdf", scanned.get(1).name());
        assertEquals("pdf", scanned.get(1).extension());
        assertEquals(5L, scanned.get(1).size());
    }
}
