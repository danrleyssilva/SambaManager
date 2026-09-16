package br.com.suaempresa.sambamanager.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Uses Windows PowerShell so persisted SMB drives are created by Windows itself. */
public class WindowsDriveMappingService {
    public List<String> map(String server, String username, char[] password, List<String> shares) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            throw new IllegalStateException("O mapeamento de unidades está disponível somente no Windows.");
        }
        char[] retained = password.clone();
        try {
            String script = mappingScript(false);
            AppLog.info("Iniciando mapeamento de " + shares.size() + " compartilhamento(s) para o usuário " + username + ".");
            String output = runPowerShell(script, server, username, password, shares);
            AppLog.info("Resposta do PowerShell no mapeamento: " + output.replaceAll("[\\r\\n]+", " | "));
            List<String> mapped = mappedDrives(output, shares.size());
            saveForReconnect(server, username, retained);
            return mapped;
        } finally {
            java.util.Arrays.fill(retained, '\0');
        }
    }

    /** Removes only mappings for this Samba server, then recreates the selected mappings. */
    public List<String> refreshMappings(String server, String username, char[] password, List<String> shares) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            throw new IllegalStateException("O mapeamento de unidades está disponível somente no Windows.");
        }
        char[] retained = password.clone();
        try {
            String script = mappingScript(true);
            AppLog.info("Atualizando mapeamentos do servidor " + server + ": removendo os anteriores e recriando " + shares.size() + " pasta(s).");
            String output = runPowerShell(script, server, username, password, shares);
            AppLog.info("Resposta do PowerShell na atualização: " + output.replaceAll("[\\r\\n]+", " | "));
            List<String> mapped = mappedDrives(output, shares.size());
            saveForReconnect(server, username, retained);
            return mapped;
        } finally {
            java.util.Arrays.fill(retained, '\0');
        }
    }

    private void saveForReconnect(String server, String username, char[] password) throws Exception {
        try {
            WindowsCredentialService.save(server, username, password);
        } catch (Exception failure) {
            AppLog.error("As unidades foram mapeadas, mas o Windows não salvou a credencial comum.", failure);
        }
        if (AutomaticMappingRestoreService.available()) {
            try {
                AutomaticMappingRestoreService.enable(server, username, password);
            } catch (Exception failure) {
                AppLog.error("Unidades acessíveis, mas a ativação da reconexão automática falhou.", failure);
                throw new IllegalStateException("As pastas foram mapeadas, mas a reconexão automática não foi ativada. "
                        + "Consulte o log do programa.", failure);
            }
        } else {
            AppLog.info("Reconexão automática não ativada nesta execução: instale a versão nova do programa.");
        }
    }

    private String mappingScript(boolean clearExisting) {
        String networkApi = """
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                using System.Text;
                public static class SambaNetworkDrive {
                    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
                    private struct NETRESOURCE {
                        public uint Scope;
                        public uint Type;
                        public uint DisplayType;
                        public uint Usage;
                        [MarshalAs(UnmanagedType.LPWStr)] public string LocalName;
                        [MarshalAs(UnmanagedType.LPWStr)] public string RemoteName;
                        [MarshalAs(UnmanagedType.LPWStr)] public string Comment;
                        [MarshalAs(UnmanagedType.LPWStr)] public string Provider;
                    }
                    [DllImport("mpr.dll", CharSet = CharSet.Unicode, EntryPoint = "WNetAddConnection2W")]
                    private static extern uint WNetAddConnection2(ref NETRESOURCE resource,
                        string password, string user, uint flags);
                    [DllImport("mpr.dll", CharSet = CharSet.Unicode, EntryPoint = "WNetGetConnectionW")]
                    private static extern uint WNetGetConnection(string local, StringBuilder remote, ref uint length);

                    public static uint Map(string letter, string remote, string user, string password) {
                        NETRESOURCE resource = new NETRESOURCE {
                            Type = 1, LocalName = letter, RemoteName = remote
                        };
                        return WNetAddConnection2(ref resource, password, user, 1);
                    }
                    public static bool Active(string letter, string expected) {
                        StringBuilder remote = new StringBuilder(1024);
                        uint length = (uint)remote.Capacity;
                        return WNetGetConnection(letter, remote, ref length) == 0 &&
                            string.Equals(remote.ToString(), expected, StringComparison.OrdinalIgnoreCase);
                    }
                }
                '@
                """;
        String preparation = clearExisting
                ? "New-PSDrive -Name 'SambaRefreshCheck' -PSProvider FileSystem -Root ('\\\\'+$server+'\\'+$items[0]) -Credential $credential -ErrorAction Stop | Out-Null; "
                + "Remove-PSDrive -Name 'SambaRefreshCheck' -ErrorAction SilentlyContinue; "
                + "Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Root -like ('\\\\'+$server+'\\*') } | "
                + "ForEach-Object { Remove-PSDrive -Name $_.Name -Force -ErrorAction Stop }; "
                : "";
        return networkApi + "$server=$env:SAMBA_MANAGER_SERVER; $user=$env:SAMBA_MANAGER_USER; "
                + "$items=$env:SAMBA_MANAGER_SHARES -split '\\|'; "
                + "$secure=ConvertTo-SecureString $env:SAMBA_MANAGER_PASSWORD -AsPlainText -Force; "
                + "$credential=[pscredential]::new($user,$secure); " + preparation
                + "$occupied=@{}; "
                + "Get-PSDrive -PSProvider FileSystem | ForEach-Object { $occupied[$_.Name.ToUpperInvariant()]=$true }; "
                + "$existing=@{}; "
                + "Get-CimInstance Win32_LogicalDisk | ForEach-Object { "
                + "$name=$_.DeviceID.TrimEnd(':').ToUpperInvariant(); $occupied[$name]=$true; "
                + "if($_.ProviderName -and -not $existing.ContainsKey($_.ProviderName)){ "
                + "$existing[$_.ProviderName]=$name } }; "
                + "Get-ChildItem 'HKCU:\\Network' -ErrorAction SilentlyContinue | Sort-Object PSChildName | "
                + "ForEach-Object { $name=$_.PSChildName.ToUpperInvariant(); $occupied[$name]=$true; "
                + "$saved=Get-ItemProperty -LiteralPath $_.PSPath; "
                + "if($saved.RemotePath -and -not $existing.ContainsKey($saved.RemotePath)){ "
                + "$existing[$saved.RemotePath]=$name } }; "
                + "Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Root -like ('\\\\'+$server+'\\*') } | "
                + "ForEach-Object { if(-not $existing.ContainsKey($_.Root)){ "
                + "$existing[$_.Root]=$_.Name.ToUpperInvariant() } }; "
                + "$next=[int][char]'F'; foreach($share in $items){ $mapped=$false; "
                + "$root='\\\\'+$server+'\\'+$share; "
                + "if($existing.ContainsKey($root)){ "
                + "$name=$existing[$root]; "
                + "if(-not [SambaNetworkDrive]::Active(($name+':'),$root)){ "
                + "$reuseCode=[SambaNetworkDrive]::Map(($name+':'),$root,$user,$env:SAMBA_MANAGER_PASSWORD); "
                + "if($reuseCode -ne 0){ "
                + "Write-Output ('SAMBA_MANAGER_MAP_ERROR:'+$share+' | Unidade '+$name+': ja existente; erro de reconexao '+$reuseCode); "
                + "continue } }; "
                + "if(-not [SambaNetworkDrive]::Active(($name+':'),$root)){ "
                + "Write-Output ('SAMBA_MANAGER_MAP_ERROR:'+$share+' | Unidade '+$name+': ja existente, mas indisponivel'); "
                + "continue }; "
                + "Write-Output ('SAMBA_MANAGER_REUSED:'+$name+':'+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($share))); "
                + "continue }; "
                + "while($next -le [int][char]'Z'){ $name=([char]$next).ToString(); $next++; "
                + "if($occupied.ContainsKey($name)){ continue }; "
                + "$occupied[$name]=$true; try { "
                + "$code=[SambaNetworkDrive]::Map(($name+':'),$root,$user,$env:SAMBA_MANAGER_PASSWORD); "
                + "if($code -eq 85 -or $code -eq 1202){ continue }; "
                + "if($code -ne 0){ throw ('Erro de rede do Windows '+$code) }; "
                + "$saved=Get-ItemProperty -LiteralPath ('HKCU:\\Network\\'+$name) -ErrorAction SilentlyContinue; "
                + "if($null -eq $saved -or $saved.RemotePath -ne $root){ "
                + "throw 'O Windows nao salvou o mapeamento persistente no perfil do usuario' }; "
                + "if(-not [SambaNetworkDrive]::Active(($name+':'),$root)){ "
                + "throw 'A unidade foi salva, mas nao ficou ativa na sessao do Windows' }; "
                + "$existing[$root]=$name; "
                + "Write-Output ('SAMBA_MANAGER_MAPPED:'+$name+':'+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($share))); "
                + "$mapped=$true; break "
                + "} catch { "
                + "$reason=$_.Exception.Message; "
                + "Write-Output ('SAMBA_MANAGER_MAP_ERROR:'+$share+' | '+$reason); break "
                + "} }; if(-not $mapped -and $next -gt [int][char]'Z'){ "
                + "Write-Output ('SAMBA_MANAGER_MAP_ERROR:'+$share+' | Nenhuma letra disponivel de F a Z') "
                + "} }";
    }

    private List<String> mappedDrives(String output, int requested) {
        List<String> drives = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.startsWith("SAMBA_MANAGER_MAPPED:") || line.startsWith("SAMBA_MANAGER_REUSED:")) {
                boolean reused = line.startsWith("SAMBA_MANAGER_REUSED:");
                String marker = reused ? "SAMBA_MANAGER_REUSED:" : "SAMBA_MANAGER_MAPPED:";
                String data = line.substring(marker.length());
                int separator = data.indexOf(':');
                if (separator > 0) {
                    String letter = data.substring(0, separator) + ":";
                    String share = new String(Base64.getDecoder().decode(data.substring(separator + 1)), StandardCharsets.UTF_8);
                    drives.add(letter);
                    AppLog.info("Compartilhamento " + share + (reused ? " já existente em " : " mapeado em ")
                            + letter + ".");
                }
            } else if (line.startsWith("SAMBA_MANAGER_MAP_ERROR:")) {
                AppLog.info("Falha em um compartilhamento: " + line.substring("SAMBA_MANAGER_MAP_ERROR:".length()));
            }
        }
        List<String> usable = new ArrayList<>();
        for (String drive : drives) {
            if (Files.isDirectory(Path.of(drive + "\\"))) {
                usable.add(drive);
            } else {
                AppLog.info("A unidade " + drive + " foi registrada, mas não está acessível na sessão do aplicativo.");
            }
        }
        if (usable.isEmpty() && requested > 0) {
            throw new IllegalStateException("Nenhuma unidade ficou acessível no Explorador de Arquivos. "
                    + "Consulte o log e verifique as conexões do Windows.");
        }
        AppLog.info("Mapeamento concluído: " + usable.size() + " de " + requested + " pasta(s) acessíveis.");
        return usable;
    }

    /** Removes only Windows drive mappings whose UNC root belongs to the specified Samba server. */
    public List<String> clearMappings(String server) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            throw new IllegalStateException("A limpeza de mapeamentos está disponível somente no Windows.");
        }
        String cancellationApi = """
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public static class SambaNetworkDriveCancel {
                    [DllImport("mpr.dll", CharSet = CharSet.Unicode, EntryPoint = "WNetCancelConnection2W")]
                    private static extern uint WNetCancelConnection2(string name, uint flags, bool force);
                    public static uint Cancel(string letter) {
                        return WNetCancelConnection2(letter, 1, true);
                    }
                }
                '@
                """;
        String script = cancellationApi + "$serverPath='\\\\'+$env:SAMBA_MANAGER_SERVER+'\\'; $letters=@(); "
                + "Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Root -like ($serverPath+'*') } | "
                + "ForEach-Object { $letters += ($_.Name+':') }; "
                + "Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=4' | "
                + "Where-Object { $_.ProviderName -like ($serverPath+'*') } | "
                + "ForEach-Object { $letters += $_.DeviceID }; "
                + "Get-ChildItem 'HKCU:\\Network' -ErrorAction SilentlyContinue | ForEach-Object { "
                + "$entry=Get-ItemProperty $_.PSPath; if($entry.RemotePath -like ($serverPath+'*')){ $letters += ($_.PSChildName+':') } }; "
                + "foreach($letter in @($letters | Select-Object -Unique)){ "
                + "$key='HKCU:\\Network\\'+$letter.TrimEnd(':'); "
                + "$saved=Get-ItemProperty -LiteralPath $key -ErrorAction SilentlyContinue; "
                + "if($null -ne $saved -and $saved.RemotePath -notlike ($serverPath+'*')){ continue }; "
                + "$code=[SambaNetworkDriveCancel]::Cancel($letter); "
                + "if($code -ne 0 -and $code -ne 2250){ "
                + "Write-Output ('SAMBA_MANAGER_REMOVE_ERROR:'+$letter+' | Erro de rede '+$code); continue }; "
                + "if(Test-Path -LiteralPath $key){ "
                + "Remove-Item -LiteralPath $key -Force -ErrorAction Stop }; "
                + "Write-Output ('SAMBA_MANAGER_REMOVED:'+$letter) }";
        AppLog.info("Iniciando limpeza de mapeamentos do servidor " + server + ".");
        String output = runPowerShell(script, server, "", new char[0], List.of());
        AppLog.info("Resposta do PowerShell na limpeza: " + output.replaceAll("[\\r\\n]+", " | "));
        output.lines().filter(line -> line.startsWith("SAMBA_MANAGER_REMOVE_ERROR:"))
                .forEach(line -> AppLog.info("Falha ao remover unidade: " + line));
        try {
            WindowsCredentialService.delete(server);
        } finally {
            AutomaticMappingRestoreService.disable(server);
        }
        return output.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("SAMBA_MANAGER_REMOVED:"))
                .map(line -> line.substring("SAMBA_MANAGER_REMOVED:".length()))
                .toList();
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
