package br.com.suaempresa.sambamanager.service;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Creates HTTPS clients that trust only the internal Royal Samba CA. */
final class InternalHttpsClient {
    private static final String CA_RESOURCE = "/certs/royal-samba-ca.crt";

    private InternalHttpsClient() {
    }

    static HttpClient create() throws Exception {
        try (InputStream input = InternalHttpsClient.class.getResourceAsStream(CA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("O certificado interno não foi incluído nesta versão do aplicativo.");
            }
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("royal-samba-ca",
                    CertificateFactory.getInstance("X.509").generateCertificate(input));
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            return HttpClient.newBuilder()
                    .sslContext(context)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }
    }
}
