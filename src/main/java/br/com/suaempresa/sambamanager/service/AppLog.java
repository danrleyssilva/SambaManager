package br.com.suaempresa.sambamanager.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Runtime log. Never send passwords or credentials to this class. */
public final class AppLog {
    private static final Path FILE = Path.of("logs", "samba-manager.log").toAbsolutePath();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AppLog() { }

    public static synchronized void info(String message) {
        write("INFO", message);
    }

    public static synchronized void error(String message, Throwable exception) {
        write("ERROR", message + " | " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
    }

    public static Path file() {
        return FILE;
    }

    private static void write(String level, String message) {
        try {
            Files.createDirectories(FILE.getParent());
            String line = "%s [%s] %s%n".formatted(LocalDateTime.now().format(TIME), level, message);
            Files.writeString(FILE, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging must never prevent the application from running.
        }
    }
}
