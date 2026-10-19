package com.tidyflow.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AppVersionTest {

    @Test
    void readsVersionFromPropertiesStream() {
        ByteArrayInputStream in = new ByteArrayInputStream("version=1.0.0\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("1.0.0", AppVersion.loadFromStream(in));
    }

    @Test
    void fallsBackToDevWhenResourceMissingOrUnresolved() {
        assertEquals("dev", AppVersion.loadFromResource("/non-existent-version.properties"));

        ByteArrayInputStream unresolved = new ByteArrayInputStream("version=${version}\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("dev", AppVersion.loadFromStream(unresolved));

        ByteArrayInputStream empty = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
        assertEquals("dev", AppVersion.loadFromStream(empty));
    }

    @Test
    void loadsRealClasspathVersionResource() {
        String version = AppVersion.load();
        assertFalse(version.isBlank());
    }
}
