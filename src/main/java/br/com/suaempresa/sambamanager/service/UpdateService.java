package br.com.suaempresa.sambamanager.service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateService {
    public UpdateInfo check(String endpoint) throws Exception {
        AppLog.info("Consultando atualizações em " + endpoint + ".");
        HttpRequest request = HttpRequest.newBuilder(URI.create(plainUrl(endpoint)))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = InternalHttpsClient.create().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        AppLog.info("Consulta de atualização respondeu HTTP " + response.statusCode() + ".");
        if (response.statusCode() != 200) {
            throw new IllegalStateException("O servidor de atualizações respondeu HTTP " + response.statusCode() + ".");
        }
        String body = response.body();
        return new UpdateInfo(stringValue(body, "version"), plainUrl(stringValue(body, "downloadUrl")),
                stringValue(body, "sha256"), booleanValue(body, "required"), stringValue(body, "notes"));
    }

    public Path download(UpdateInfo update) throws Exception {
        String expectedHash = update.sha256().trim().toLowerCase();
        if (!expectedHash.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("O servidor ainda não possui um SHA-256 válido para esta atualização.");
        }
        URI uri = URI.create(plainUrl(update.downloadUrl()));
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("A atualização foi recusada porque o download não usa HTTPS.");
        }
        String safeVersion = update.version().replaceAll("[^0-9A-Za-z._-]", "-");
        Path directory = Files.createTempDirectory("royal-server-access-update-");
        Path installer = directory.resolve("RoyalServerAccess-" + safeVersion + ".exe");
        AppLog.info("Baixando atualização " + update.version() + ".");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/vnd.microsoft.portable-executable")
                .GET()
                .build();
        HttpResponse<Path> response = InternalHttpsClient.create().send(request,
                HttpResponse.BodyHandlers.ofFile(installer));
        AppLog.info("Download da atualização respondeu HTTP " + response.statusCode() + ".");
        if (response.statusCode() != 200) {
            Files.deleteIfExists(installer);
            throw new IllegalStateException("Não foi possível baixar a atualização. HTTP " + response.statusCode() + ".");
        }
        String downloadedHash = sha256(installer);
        if (!MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII),
                downloadedHash.getBytes(StandardCharsets.US_ASCII))) {
            Files.deleteIfExists(installer);
            throw new IllegalStateException("A atualização baixada não passou na verificação de segurança SHA-256.");
        }
        AppLog.info("SHA-256 da atualização " + update.version() + " validado com sucesso.");
        return installer;
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String stringValue(String json, String key) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key)
                + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("Manifesto de atualização inválido: campo " + key + " ausente.");
        }
        return matcher.group(1)
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private boolean booleanValue(String json, String key) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(true|false)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("Manifesto de atualização inválido: campo " + key + " ausente.");
        }
        return Boolean.parseBoolean(matcher.group(1));
    }

    private String plainUrl(String value) {
        int marker = value.indexOf("](");
        return value.startsWith("[") && marker > 0 && value.endsWith(")")
                ? value.substring(marker + 2, value.length() - 1)
                : value;
    }
}
