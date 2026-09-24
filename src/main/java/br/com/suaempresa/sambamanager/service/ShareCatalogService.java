package br.com.suaempresa.sambamanager.service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Reads the current share names from the internal Samba API. */
public final class ShareCatalogService {
    public List<String> fetch(String endpoint) throws Exception {
        URI uri = URI.create(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("A lista de pastas deve usar HTTPS.");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = InternalHttpsClient.create().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("O catálogo de pastas respondeu HTTP " + response.statusCode() + ".");
        }
        List<String> shares = parseShares(response.body());
        AppLog.info("Catálogo do servidor carregado: " + shares.size() + " compartilhamento(s).");
        return shares;
    }

    static List<String> parseShares(String body) {
        int key = body.indexOf("\"shares\"");
        int colon = key < 0 ? -1 : body.indexOf(':', key + 8);
        int index = colon < 0 ? -1 : skipWhitespace(body, colon + 1);
        if (index < 0 || index >= body.length() || body.charAt(index) != '[') {
            throw new IllegalArgumentException("Catálogo de pastas inválido.");
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        index++;
        while (true) {
            index = skipWhitespace(body, index);
            if (index >= body.length()) {
                throw new IllegalArgumentException("Catálogo de pastas incompleto.");
            }
            if (body.charAt(index) == ']') {
                break;
            }
            if (body.charAt(index) != '"') {
                throw new IllegalArgumentException("Nome de pasta inválido no catálogo.");
            }
            StringBuilder name = new StringBuilder();
            index++;
            boolean closed = false;
            while (index < body.length()) {
                char character = body.charAt(index++);
                if (character == '"') {
                    closed = true;
                    break;
                }
                if (character == '\\') {
                    if (index >= body.length()) throw new IllegalArgumentException("Escape incompleto no catálogo.");
                    char escaped = body.charAt(index++);
                    character = switch (escaped) {
                        case '"', '\\', '/' -> escaped;
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> {
                            if (index + 4 > body.length()) throw new IllegalArgumentException("Unicode inválido.");
                            try {
                                char decoded = (char) Integer.parseInt(body.substring(index, index + 4), 16);
                                index += 4;
                                yield decoded;
                            } catch (NumberFormatException error) {
                                throw new IllegalArgumentException("Unicode inválido.", error);
                            }
                        }
                        default -> throw new IllegalArgumentException("Escape inválido no catálogo.");
                    };
                }
                name.append(character);
            }
            if (!closed || !validShare(name.toString())) {
                throw new IllegalArgumentException("Nome de pasta inválido no catálogo.");
            }
            names.add(name.toString());
            if (names.size() > 200) throw new IllegalArgumentException("Catálogo de pastas muito grande.");
            index = skipWhitespace(body, index);
            if (index < body.length() && body.charAt(index) == ',') {
                index++;
                if (skipWhitespace(body, index) < body.length()
                        && body.charAt(skipWhitespace(body, index)) == ']') {
                    throw new IllegalArgumentException("Separador inválido no catálogo.");
                }
            } else if (index < body.length() && body.charAt(index) == ']') {
                break;
            } else {
                throw new IllegalArgumentException("Separador inválido no catálogo.");
            }
        }
        if (names.isEmpty()) throw new IllegalArgumentException("O catálogo de pastas está vazio.");
        return List.copyOf(new ArrayList<>(names));
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return index;
    }

    private static boolean validShare(String name) {
        if (name.isBlank() || name.length() > 80) return false;
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character == '|' || character == '/' || character == '\\' || Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }
}
