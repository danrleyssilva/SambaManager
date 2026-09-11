package br.com.suaempresa.sambamanager.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Abre o instalador e tenta centralizar sua janela sem bloquear o aplicativo. */
public final class UpdateInstallerLauncher {
    private UpdateInstallerLauncher() {
    }

    public static void launchCentered(Path installer) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new IllegalStateException("A instalação automática está disponível somente no Windows.");
        }

        Path absoluteInstaller = installer.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absoluteInstaller)) {
            throw new IllegalArgumentException("O instalador baixado não foi encontrado.");
        }

        // O instalador é iniciado antes da tentativa de centralização. Assim, mesmo que
        // uma política do Windows bloqueie a API visual, a atualização continua aberta.
        String script = """
                $installer = $env:SAMBA_UPDATE_INSTALLER
                $process = Start-Process -FilePath $installer -PassThru
                try {
                    Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public static class SambaInstallerWindow {
                    [StructLayout(LayoutKind.Sequential)]
                    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
                    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
                    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
                    [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
                    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
                }
                '@
                    for ($attempt = 0; $attempt -lt 150; $attempt++) {
                        Start-Sleep -Milliseconds 100
                        $process.Refresh()
                        $handle = $process.MainWindowHandle
                        if ($handle -ne [IntPtr]::Zero) {
                            $rect = New-Object SambaInstallerWindow+RECT
                            if ([SambaInstallerWindow]::GetWindowRect($handle, [ref]$rect)) {
                                $width = $rect.Right - $rect.Left
                                $height = $rect.Bottom - $rect.Top
                                $x = [Math]::Max(0, [int](([SambaInstallerWindow]::GetSystemMetrics(0) - $width) / 2))
                                $y = [Math]::Max(0, [int](([SambaInstallerWindow]::GetSystemMetrics(1) - $height) / 2))
                                [SambaInstallerWindow]::SetWindowPos($handle, [IntPtr]::Zero, $x, $y, 0, 0, 0x45) | Out-Null
                                [SambaInstallerWindow]::SetForegroundWindow($handle) | Out-Null
                                break
                            }
                        }
                        if ($process.HasExited) { break }
                    }
                } catch {
                    # A falha ao reposicionar não deve fechar nem interromper o instalador.
                }
                """;

        String encodedScript = Base64.getEncoder()
                .encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder helper = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle", "Hidden",
                "-EncodedCommand", encodedScript);
        helper.environment().put("SAMBA_UPDATE_INSTALLER", absoluteInstaller.toString());
        helper.redirectErrorStream(true);
        helper.start();
    }
}
