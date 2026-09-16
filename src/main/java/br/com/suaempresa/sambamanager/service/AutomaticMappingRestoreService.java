package br.com.suaempresa.sambamanager.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Base64;

/** Reconnects remembered drives at Windows sign-in, without a JavaFX window. */
public final class AutomaticMappingRestoreService {
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String RUN_VALUE = "RoyalServerAccessRestore";
    private static final String RESTORE_LAUNCHER = "Royal Server Access Restore.exe";

    private AutomaticMappingRestoreService() { }

    public static boolean available() {
        try {
            restoreLauncher();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean hasRememberedMappings(String server) throws Exception {
        Process process = new ProcessBuilder("reg.exe", "query", "HKCU\\Network", "/s", "/v", "RemotePath")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return process.waitFor() == 0 && output.contains("\\\\" + server + "\\");
    }

    public static void enable(String server, String user, char[] password) throws Exception {
        Path launcher = restoreLauncher();
        AppLog.info("Reconexão automática: auxiliar encontrado em " + launcher + ".");
        try {
            AppLog.info("Reconexão automática: salvando credencial própria no Windows (senha não registrada).");
            WindowsCredentialService.saveForAutomaticRestore(server, user, password);
            registerStartup(launcher);
            AppLog.info("Reconexão automática: inicialização registrada no perfil Windows atual.");
            AppLog.info("Reconexão automática ativada para " + server
                    + " no perfil atual do Windows. Senha no Gerenciador de Credenciais; não registrada no log.");
        } catch (Exception failure) {
            AppLog.error("Falha ao ativar reconexão automática para " + server + ".", failure);
            throw failure;
        }
    }

    /** Only updates a password if automatic restore was already enabled. */
    public static void updatePasswordIfEnabled(String server, String user, char[] password) throws Exception {
        if (startupRegistered()) {
            enable(server, user, password);
        }
    }

    public static void disable(String server) throws Exception {
        // Remove only our own startup value, never the user's other startup apps.
        Process process = new ProcessBuilder("reg.exe", "delete", RUN_KEY, "/v", RUN_VALUE, "/f")
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor(); // A missing value is harmless.
        WindowsCredentialService.deleteAutomaticRestore(server);
        Files.deleteIfExists(legacyCredentialFile(server));
        AppLog.info("Reconexão automática desativada para " + server + ".");
    }

    private static boolean startupRegistered() throws Exception {
        Process process = new ProcessBuilder("reg.exe", "query", RUN_KEY, "/v", RUN_VALUE)
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0;
    }

    public static void recordStartupEvent(String message) {
        restoreLog(message);
    }

    public static void restore(String server) throws Exception {
        restoreLog("Iniciando reconexão automática de " + server + " na sessão atual do Windows.");
        String script = """
                $ErrorActionPreference='Stop'
                Add-Type -TypeDefinition @'
                using System;
                using System.ComponentModel;
                using System.Runtime.InteropServices;
                using System.Text;
                public static class RoyalRestoreNetwork {
                    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
                    public struct NETRESOURCE {
                        public uint Scope, Type, DisplayType, Usage;
                        [MarshalAs(UnmanagedType.LPWStr)] public string LocalName, RemoteName, Comment, Provider;
                    }
                    [DllImport("mpr.dll", CharSet=CharSet.Unicode, EntryPoint="WNetAddConnection2W")]
                    public static extern uint Connect(ref NETRESOURCE resource, string password, string user, uint flags);
                    [DllImport("mpr.dll", CharSet=CharSet.Unicode, EntryPoint="WNetCancelConnection2W")]
                    public static extern uint Cancel(string local, uint flags, bool force);
                    [DllImport("mpr.dll", CharSet=CharSet.Unicode, EntryPoint="WNetGetConnectionW")]
                    private static extern uint WNetGetConnection(string local, StringBuilder remote, ref uint length);
                    public static string ConnectedRemote(string local) {
                        StringBuilder remote = new StringBuilder(1024);
                        uint length = (uint)remote.Capacity;
                        return WNetGetConnection(local, remote, ref length) == 0 ? remote.ToString() : null;
                    }

                    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
                    private struct CREDENTIAL {
                        public uint Flags, Type;
                        [MarshalAs(UnmanagedType.LPWStr)] public string TargetName, Comment;
                        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
                        public uint CredentialBlobSize;
                        public IntPtr CredentialBlob;
                        public uint Persist, AttributeCount;
                        public IntPtr Attributes;
                        [MarshalAs(UnmanagedType.LPWStr)] public string TargetAlias, UserName;
                    }
                    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, EntryPoint="CredReadW", SetLastError=true)]
                    private static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);
                    [DllImport("advapi32.dll", EntryPoint="CredFree")]
                    private static extern void CredFree(IntPtr credential);
                    public static string[] ReadCredential(string target) {
                        IntPtr pointer;
                        if (!CredRead(target, 1, 0, out pointer))
                            throw new Win32Exception(Marshal.GetLastWin32Error());
                        try {
                            CREDENTIAL value=(CREDENTIAL)Marshal.PtrToStructure(pointer,typeof(CREDENTIAL));
                            if (value.Persist < 2 || string.IsNullOrWhiteSpace(value.UserName) ||
                                    value.CredentialBlob == IntPtr.Zero || value.CredentialBlobSize == 0)
                                throw new InvalidOperationException("Credencial propria incompleta");
                            byte[] bytes=new byte[value.CredentialBlobSize];
                            try {
                                Marshal.Copy(value.CredentialBlob,bytes,0,bytes.Length);
                                return new string[] { value.UserName, Encoding.Unicode.GetString(bytes) };
                            } finally { Array.Clear(bytes,0,bytes.Length); }
                        } finally { CredFree(pointer); }
                    }
                }
                '@
                $server=$env:ROYAL_RESTORE_SERVER
                $prefix='\\\\'+$server+'\\'
                $saved=@(Get-ChildItem 'HKCU:\\Network' -ErrorAction SilentlyContinue | ForEach-Object {
                    $entry=Get-ItemProperty -LiteralPath $_.PSPath
                    if ($_.PSChildName -match '^[A-Za-z]$' -and $null -ne $entry.RemotePath -and
                            $entry.RemotePath.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)) {
                        [pscustomobject]@{ Local=($_.PSChildName.ToUpperInvariant()+':');
                            Remote=$entry.RemotePath; User=$entry.UserName }
                    }
                })
                if ($saved.Count -eq 0) { 'Nenhuma unidade lembrada para este servidor'; exit 0 }
                try {
                    try {
                        $credential=[RoyalRestoreNetwork]::ReadCredential('RoyalMax.RoyalServerAccess:'+$server)
                    } catch {
                        $detail=$_.Exception.InnerException
                        $native=if ($detail -is [ComponentModel.Win32Exception]) {
                            $detail.NativeErrorCode
                        } else { 0 }
                        Write-Output ('ROYAL_RESTORE_DIAG:VAULT:'+ $native)
                        exit 1
                    }
                    $user=$credential[0]
                    $password=$credential[1]
                    $saved=@($saved | Where-Object {
                        [string]::IsNullOrWhiteSpace($_.User) -or
                        [string]::Equals($_.User,$user,[StringComparison]::OrdinalIgnoreCase) -or
                        $_.User.EndsWith('\\'+$user,[StringComparison]::OrdinalIgnoreCase)
                    })
                    if ($saved.Count -eq 0) {
                        Write-Output 'ROYAL_RESTORE_DIAG:ACCOUNT_MISMATCH'
                        exit 1
                    }
                    $pending=@($saved)
                    $restored=0
                    $lastCode=0
                    for ($attempt=1; $attempt -le 12 -and $pending.Count -gt 0; $attempt++) {
                        $remaining=@()
                        foreach ($item in $pending) {
                            $active=[RoyalRestoreNetwork]::ConnectedRemote($item.Local)
                            $sameRemote=$active -and [string]::Equals($active,$item.Remote,
                                    [StringComparison]::OrdinalIgnoreCase)
                            $localPath=$item.Local+'\\'
                            if ($sameRemote -and (Test-Path -LiteralPath $localPath -ErrorAction SilentlyContinue)) {
                                Write-Output ('ROYAL_RESTORE_LETTER:'+$item.Local+':ACCESSIBLE')
                                $restored++
                                continue
                            }
                            if ($active -and -not $sameRemote) {
                                Write-Output ('ROYAL_RESTORE_DIAG:LETTER_CONFLICT:'+$item.Local)
                                exit 1
                            }
                            if ($sameRemote) {
                                # O caminho foi lembrado, mas o acesso falhou; soltamos só esta sessão.
                                $cancelCode=[RoyalRestoreNetwork]::Cancel($item.Local,0,$false)
                                if ($cancelCode -ne 0 -and $cancelCode -ne 2250) {
                                    Write-Output ('ROYAL_RESTORE_DIAG:LETTER_DISCONNECT:'+$item.Local+':'+$cancelCode)
                                    exit 1
                                }
                            }
                            $resource=New-Object RoyalRestoreNetwork+NETRESOURCE
                            $resource.Type=1
                            $resource.LocalName=$item.Local
                            $resource.RemoteName=$item.Remote
                            # Reconecta somente a letra lembrada para este servidor e perfil.
                            $code=[RoyalRestoreNetwork]::Connect([ref]$resource,$password,$user,1)
                            if ($code -eq 85) {
                                # Desliga apenas uma sessão antiga desta letra, sem apagar o perfil.
                                $cancelCode=[RoyalRestoreNetwork]::Cancel($item.Local,0,$false)
                                if ($cancelCode -eq 0 -or $cancelCode -eq 2250) {
                                    $code=[RoyalRestoreNetwork]::Connect([ref]$resource,$password,$user,1)
                                }
                            }
                            if ($code -eq 0) {
                                if (Test-Path -LiteralPath $localPath -ErrorAction SilentlyContinue) {
                                    Write-Output ('ROYAL_RESTORE_LETTER:'+$item.Local+':ACCESSIBLE')
                                    $restored++
                                } else {
                                    Write-Output ('ROYAL_RESTORE_LETTER:'+$item.Local+':CONNECTED_NOT_ACCESSIBLE')
                                    $lastCode=1201
                                    $remaining += $item
                                }
                                continue
                            }
                            if ($code -eq 1219) {
                                Write-Output 'ROYAL_RESTORE_DIAG:SMB_ACCOUNT_CONFLICT:1219'
                                exit 1
                            }
                            if ($code -eq 86 -or $code -eq 1326) {
                                Write-Output ('ROYAL_RESTORE_DIAG:SMB_AUTH:'+ $code)
                                exit 1
                            }
                            if ($code -eq 85 -or $code -eq 1202) {
                                Write-Output ('ROYAL_RESTORE_DIAG:LETTER_ALREADY_USED:'+$item.Local+':'+$code)
                                exit 1
                            }
                            $lastCode=$code
                            $remaining += $item
                        }
                        $pending=@($remaining)
                        if ($pending.Count -gt 0 -and $attempt -lt 12) { Start-Sleep -Seconds 5 }
                    }
                    if ($pending.Count -gt 0) {
                        Write-Output ('ROYAL_RESTORE_DIAG:SMB_NETWORK:'+ $lastCode)
                        exit 1
                    }
                    Write-Output ('ROYAL_RESTORE_DONE:'+$restored+'/'+$saved.Count)
                } finally { $credential=$null; $password=$null }
                """;
        try {
            String result = runPowerShell(script, server);
            restoreLog(result.strip().replaceAll("[\\r\\n]+", " | "));
        } catch (Exception failure) {
            restoreLog("Falha na reconexão automática de " + server + ": " + failure.getMessage());
            throw failure;
        }
    }

    private static Path restoreLauncher() {
        String command = ProcessHandle.current().info().command().orElse("");
        if (command.isBlank()) {
            throw new IllegalStateException("Não foi possível localizar o executável instalado.");
        }
        Path launcher = Path.of(command).toAbsolutePath().getParent().resolve(RESTORE_LAUNCHER);
        if (!Files.isRegularFile(launcher)) {
            throw new IllegalStateException("Reconexão automática exige o instalador novo do Royal Server Access.");
        }
        return launcher;
    }

    private static Path legacyCredentialFile(String server) {
        if (!server.matches("[0-9A-Za-z.-]+")) {
            throw new IllegalArgumentException("Nome de servidor inválido.");
        }
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            throw new IllegalStateException("Perfil do Windows não disponível.");
        }
        return Path.of(appData, "RoyalServerAccess", "auto-restore-" + server + ".bin");
    }

    private static String runPowerShell(String script, String server) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encoded);
        builder.environment().put("ROYAL_RESTORE_SERVER", server);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            // PowerShell's error output could contain sensitive values; never include it in a log.
            output.lines().map(String::trim)
                    .filter(line -> line.startsWith("ROYAL_RESTORE_LETTER:"))
                    .forEach(AutomaticMappingRestoreService::restoreLog);
            String diagnosis = output.lines().map(String::trim)
                    .filter(line -> line.startsWith("ROYAL_RESTORE_DIAG:"))
                    .map(line -> line.substring("ROYAL_RESTORE_DIAG:".length()))
                    .findFirst().orElse("POWERSHELL:" + exit);
            throw new IllegalStateException("Reconexão automática falhou na etapa " + diagnosis + ".");
        }
        return output;
    }

    private static void registerStartup(Path launcher) throws Exception {
        // reg.exe strips the quotes around /d in some Windows installations. Store and
        // verify the literal quoted command, because the installed path contains spaces.
        String script = """
                $ErrorActionPreference='Stop'
                $key=[Microsoft.Win32.Registry]::CurrentUser.CreateSubKey(
                    'Software\\Microsoft\\Windows\\CurrentVersion\\Run')
                if ($null -eq $key) { throw 'Chave de inicializacao indisponivel' }
                try {
                    # Build quotation marks by character code so ProcessBuilder/PowerShell
                    # command-line parsing cannot consume them before the registry write.
                    $quote=[char]34
                    $command=$quote+$env:ROYAL_RESTORE_LAUNCHER+$quote
                    $key.SetValue('RoyalServerAccessRestore',$command,
                        [Microsoft.Win32.RegistryValueKind]::String)
                    $stored=[string]$key.GetValue('RoyalServerAccessRestore')
                    if ($stored -ne $command -or $stored[0] -ne $quote -or
                            $stored[$stored.Length-1] -ne $quote) {
                        throw 'Inicializacao nao confirmada'
                    }
                } finally { $key.Close() }
                """;
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-Command", script).redirectErrorStream(true);
        builder.environment().put("ROYAL_RESTORE_LAUNCHER", launcher.toString());
        Process process = builder.start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("Não foi possível registrar a reconexão na inicialização do Windows.");
        }
    }

    private static void restoreLog(String message) {
        try {
            String appData = System.getenv("APPDATA");
            if (appData == null || appData.isBlank()) return;
            Path log = Path.of(appData, "RoyalServerAccess", "auto-restore.log");
            Files.createDirectories(log.getParent());
            Files.writeString(log, LocalDateTime.now() + " " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Logging must not block Windows sign-in.
        }
    }
}
