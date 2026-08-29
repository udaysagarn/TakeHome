package ai.devin.d1.github;

import ai.devin.d1.config.D1Properties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Supplies the bearer token every GitHub call is made with.
 *
 * <p>A GitHub App is preferred: D1 signs a short-lived RS256 JWT with the app's private key and
 * exchanges it for an installation token, which GitHub scopes to the installed repositories and
 * expires after an hour. Actions then appear under the app's own identity rather than a human's. A
 * personal access token is the fallback so the control plane still runs without an app.
 */
@Component
public class GitHubCredentials {

    private static final Logger log = LoggerFactory.getLogger(GitHubCredentials.class);

    /** GitHub rejects a JWT older than 10 minutes; 9 leaves room for clock skew. */
    private static final Duration JWT_TTL = Duration.ofMinutes(9);

    /** Installation tokens last an hour; refresh early so no call races the expiry. */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final D1Properties props;
    private final RestClient http;

    private volatile String installationToken;
    private volatile Instant installationTokenExpiry = Instant.EPOCH;

    public GitHubCredentials(RestClient.Builder builder, D1Properties props) {
        this.props = props;
        this.http = builder.baseUrl(props.getGithub().getApiUrl()).build();
    }

    public boolean isConfigured() {
        return props.getGithub().getApp().isConfigured()
                || (props.getGithub().getToken() != null
                        && !props.getGithub().getToken().isBlank());
    }

    /** The identity D1 acts as, for logs and issue comments. */
    public String identity() {
        return props.getGithub().getApp().isConfigured()
                ? "github-app:" + props.getGithub().getApp().getAppId()
                : "token";
    }

    public synchronized String bearerToken() {
        D1Properties.App app = props.getGithub().getApp();
        if (!app.isConfigured()) {
            return props.getGithub().getToken();
        }
        if (installationToken != null && Instant.now().isBefore(installationTokenExpiry.minus(REFRESH_MARGIN))) {
            return installationToken;
        }
        InstallationToken minted = http.post()
                .uri("/app/installations/{id}/access_tokens", app.getInstallationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt(app))
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .body(InstallationToken.class);
        if (minted == null || minted.token() == null) {
            throw new IllegalStateException("GitHub returned no installation token for app " + app.getAppId());
        }
        installationToken = minted.token();
        installationTokenExpiry = minted.expiresAt() == null
                ? Instant.now().plus(Duration.ofHours(1))
                : Instant.parse(minted.expiresAt());
        log.info("minted GitHub App installation token, expires at {}", installationTokenExpiry);
        return installationToken;
    }

    private static String appJwt(D1Properties.App app) {
        Instant now = Instant.now();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iat\":" + (now.getEpochSecond() - 60) + ",\"exp\":"
                + now.plus(JWT_TTL).getEpochSecond() + ",\"iss\":\"" + app.getAppId() + "\"}");
        String signingInput = header + "." + payload;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey(app.getPrivateKey()));
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not sign GitHub App JWT", e);
        }
    }

    /**
     * GitHub hands out PKCS#1 keys ({@code BEGIN RSA PRIVATE KEY}) while the JDK only reads PKCS#8,
     * so a PKCS#1 body is wrapped in the PKCS#8 envelope rather than making the operator run
     * {@code openssl pkcs8} first.
     */
    private static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String body = pem.replace("\\n", "\n")
                .replaceAll("-----BEGIN (RSA )?PRIVATE KEY-----", "")
                .replaceAll("-----END (RSA )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        if (pkcs1) {
            der = wrapPkcs1(der);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** SEQUENCE { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL }, OCTET STRING pkcs1 } */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] algorithm = {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05,
            0x00
        };
        byte[] octetString = derTagged((byte) 0x04, pkcs1);
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(version);
        inner.writeBytes(algorithm);
        inner.writeBytes(octetString);
        return derTagged((byte) 0x30, inner.toByteArray());
    }

    private static byte[] derTagged(byte tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int byteCount = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
            out.write(0x80 | byteCount);
            for (int i = byteCount - 1; i >= 0; i--) {
                out.write((length >> (8 * i)) & 0xff);
            }
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record InstallationToken(String token, String expiresAt) {}
}
