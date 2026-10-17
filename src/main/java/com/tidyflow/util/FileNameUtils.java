package com.tidyflow.util;

import java.util.Locale;

/**
 * Helper methods for extracting file base names and extensions.
 */
public final class FileNameUtils {

    private FileNameUtils() {
    }

    /**
     * Extracts the lowercase extension without the leading dot, or empty string if none exists.
     */
    public static String getExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Extracts the file name without its extension.
     */
    public static String getBaseName(String fileName) {
        if (fileName == null) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == fileName.length() - 1) {
            return fileName;
        }
        return fileName.substring(0, lastDot);
    }

    /**
     * Extracts the original extension including the leading dot (preserving case), or empty string if none.
     */
    public static String getRawExtensionWithDot(String fileName) {
        if (fileName == null) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot);
    }
}
