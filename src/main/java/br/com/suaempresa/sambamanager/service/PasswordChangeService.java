package br.com.suaempresa.sambamanager.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Uses Windows' trusted HTTPS stack; passwords are passed only through the child process environment. */
public class PasswordChangeService {
    public void change(String endpoint, String username, char[] currentPassword, char[] newPassword) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            throw new IllegalStateException("A alteração de senha está disponível somente no Windows.");
        }
        try {
            endpoint = plainUrl(endpoint);
            AppLog.info("Solicitação de alteração de senha iniciada para o usuário " + username + ".");
            InternalCertificateInstaller.ensureInstalled();
            AppLog.info("Preparando conexão HTTPS com o serviço de senha.");
            String script = "$body=@{username=$env:SAMBA_MANAGER_USER;currentPassword=$env:SAMBA_MANAGER_CURRENT;newPassword=$env:SAMBA_MANAGER_NEW}|ConvertTo-Json -Compress; "
                    + "$response=Invoke-WebRequest -UseBasicParsing -TimeoutSec 30 -Uri $env:SAMBA_MANAGER_PASSWORD_API -Method POST -ContentType 'application/json' -Body $body -ErrorAction Stop; "
                    + "if($response.StatusCode -ne 200){throw 'O serviço recusou a alteração de senha'}; 'SAMBA_MANAGER_PASSWORD_CHANGED'; exit 0";
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder processBuilder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
            processBuilder.environment().put("SAMBA_MANAGER_PASSWORD_API", endpoint);
            processBuilder.environment().put("SAMBA_MANAGER_USER", username);
            processBuilder.environment().put("SAMBA_MANAGER_CURRENT", new String(currentPassword));
            processBuilder.environment().put("SAMBA_MANAGER_NEW", new String(newPassword));
            processBuilder.redirectErrorStream(true);
            AppLog.info("Enviando solicitação HTTPS de alteração de senha.");
            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            AppLog.info("PowerShell de alteração de senha finalizado com código " + exitCode + ".");
            if (exitCode != 0 || !output.contains("SAMBA_MANAGER_PASSWORD_CHANGED")) {
                AppLog.info("Resposta de erro do serviço de senha: " + output.replaceAll("[\\r\\n]+", " | "));
                String normalized = output.toLowerCase();
                if (normalized.contains("ssl/tls") || normalized.contains("rela") && normalized.contains("confian")) {
                    throw new IllegalStateException("O certificado de segurança do servidor não é confiável neste computador. Peça ao TI para instalar o certificado interno.");
                }
                throw new IllegalStateException("Não foi possível alterar a senha. Confira a senha atual e tente novamente.");
            }
        } finally {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }

    private String plainUrl(String value) {
        int marker = value.indexOf("](");
        return value.startsWith("[") && marker > 0 && value.endsWith(")")
                ? value.substring(marker + 2, value.length() - 1)
                : value;
    }
}
