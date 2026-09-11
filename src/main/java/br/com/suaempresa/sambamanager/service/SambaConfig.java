package br.com.suaempresa.sambamanager.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

public record SambaConfig(String server, String passwordApiUrl, String updateApiUrl,
        String auditApiUrl, List<String> shares) {
    public static SambaConfig load() {
        Properties properties = new Properties();
        try (InputStream input = SambaConfig.class.getResourceAsStream("/samba.properties")) {
            if (input == null) throw new IllegalStateException("Arquivo samba.properties não encontrado.");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível carregar a configuração.", exception);
        }
        List<String> shares = List.of(properties.getProperty("shares", "").split("\\|"));
        return new SambaConfig(properties.getProperty("server"), plainUrl(properties.getProperty("password.api.url")),
                plainUrl(properties.getProperty("update.api.url")),
                plainUrl(properties.getProperty("audit.api.url")), shares);
    }

    private static String plainUrl(String value) {
        if (value == null) return null;
        int linkStart = value.indexOf("](");
        if (value.startsWith("[") && linkStart > 0 && value.endsWith(")")) {
            return value.substring(linkStart + 2, value.length() - 1);
        }
        return value;
    }
}
