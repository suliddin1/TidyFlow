package com.tidyflow.util;

import com.tidyflow.data.config.ThemeMode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Windows app theme preference (AppsUseLightTheme) from the registry
 * and parses the reg.exe output with a safe fallback to LIGHT.
 */
public final class WindowsThemeDetector {

    public static final String PERSONALIZE_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    public static final String VALUE_NAME = "AppsUseLightTheme";

    private static final Pattern APPS_USE_LIGHT_THEME_PATTERN = Pattern.compile(
            "(?i)\\bAppsUseLightTheme\\s+REG_DWORD\\s+(0x[0-9a-f]+|\\d+)\\b"
    );

    private WindowsThemeDetector() {
    }

    /**
     * Pure parser for {@code reg query} output.
     * Returns DARK when AppsUseLightTheme is 0 (0x0), and LIGHT for 1 (0x1) or any missing/invalid output.
     */
    public static ThemeMode parseRegQueryOutput(String regOutput) {
        if (regOutput == null || regOutput.isBlank()) {
            return ThemeMode.LIGHT;
        }
        Matcher matcher = APPS_USE_LIGHT_THEME_PATTERN.matcher(regOutput);
        if (!matcher.find()) {
            return ThemeMode.LIGHT;
        }
        String rawValue = matcher.group(1).trim();
        try {
            int numeric = rawValue.startsWith("0x") || rawValue.startsWith("0X")
                    ? Integer.parseInt(rawValue.substring(2), 16)
                    : Integer.parseInt(rawValue);
            return numeric == 0 ? ThemeMode.DARK : ThemeMode.LIGHT;
        } catch (NumberFormatException e) {
            return ThemeMode.LIGHT;
        }
    }

    /**
     * Queries Windows registry for the current user's app theme; falls back to LIGHT if anything fails.
     */
    public static ThemeMode detectSystemTheme() {
        try {
            Process process = new ProcessBuilder(
                    List.of("reg.exe", "query", PERSONALIZE_KEY, "/v", VALUE_NAME)
            ).redirectErrorStream(true).start();

            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ThemeMode.LIGHT;
            }
            if (process.exitValue() != 0) {
                return ThemeMode.LIGHT;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parseRegQueryOutput(output);
        } catch (Exception e) {
            return ThemeMode.LIGHT;
        }
    }
}
