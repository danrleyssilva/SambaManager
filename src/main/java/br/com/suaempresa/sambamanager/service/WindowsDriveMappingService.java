package br.com.suaempresa.sambamanager.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Uses Windows PowerShell so persisted SMB drives are created by Windows itself. */
public class WindowsDriveMappingService {
    public List<String> map(String server, String username, char[] password, List<String> shares) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            throw new IllegalStateException("O mapeamento de unidades está disponível somente no Windows.");
        }
        String script = "$server=$env:SAMBA_MANAGER_SERVER; $user=$env:SAMBA_MANAGER_USER; "
                + "$items=$env:SAMBA_MANAGER_SHARES -split '\\|'; $start='F'; "
                + "$secure=ConvertTo-SecureString $env:SAMBA_MANAGER_PASSWORD -AsPlainText -Force; "
                + "$credential=[pscredential]::new($user,$secure); $letter=[int][char]$start; "
                + "$result=@(); foreach($share in $items){ $name=[char]$letter; "
                + "New-PSDrive -Name $name -PSProvider FileSystem -Root ('\\\\'+$server+'\\'+$share) "
                + "-Credential $credential -Persist -ErrorAction Stop | Out-Null; $result += ($name+':'); $letter++ }; $result";
        AppLog.info("Iniciando mapeamento de " + shares.size() + " compartilhamento(s) para o usuário " + username + ".");
        String output = runPowerShell(script, server, username, password, shares);
        AppLog.info("Resposta do PowerShell no mapeamento: " + output.replaceAll("[\\r\\n]+", " | "));
        List<String> drives = new ArrayList<>();
        for (String line : output.split("\\R")) if (!line.isBlank()) drives.add(line.trim());
        return drives;
    }

    /** Removes only mappings for this Samba server, then recreates the selected mappings. */
    public List<String> refreshMappings(String server, String username, char[] password, List<String> shares) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            throw new IllegalStateException("O mapeamento de unidades está disponível somente no Windows.");
        }
        String script = "$server=$env:SAMBA_MANAGER_SERVER; $user=$env:SAMBA_MANAGER_USER; "
                + "$items=$env:SAMBA_MANAGER_SHARES -split '\\|'; $start='F'; "
                + "$secure=ConvertTo-SecureString $env:SAMBA_MANAGER_PASSWORD -AsPlainText -Force; "
                + "$credential=[pscredential]::new($user,$secure); "
                + "New-PSDrive -Name 'SambaRefreshCheck' -PSProvider FileSystem -Root ('\\\\'+$server+'\\'+$items[0]) -Credential $credential -ErrorAction Stop | Out-Null; "
                + "Remove-PSDrive -Name 'SambaRefreshCheck' -ErrorAction SilentlyContinue; "
                + "Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Root -like ('\\\\'+$server+'\\*') } | "
                + "ForEach-Object { Remove-PSDrive -Name $_.Name -Force -ErrorAction Stop }; "
                + "$letter=[int][char]$start; $result=@(); foreach($share in $items){ $name=[char]$letter; "
                + "New-PSDrive -Name $name -PSProvider FileSystem -Root ('\\\\'+$server+'\\'+$share) "
                + "-Credential $credential -Persist -ErrorAction Stop | Out-Null; $result += ($name+':'); $letter++ }; $result";
        AppLog.info("Atualizando mapeamentos do servidor " + server + ": removendo os anteriores e recriando " + shares.size() + " pasta(s).");
        String output = runPowerShell(script, server, username, password, shares);
        AppLog.info("Resposta do PowerShell na atualização: " + output.replaceAll("[\\r\\n]+", " | "));
        List<String> drives = new ArrayList<>();
        for (String line : output.split("\\R")) if (!line.isBlank()) drives.add(line.trim());
        return drives;
    }

    /** Tests every share using the supplied Samba account, without creating persistent drives. */
    public List<String> checkAccessibleShares(String server, String username, char[] password, List<String> shares) throws Exception {
        AppLog.info("Iniciando verificação de " + shares.size() + " compartilhamento(s) para o usuário " + username + " no servidor " + server + ".");
        String script = "$server=$env:SAMBA_MANAGER_SERVER; $user=$env:SAMBA_MANAGER_USER; "
                + "$items=$env:SAMBA_MANAGER_SHARES -split '\\|'; "
                + "$secure=ConvertTo-SecureString $env:SAMBA_MANAGER_PASSWORD -AsPlainText -Force; "
                + "$credential=[pscredential]::new($user,$secure); $index=0; "
                + "foreach($share in $items){ $name='SambaCheck'+$index; try { "
                + "New-PSDrive -Name $name -PSProvider FileSystem -Root ('\\\\'+$server+'\\'+$share) -Credential $credential -ErrorAction Stop | Out-Null; "
                + "Write-Output ('SAMBA_MANAGER_ACCESS:' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($share))) "
                + "} catch { Write-Output ('SAMBA_MANAGER_ERROR:' + $share + ' | ' + $_.Exception.Message) } finally { Remove-PSDrive -Name $name -ErrorAction SilentlyContinue }; $index++ }";
        String output = runPowerShell(script, server, username, password, shares);
        AppLog.info("Resposta do PowerShell na verificação: " + output.replaceAll("[\\r\\n]+", " | "));
        String normalized = output.toLowerCase();
        if (normalized.contains("conex") && normalized.contains("servidor") && normalized.contains("nome de usu")) {
            throw new IllegalStateException("Já existe uma conexão com " + server
                    + " usando outras credenciais. Feche as conexões antigas desse servidor e tente novamente.");
        }
        if (normalized.contains("logon failure") || normalized.contains("password is not correct")
                || normalized.contains("username or password") || normalized.contains("nome de usuário ou senha")
                || normalized.contains("senha de rede")) {
            throw new IllegalStateException("Usuário ou senha inválidos. Confira os dados e tente novamente.");
        }
        if (!normalized.contains("samba_manager_access:") && normalized.contains("samba_manager_error:")) {
            throw new IllegalStateException("Não foi possível verificar as pastas no servidor. Consulte o log para mais detalhes.");
        }
        return output.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("SAMBA_MANAGER_ACCESS:"))
                .map(line -> line.substring("SAMBA_MANAGER_ACCESS:".length()))
                .map(encodedShare -> new String(Base64.getDecoder().decode(encodedShare), StandardCharsets.UTF_8))
                .toList();
    }

    private String runPowerShell(String script, String server, String username, char[] password, List<String> shares) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            throw new IllegalStateException("O acesso ao Samba está disponível somente no Windows.");
        }
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder processBuilder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
        processBuilder.environment().put("SAMBA_MANAGER_SERVER", server);
        processBuilder.environment().put("SAMBA_MANAGER_USER", username);
        processBuilder.environment().put("SAMBA_MANAGER_SHARES", String.join("|", shares));
        processBuilder.environment().put("SAMBA_MANAGER_PASSWORD", new String(password));
        java.util.Arrays.fill(password, '\0');
        AppLog.info("PowerShell iniciado para " + shares.size() + " compartilhamento(s). Senha não registrada.");
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        AppLog.info("PowerShell finalizado com código " + exitCode + ".");
        if (exitCode != 0) throw new IllegalStateException(output.isBlank() ? "Falha na comunicação com o servidor Samba." : output);
        return output;
    }
}
