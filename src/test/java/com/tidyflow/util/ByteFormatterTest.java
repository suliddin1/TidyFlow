package com.tidyflow.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ByteFormatterTest {

    @Test
    void formatsZeroAndSmallBytes() {
        assertEquals("0 B", ByteFormatter.format(0));
        assertEquals("0 B", ByteFormatter.format(-50));
        assertEquals("512 B", ByteFormatter.format(512));
        assertEquals("1023 B", ByteFormatter.format(1023));
    }

    @Test
    void formatsKilobytesWithOneDecimal() {
        assertEquals("1.0 KB", ByteFormatter.format(1024));
        assertEquals("1.5 KB", ByteFormatter.format(1536));
        assertEquals("1023.9 KB", ByteFormatter.format(1024 * 1024 - 103));
    }

    @Test
    void formatsMegabytesGigabytesAndTerabytes() {
        long oneMb = 1024L * 1024L;
        long oneGb = oneMb * 1024L;
        long oneTb = oneGb * 1024L;

        assertEquals("1.0 MB", ByteFormatter.format(oneMb));
        assertEquals("2.5 MB", ByteFormatter.format((long) (2.5 * oneMb)));
        assertEquals("1.0 GB", ByteFormatter.format(oneGb));
        assertEquals("3.3 GB", ByteFormatter.format((long) (3.3 * oneGb)));
        assertEquals("1.0 TB", ByteFormatter.format(oneTb));
        assertEquals("4.8 TB", ByteFormatter.format((long) (4.8 * oneTb)));
    }
}
