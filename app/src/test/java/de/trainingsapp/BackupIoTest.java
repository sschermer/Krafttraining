package de.trainingsapp;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class BackupIoTest {
    @Test public void exactByteLimitPreservesUnicode() throws Exception {
        byte[] text = "{\"name\":\"Übung\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("{\"name\":\"Übung\"}", BackupIo.readUtf8(new ByteArrayInputStream(text), text.length));
    }

    @Test public void excessiveBytesAreRejectedBeforeParsing() {
        assertThrows(IOException.class, () -> BackupIo.readUtf8(new ByteArrayInputStream(new byte[8193]), 8192));
    }

    @Test public void malformedUtf8IsRejected() {
        assertThrows(IOException.class, () -> BackupIo.readUtf8(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28}), 20));
    }

    @Test public void bomFromExternalEditorIsAccepted() throws Exception {
        assertEquals("{}", BackupIo.readUtf8(new ByteArrayInputStream("\uFEFF{}".getBytes(StandardCharsets.UTF_8)), 20));
    }

    @Test public void cancelledReadDoesNotContinue() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> BackupIo.readUtf8(new ByteArrayInputStream(new byte[5]), 20));
        } finally { Thread.interrupted(); }
    }
}
