package br.com.suaempresa.sambamanager.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Turns PowerShell's per-share output into a safe, useful diagnosis. */
final class AccessCheckDiagnostics {
    private AccessCheckDiagnostics() { }

    static String summary(String output) {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        int accessible = 0;
        for (String line : output.lines().map(String::trim).toList()) {
            if (line.startsWith("SAMBA_MANAGER_ACCESS:")) {
                accessible++;
            } else if (line.startsWith("SAMBA_MANAGER_ERROR:")) {
                int separator = line.indexOf(" | ", "SAMBA_MANAGER_ERROR:".length());
                String reason = separator < 0 ? "erro sem descrição" : line.substring(separator + 3).trim();
                reasons.merge(classify(reason), 1, Integer::sum);
            }
        }
        return "acessíveis=" + accessible + ", falhas=" + reasons;
    }

    static String diagnosis(String output, String server) {
        String normalized = output.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("multiple connections to a server or shared resource")
                || normalized.contains("using more than one user name")
                || normalized.contains("múltiplas conexões")
                || normalized.contains("multiplas conexoes")
                || (normalized.contains("conex") && normalized.contains("servidor")
                    && normalized.contains("nome de usu"))) {
            return "O Windows já está conectado a " + server + " com outro usuário. "
                    + "Não é possível verificar esta conta na mesma sessão. "
                    + "Salve e feche os arquivos abertos; depois use 'Limpar mapeamentos' "
                    + "e tente novamente, ou teste em outro perfil do Windows.";
        }
        if (normalized.contains("logon failure") || normalized.contains("password is not correct")
                || normalized.contains("username or password") || normalized.contains("nome de usuário ou senha")
                || normalized.contains("senha de rede")) {
            return "Usuário ou senha inválidos. Confira os dados e tente novamente.";
        }
        return null;
    }

    private static String classify(String reason) {
        String diagnosis = diagnosis(reason, "servidor");
        if (diagnosis != null) return diagnosis.startsWith("O Windows") ? "conflito de credenciais Windows" : "autenticação recusada";
        String normalized = reason.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("network name") || normalized.contains("nome da rede")) return "compartilhamento não encontrado";
        if (normalized.contains("access is denied") || normalized.contains("acesso negado")) return "acesso negado";
        return reason.length() > 160 ? reason.substring(0, 160) + "…" : reason;
    }
}
