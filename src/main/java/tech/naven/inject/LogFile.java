package tech.naven.inject;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Mirrors {@code System.out}/{@code System.err} into {@code %TEMP%\naven-java.log}.
 *
 * <p>The injected client runs under a launcher that starts {@code java.exe} without a
 * visible console, and NeoForge does not route {@code System.out} through log4j, so
 * bootstrap diagnostics would otherwise be lost. log4j output keeps going to the game
 * log; everything printed by the agent/engine/patches lands here.</p>
 */
public final class LogFile {
    private static volatile boolean installed;

    private LogFile() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            Path path = Path.of(System.getProperty("java.io.tmpdir"), "naven-java.log");
            OutputStream file = new FileOutputStream(path.toFile(), false);
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            System.setOut(new PrintStream(new Tee(originalOut, file), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new Tee(originalErr, file), true, StandardCharsets.UTF_8));
            System.out.println("[naven] mirroring stdout/stderr to " + path);
        } catch (IOException e) {
            System.err.println("[naven] could not open naven-java.log: " + e);
        }
    }

    private static final class Tee extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

        private Tee(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void write(int b) throws IOException {
            this.first.write(b);
            this.second.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            this.first.write(b, off, len);
            this.second.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            this.first.flush();
            this.second.flush();
        }
    }
}
