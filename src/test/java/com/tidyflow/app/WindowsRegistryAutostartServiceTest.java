package com.tidyflow.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsRegistryAutostartServiceTest {

    @Test
    void buildEnableCommandQuotesPathWithSpacesCorrectly() {
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
                "\"C:\\Program Files\\TidyFlow App\\TidyFlow.exe\"",
                "/f"
        ), cmd);
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
