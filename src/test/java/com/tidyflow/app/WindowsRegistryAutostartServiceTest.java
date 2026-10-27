package com.tidyflow.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsRegistryAutostartServiceTest {

    @Test
    void buildEnableCommandQuotesPathWithSpacesAndAppendsBackgroundFlag() {
        String exePath = "C:\\Program Files\\TidyFlow App\\TidyFlow.exe";
        List<String> cmd = WindowsRegistryAutostartService.buildEnableCommand(exePath);

        assertEquals(List.of(
                "reg.exe",
                "add",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v",
                "TidyFlow",
                "/t",
                "REG_SZ",
                "/d",
                "\"C:\\Program Files\\TidyFlow App\\TidyFlow.exe\" --background",
                "/f"
        ), cmd);
    }

    @Test
    void formatRegistryValueDataPreservesAlreadyQuotedPathsAndEndsWithBackground() {
        String alreadyQuoted = "\"C:\\Users\\Alice Smith\\AppData\\Local\\Programs\\TidyFlow\\TidyFlow.exe\"";
        String value = WindowsRegistryAutostartService.formatRegistryValueData(alreadyQuoted);

        assertEquals("\"C:\\Users\\Alice Smith\\AppData\\Local\\Programs\\TidyFlow\\TidyFlow.exe\" --background", value);
        assertTrue(value.endsWith(" --background"));
    }

    @Test
    void toWindowsProcessCommandEscapesQuotesForRegExe() {
        List<String> logical = WindowsRegistryAutostartService.buildEnableCommand(
                "C:\\Program Files\\TidyFlow App\\TidyFlow.exe"
        );
        List<String> encoded = WindowsRegistryAutostartService.toWindowsProcessCommand(logical);

        assertEquals("\\\"C:\\Program Files\\TidyFlow App\\TidyFlow.exe\\\" --background", encoded.get(8));
        assertTrue(encoded.get(8).endsWith(" --background"));
    }

    @Test
    void buildEnableCommandRejectsBlankPath() {
        assertThrows(IllegalArgumentException.class, () -> WindowsRegistryAutostartService.buildEnableCommand("   "));
    }

    @Test
    void buildDisableCommandTargetsCurrentUserRunKey() {
        List<String> cmd = WindowsRegistryAutostartService.buildDisableCommand();

        assertEquals(List.of(
                "reg.exe",
                "delete",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v",
                "TidyFlow",
                "/f"
        ), cmd);
    }

    @Test
    void isAvailableOnlyWhenJpackagePropertyPresent() {
        WindowsRegistryAutostartService devMode = new WindowsRegistryAutostartService(() -> null);
        assertFalse(devMode.isAvailable());

        WindowsRegistryAutostartService packagedMode =
                new WindowsRegistryAutostartService(() -> "C:\\Program Files\\TidyFlow\\TidyFlow.exe");
        assertTrue(packagedMode.isAvailable());
    }
}
