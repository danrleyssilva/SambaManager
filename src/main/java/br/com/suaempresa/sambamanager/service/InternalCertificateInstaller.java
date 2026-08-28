package br.com.suaempresa.sambamanager.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Installs the bundled, public internal CA to the current Windows user's trusted-root store. */
public final class InternalCertificateInstaller {
    private static final String RESOURCE = "/certs/royal-samba-ca.crt";

    private InternalCertificateInstaller() { }

    public static void ensureInstalled() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return;
        try (InputStream certificate = InternalCertificateInstaller.class.getResourceAsStream(RESOURCE)) {
            if (certificate == null) {
                throw new IllegalStateException("O certificado interno não foi incluído nesta versão do aplicativo.");
            }
            Path temporaryCertificate = Files.createTempFile("royal-samba-ca-", ".crt");
            try {
                Files.copy(certificate, temporaryCertificate, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Process process = new ProcessBuilder("certutil.exe", "-user", "-addstore", "-f", "Root", temporaryCertificate.toString())
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes());
                if (process.waitFor() != 0) {
                    throw new IllegalStateException("Não foi possível instalar o certificado interno: " + output);
                }
                AppLog.info("Certificado interno verificado no repositório de confiança do Windows.");
            } finally {
                Files.deleteIfExists(temporaryCertificate);
            }
        }
    }
}
