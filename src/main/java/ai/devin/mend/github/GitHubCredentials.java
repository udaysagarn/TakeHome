package ai.devin.mend.github;

import ai.devin.mend.config.MendProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Supplies the bearer token every GitHub call is made with.
 *
 * <p>A GitHub App is preferred: menD signs a short-lived RS256 JWT with the app's private key and
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

    private final MendProperties props;
    private final RestClient http;

    private volatile String installationToken;
    private volatile Instant installationTokenExpiry = Instant.EPOCH;
    private volatile Map<String, String> permissions = Map.of();

    public GitHubCredentials(RestClient.Builder builder, MendProperties props) {
        this.props = props;
        this.http = builder.baseUrl(props.getGithub().getApiUrl()).build();
    }

    public boolean isConfigured() {
        return props.getGithub().getApp().isConfigured()
                || (props.getGithub().getToken() != null
                        && !props.getGithub().getToken().isBlank());
    }

    /** The installation whose token is in use; empty when running on a personal access token. */
    public String installationId() {
        return props.getGithub().getApp().isConfigured()
                ? props.getGithub().getApp().getInstallationId()
                : "";
    }

    /**
     * Permissions GitHub grants the installation, e.g. {@code issues -> write}. Empty on a personal
     * access token, whose scopes GitHub does not report on this route.
     */
    public Map<String, String> installationPermissions() {
        if (!props.getGithub().getApp().isConfigured()) {
            return Map.of();
        }
        bearerToken();
        return permissions;
    }

    /** The identity menD acts as, for logs and issue comments. */
    public String identity() {
        return props.getGithub().getApp().isConfigured()
                ? "github-app:" + props.getGithub().getApp().getAppId()
                : "token";
    }

    public synchronized String bearerToken() {
        MendProperties.App app = props.getGithub().getApp();
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
        permissions = minted.permissions() == null ? Map.of() : Map.copyOf(minted.permissions());
        installationTokenExpiry = minted.expiresAt() == null
                ? Instant.now().plus(Duration.ofHours(1))
                : Instant.parse(minted.expiresAt());
        log.info("minted GitHub App installation token, expires at {}", installationTokenExpiry);
        return installationToken;
    }

    private static String appJwt(MendProperties.App app) {
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
    private static PrivateKey privateKey(String configured) throws GeneralSecurityException {
        String pem = pem(configured);
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String body = pem.replaceAll("-----BEGIN (RSA )?PRIVATE KEY-----", "")
                .replaceAll("-----END (RSA )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new GitHubCredentialsException(
                    "GITHUB_APP_PRIVATE_KEY holds a PEM whose body is not valid base64. Re-copy the"
                            + " whole .pem GitHub generated, or point GITHUB_APP_PRIVATE_KEY at the file.");
        }
        if (pkcs1) {
            der = wrapPkcs1(der);
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new GitHubCredentialsException("GITHUB_APP_PRIVATE_KEY decoded, but is not an RSA private key"
                    + " GitHub would have issued (" + e.getMessage() + ").");
        }
    }

    /**
     * Resolves the configured value to PEM text, accepting the shapes an operator's environment
     * actually produces: the PEM itself, a PEM whose newlines survived only as {@code \n} escapes,
     * one still wrapped in the quotes it was pasted with, a path to the downloaded {@code .pem}, and
     * the whole file base64-encoded for a single-line variable. Anything else fails here, naming the
     * variable, rather than deeper down as an opaque base64 complaint.
     */
    private static String pem(String configured) {
        String value = unquote(configured.strip()).replace("\\n", "\n");
        if (value.contains("PRIVATE KEY-----")) {
            return value;
        }
        String fromFile = readIfPath(value);
        if (fromFile != null) {
            return fromFile;
        }
        String decoded = decodeIfBase64Pem(value);
        if (decoded != null) {
            return decoded;
        }
        throw new GitHubCredentialsException("GITHUB_APP_PRIVATE_KEY does not hold a private key: "
                + diagnosis(value)
                + " Set it to the contents of the .pem GitHub generated, to the path of that file, or to"
                + " the file base64-encoded.");
    }

    /** Why the value cannot be a key, said without repeating anything that might be key material. */
    private static String diagnosis(String value) {
        if (value.isEmpty()) {
            return "it is empty.";
        }
        if (value.contains("$")) {
            return "it still contains an unexpanded shell substitution. A .env file is read literally"
                    + " by docker compose and by Spring, so \"$(cat key.pem)\" is never run.";
        }
        if (value.contains("BEGIN OPENSSH PRIVATE KEY")) {
            return "it is an OpenSSH key, not the RSA key a GitHub App issues.";
        }
        if (value.contains("ENCRYPTED PRIVATE KEY")) {
            return "it is passphrase-encrypted; menD cannot decrypt it.";
        }
        return "it has no -----BEGIN PRIVATE KEY----- header.";
    }

    private static String unquote(String value) {
        boolean quoted = value.length() > 1
                && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")));
        return quoted ? value.substring(1, value.length() - 1) : value;
    }

    private static String readIfPath(String value) {
        if (value.contains("\n") || value.length() > 4096) {
            return null;
        }
        try {
            Path path = Path.of(value);
            if (!Files.isReadable(path) || Files.isDirectory(path)) {
                return null;
            }
            String contents = Files.readString(path, StandardCharsets.UTF_8);
            if (!contents.contains("PRIVATE KEY-----")) {
                throw new GitHubCredentialsException(
                        "GITHUB_APP_PRIVATE_KEY points at " + path + ", which is not a PEM private key.");
            }
            return contents;
        } catch (InvalidPathException | IOException e) {
            return null;
        }
    }

    private static String decodeIfBase64Pem(String value) {
        try {
            String decoded = new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
            return decoded.contains("PRIVATE KEY-----") ? decoded : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
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
    record InstallationToken(String token, String expiresAt, Map<String, String> permissions) {}
}
