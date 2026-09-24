package br.com.suaempresa.sambamanager.service;

import java.util.List;

/** Run directly with java; no test framework is needed for this parser check. */
public final class ShareCatalogServiceSmokeTest {
    public static void main(String[] args) {
        List<String> shares = ShareCatalogService.parseShares(
                "{\"shares\":[\"17 - Manutenção\",\"26 - Projetos\",\"26 - Projetos\"]}");
        if (!shares.equals(List.of("17 - Manutenção", "26 - Projetos"))) {
            throw new AssertionError("A ordem e os nomes retornados pelo Samba devem ser preservados.");
        }
        try {
            ShareCatalogService.parseShares("{\"shares\":[\"pasta|inválida\"]}");
            throw new AssertionError("Nomes incompatíveis com o transporte devem ser recusados.");
        } catch (IllegalArgumentException expected) {
            // Invalid names must never become UNC paths.
        }
    }
}
