package br.com.suaempresa.sambamanager.service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/** Direct HTTPS client that trusts only the bundled internal CA. */
public class PasswordChangeService {
    public void change(String endpoint, String username, char[] currentPassword, char[] newPassword) throws Exception {
        try {
            endpoint = plainUrl(endpoint);
            AppLog.info("Solicitação de alteração de senha iniciada para o usuário " + username + ".");
            String body = "{\"username\":\"" + json(username) + "\",\"currentPassword\":\""
                    + json(new String(currentPassword)) + "\",\"newPassword\":\"" + json(new String(newPassword)) + "\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            AppLog.info("Enviando solicitação HTTPS de alteração de senha.");
            HttpResponse<String> response = InternalHttpsClient.create().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            AppLog.info("Serviço de senha respondeu HTTP " + response.statusCode() + ".");
            if (response.statusCode() != 200) throw new IllegalStateException(message(response.body()));
        } finally {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }

    private String plainUrl(String value) {
        int marker = value.indexOf("](");
        return value.startsWith("[") && marker > 0 && value.endsWith(")") ? value.substring(marker + 2, value.length() - 1) : value;
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String message(String body) {
        int start = body.indexOf("\"message\":\"");
        if (start < 0) return "Não foi possível alterar a senha. Tente novamente.";
        start += "\"message\":\"".length();
        int end = body.indexOf('"', start);
        return end < 0 ? "Não foi possível alterar a senha. Tente novamente." : body.substring(start, end);
    }
}
