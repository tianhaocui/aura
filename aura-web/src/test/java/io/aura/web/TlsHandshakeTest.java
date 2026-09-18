package io.aura.web;

import io.aura.AuraApp;
import io.aura.AuraConfig;
import io.aura.contract.RouteContribution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TlsHandshakeTest {

    private static final String ALIAS = "aura-test";
    private static final String PASSWORD = "aura-test-password";

    @TempDir
    Path tempDir;

    @Test
    void servesHttpsWithARealTlsHandshakeAndCertificateValidation() throws Exception {
        Path keyStorePath = tempDir.resolve("server.p12");
        generateServerKeyStore(keyStorePath);

        KeyStore serverKeyStore = loadKeyStore(keyStorePath);
        assertThat(serverKeyStore.entryInstanceOf(ALIAS, KeyStore.PrivateKeyEntry.class)).isTrue();
        Certificate serverCertificate = serverKeyStore.getCertificate(ALIAS);
        assertThat(serverCertificate).isNotNull();

        int port = freePort();
        URI endpoint = URI.create("https://localhost:" + port + "/ok");
        AuraApp app = TestApplications.app(
                new AuraConfig().port(port).tls(keyStorePath.toString(), PASSWORD),
                "tls-handshake",
                new RouteContribution("GET", "/ok", (BaseHandler) request -> request.text("ok")));

        try {
            app.start();

            HttpRequest request = HttpRequest.newBuilder(endpoint).GET().build();
            assertThatThrownBy(() -> HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString()))
                    .isInstanceOf(IOException.class)
                    .satisfies(error -> assertThat(hasCause(error, SSLHandshakeException.class))
                            .as("default HttpClient must reject the self-signed certificate")
                            .isTrue());

            HttpResponse<String> response = clientTrustingOnly(serverCertificate)
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ok");
            assertThat(response.sslSession()).isPresent();
            assertThat(response.headers().firstValue("Strict-Transport-Security"))
                    .contains("max-age=31536000; includeSubDomains");
        } finally {
            app.stop();
        }
    }

    private static void generateServerKeyStore(Path keyStorePath) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process process = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias", ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-storetype", "PKCS12",
                "-keystore", keyStorePath.toString(),
                "-storepass", PASSWORD,
                "-keypass", PASSWORD,
                "-dname", "CN=localhost, OU=Test, O=Aura, L=Test, ST=Test, C=US",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-noprompt")
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
        }
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(finished).as("keytool timed out: %s", output).isTrue();
        assertThat(process.exitValue()).as("keytool output: %s", output).isZero();
    }

    private static KeyStore loadKeyStore(Path keyStorePath) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, PASSWORD.toCharArray());
        }
        return keyStore;
    }

    private static HttpClient clientTrustingOnly(Certificate serverCertificate) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry(ALIAS, serverCertificate);

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(sslContext).build();
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
