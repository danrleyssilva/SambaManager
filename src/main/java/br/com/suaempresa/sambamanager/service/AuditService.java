package br.com.suaempresa.sambamanager.service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Envia ao servidor somente metadados de uma autenticação Samba já validada. */
public class AuditService {
    public void recordConnection(String endpoint, String username, String computerName,
            String appVersion, int accessibleCount) throws Exception {
        String body = "{\"username\":\"" + json(username)
                + "\",\"computerName\":\"" + json(computerName)
                + "\",\"appVersion\":\"" + json(appVersion)
                + "\",\"accessibleCount\":" + accessibleCount + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = InternalHttpsClient.create().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("O servidor recusou o registro de auditoria: HTTP "
                    + response.statusCode());
        }
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
