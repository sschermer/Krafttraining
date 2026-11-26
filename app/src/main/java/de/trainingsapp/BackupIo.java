package de.trainingsapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bound bytes before parsing, including streams whose provider reports no size. */
public final class BackupIo {
    private BackupIo() {}

    public static String readUtf8(InputStream input) throws IOException {
        return readUtf8(input, BackupValidation.MAX_BACKUP_BYTES);
    }

    static String readUtf8(InputStream input, int maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("Invalid size limit");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Import abgebrochen.");
            int count = input.read(buffer);
            if (count == -1) break;
            if (count == 0) continue;
            if (count > maxBytes - bytes.size())
                throw new IOException("Die Sicherung ist zu groß (maximal 32 MiB).");
            bytes.write(buffer, 0, count);
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return value.startsWith("\uFEFF") ? value.substring(1) : value;
        } catch (CharacterCodingException error) {
            throw new IOException("Die Sicherung enthält kein gültiges UTF-8.", error);
        }
    }
}
