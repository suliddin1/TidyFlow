package com.tidyflow.util;

import com.tidyflow.data.config.ThemeMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsThemeDetectorTest {

    @Test
    void parsesDarkThemeWhenAppsUseLightThemeIsZero() {
        String output = """
                
                HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize
                    AppsUseLightTheme    REG_DWORD    0x0
                """;
        assertEquals(ThemeMode.DARK, WindowsThemeDetector.parseRegQueryOutput(output));
    }

    @Test
    void parsesLightThemeWhenAppsUseLightThemeIsOne() {
        String output = """
                
                HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize
                    AppsUseLightTheme    REG_DWORD    0x1
                """;
        assertEquals(ThemeMode.LIGHT, WindowsThemeDetector.parseRegQueryOutput(output));
    }

    @Test
    void fallsBackToLightThemeOnMissingOrMalformedOutput() {
        assertEquals(ThemeMode.LIGHT, WindowsThemeDetector.parseRegQueryOutput(null));
        assertEquals(ThemeMode.LIGHT, WindowsThemeDetector.parseRegQueryOutput(""));
        assertEquals(ThemeMode.LIGHT, WindowsThemeDetector.parseRegQueryOutput("ERROR: The system was unable to find the specified registry key or value."));
        assertEquals(ThemeMode.LIGHT, WindowsThemeDetector.parseRegQueryOutput("AppsUseLightTheme REG_DWORD unexpected"));
    }
}
