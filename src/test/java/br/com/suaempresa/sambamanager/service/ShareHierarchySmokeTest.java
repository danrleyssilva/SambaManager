package br.com.suaempresa.sambamanager.service;

import java.util.List;

public final class ShareHierarchySmokeTest {
    public static void main(String[] args) {
        List<String> shares = List.of("1 - Contabilidade", "1 - Contabilidade - Controles",
                "1 - Contabilidade - Controles - 2026", "2 - Conta Sucata", "StorageRoot");
        var children = ShareHierarchy.childrenOf(shares);
        if (!children.get("1 - Contabilidade").equals(List.of("1 - Contabilidade - Controles"))) {
            throw new AssertionError("Subshare not grouped under its parent");
        }
        if (!children.get("1 - Contabilidade - Controles").equals(
                List.of("1 - Contabilidade - Controles - 2026"))) {
            throw new AssertionError("Nested share not grouped under the closest parent");
        }
        if (ShareHierarchy.parentOf("2 - Conta Sucata", shares) != null) {
            throw new AssertionError("Unrelated share was grouped");
        }
        if (!ShareHierarchy.childLabel("1 - Contabilidade - Controles", "1 - Contabilidade")
                .equals("Controles")) {
            throw new AssertionError("Child label should omit the repeated parent name");
        }
    }
}
