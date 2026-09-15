package br.com.suaempresa.sambamanager.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Saves SMB credentials in the current Windows user's Credential Manager. */
public final class WindowsCredentialService {
    private WindowsCredentialService() {
    }

    public static void save(String server, String username, char[] password) throws Exception {
        run("save", server, username, password);
    }

    public static void delete(String server) throws Exception {
        run("delete", server, "", new char[0]);
    }

    public static void saveForAutomaticRestore(String server, String username, char[] password) throws Exception {
        run("auto-save", server, username, password);
    }

    public static void deleteAutomaticRestore(String server) throws Exception {
        run("auto-delete", server, "", new char[0]);
    }

    private static void run(String action, String server, String username, char[] password) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new IllegalStateException("O armazenamento de credenciais exige Windows.");
        }
        String script = """
                Add-Type -TypeDefinition @'
                using System;
                using System.ComponentModel;
                using System.Runtime.InteropServices;
                using System.Text;
                public static class SambaWindowsCredential {
                    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
                    private struct CREDENTIAL {
                        public uint Flags;
                        public uint Type;
                        [MarshalAs(UnmanagedType.LPWStr)] public string TargetName;
                        [MarshalAs(UnmanagedType.LPWStr)] public string Comment;
                        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
                        public uint CredentialBlobSize;
                        public IntPtr CredentialBlob;
                        public uint Persist;
                        public uint AttributeCount;
                        public IntPtr Attributes;
                        [MarshalAs(UnmanagedType.LPWStr)] public string TargetAlias;
                        [MarshalAs(UnmanagedType.LPWStr)] public string UserName;
                    }
                    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, EntryPoint = "CredWriteW", SetLastError = true)]
                    private static extern bool CredWrite(ref CREDENTIAL credential, uint flags);
                    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, EntryPoint = "CredReadW", SetLastError = true)]
                    private static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);
                    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, EntryPoint = "CredDeleteW", SetLastError = true)]
                    private static extern bool CredDelete(string target, uint type, uint flags);
                    [DllImport("advapi32.dll", EntryPoint = "CredFree")]
                    private static extern void CredFree(IntPtr credential);

                    public static void Save(string target, uint type, string user, string password) {
                        byte[] blob = Encoding.Unicode.GetBytes(password);
                        IntPtr memory = Marshal.AllocHGlobal(blob.Length);
                        try {
                            Marshal.Copy(blob, 0, memory, blob.Length);
                            CREDENTIAL value = new CREDENTIAL {
                                Type = type, TargetName = target, UserName = user,
                                CredentialBlob = memory, CredentialBlobSize = (uint)blob.Length,
                                Persist = 2
                            };
                            if (!CredWrite(ref value, 0))
                                throw new Win32Exception(Marshal.GetLastWin32Error());
                            IntPtr stored;
                            if (!CredRead(target, type, 0, out stored))
                                throw new Win32Exception(Marshal.GetLastWin32Error());
                            try {
                                CREDENTIAL confirmed = (CREDENTIAL)Marshal.PtrToStructure(stored, typeof(CREDENTIAL));
                                if (confirmed.Persist < 2 ||
                                        !string.Equals(confirmed.UserName, user, StringComparison.OrdinalIgnoreCase))
                                    throw new InvalidOperationException("A credencial nao persistiu apos o logoff");
                                if (type == 1) {
                                    if (confirmed.CredentialBlobSize != blob.Length || confirmed.CredentialBlob == IntPtr.Zero)
                                        throw new InvalidOperationException("A credencial do aplicativo nao guardou a senha");
                                    byte[] check = new byte[blob.Length];
                                    try {
                                        Marshal.Copy(confirmed.CredentialBlob, check, 0, check.Length);
                                        for (int index = 0; index < blob.Length; index++)
                                            if (blob[index] != check[index])
                                                throw new InvalidOperationException("A credencial do aplicativo mudou ao salvar");
                                    } finally { Array.Clear(check, 0, check.Length); }
                                }
                            } finally { CredFree(stored); }
                        } finally {
                            for (int index = 0; index < blob.Length; index++) blob[index] = 0;
                            for (int index = 0; index < blob.Length; index++) Marshal.WriteByte(memory, index, 0);
                            Marshal.FreeHGlobal(memory);
                        }
                    }

                    public static void Delete(string target, uint type) {
                        if (!CredDelete(target, type, 0) && Marshal.GetLastWin32Error() != 1168)
                            throw new Win32Exception(Marshal.GetLastWin32Error());
                    }
                }
                '@
                $auto=$env:SAMBA_CREDENTIAL_ACTION.StartsWith('auto-')
                $target=if ($auto) { 'RoyalMax.RoyalServerAccess:'+$env:SAMBA_CREDENTIAL_SERVER }
                    else { $env:SAMBA_CREDENTIAL_SERVER }
                $type=if ($auto) { [uint32]1 } else { [uint32]2 }
                try {
                    if ($env:SAMBA_CREDENTIAL_ACTION.EndsWith('save')) {
                        [SambaWindowsCredential]::Save($target,$type,
                            $env:SAMBA_CREDENTIAL_USER,$env:SAMBA_CREDENTIAL_PASSWORD)
                    } else {
                        [SambaWindowsCredential]::Delete($target,$type)
                    }
                } catch {
                    $detail=$_.Exception
                    while ($null -ne $detail.InnerException) { $detail=$detail.InnerException }
                    $native=if ($detail -is [ComponentModel.Win32Exception]) {
                        $detail.NativeErrorCode
                    } else { 0 }
                    Write-Output ('SAMBA_CREDENTIAL_DIAG:'+ $native + ':' + $detail.GetType().Name)
                    exit 1
                }
                """;
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder command = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encoded);
        command.environment().put("SAMBA_CREDENTIAL_ACTION", action);
        command.environment().put("SAMBA_CREDENTIAL_SERVER", server);
        command.environment().put("SAMBA_CREDENTIAL_USER", username);
        command.environment().put("SAMBA_CREDENTIAL_PASSWORD", new String(password));
        command.redirectErrorStream(true);
        Process process = command.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            String diagnosis = output.lines().map(String::trim)
                    .filter(line -> line.startsWith("SAMBA_CREDENTIAL_DIAG:"))
                    .findFirst().orElse("SAMBA_CREDENTIAL_DIAG:sem código");
            throw new IllegalStateException("O Windows não conseguiu acessar a credencial para " + server
                    + " (" + diagnosis.substring("SAMBA_CREDENTIAL_DIAG:".length()) + ").");
        }
        AppLog.info(action.endsWith("save")
                ? (action.startsWith("auto-")
                    ? "Credencial própria de reconexão verificada no Gerenciador de Credenciais para " + server + "."
                    : "Credencial permanente confirmada no Gerenciador de Credenciais do Windows para " + server + ".")
                : (action.startsWith("auto-")
                    ? "Credencial própria de reconexão removida para " + server + "."
                    : "Credencial do servidor " + server + " removida do Gerenciador de Credenciais do Windows."));
    }
}
