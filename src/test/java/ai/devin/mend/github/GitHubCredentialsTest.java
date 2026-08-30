package ai.devin.mend.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.devin.mend.config.MendProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

class GitHubCredentialsTest {

    private static final String INSTALLATION_TOKEN_JSON =
            """
            {"token":"ghs_installation","expires_at":"%s"}
            """;

    @Test
    void fallsBackToPersonalAccessTokenWhenNoAppConfigured() {
        MendProperties props = new MendProperties();
        props.getGithub().setToken("ghp_pat");
        GitHubCredentials credentials = new GitHubCredentials(RestClient.builder(), props);

        assertThat(credentials.isConfigured()).isTrue();
        assertThat(credentials.bearerToken()).isEqualTo("ghp_pat");
        assertThat(credentials.identity()).isEqualTo("token");
    }

    @Test
    void mintsInstallationTokenWithAnRs256JwtAndCachesIt() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        MendProperties props = new MendProperties();
        props.getGithub().getApp().setAppId("12345");
        props.getGithub().getApp().setInstallationId("67890");
        props.getGithub().getApp().setPrivateKey(pkcs8Pem(keyPair));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubCredentials credentials = new GitHubCredentials(builder, props);

        server.expect(requestTo("https://api.github.com/app/installations/67890/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jwtSignedBy(keyPair.getPublic(), "12345"))
                .andRespond(withSuccess(
                        INSTALLATION_TOKEN_JSON.formatted(Instant.now().plusSeconds(3600)),
                        MediaType.APPLICATION_JSON));

        assertThat(credentials.identity()).isEqualTo("github-app:12345");
        assertThat(credentials.bearerToken()).isEqualTo("ghs_installation");
        // A second call must reuse the cached token; the mock server allows only one request.
        assertThat(credentials.bearerToken()).isEqualTo("ghs_installation");
        server.verify();
    }

    @Test
    void acceptsAPkcs1PrivateKeyAsDownloadedFromGitHub() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        MendProperties props = new MendProperties();
        props.getGithub().getApp().setAppId("12345");
        props.getGithub().getApp().setInstallationId("67890");
        props.getGithub().getApp().setPrivateKey(pkcs1Pem(keyPair));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubCredentials credentials = new GitHubCredentials(builder, props);

        server.expect(requestTo("https://api.github.com/app/installations/67890/access_tokens"))
                .andExpect(jwtSignedBy(keyPair.getPublic(), "12345"))
                .andRespond(withSuccess(
                        INSTALLATION_TOKEN_JSON.formatted(Instant.now().plusSeconds(3600)),
                        MediaType.APPLICATION_JSON));

        assertThat(credentials.bearerToken()).isEqualTo("ghs_installation");
        server.verify();
    }

    @Test
    void acceptsAKeyWhoseNewlinesSurvivedOnlyAsEscapesAndTheQuotesItWasPastedWith() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String escaped = "\"" + pkcs8Pem(keyPair).replace("\n", "\\n") + "\"";

        assertThat(mints(escaped, keyPair)).isEqualTo("ghs_installation");
    }

    @Test
    void acceptsAPathToTheDownloadedPemAndTheWholeFileBase64Encoded(@TempDir Path dir) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path pem = Files.writeString(dir.resolve("mend.private-key.pem"), pkcs8Pem(keyPair));

        assertThat(mints(pem.toString(), keyPair)).isEqualTo("ghs_installation");
        assertThat(mints(
                        Base64.getEncoder()
                                .encodeToString(pkcs8Pem(keyPair).getBytes(StandardCharsets.UTF_8)),
                        keyPair))
                .isEqualTo("ghs_installation");
    }

    /**
     * A {@code .env} is read literally, so {@code GITHUB_APP_PRIVATE_KEY="$(cat key.pem)"} reaches
     * menD unexpanded. That used to surface as "Illegal base64 character 24".
     */
    @Test
    void namesTheVariableAndTheUnexpandedSubstitutionInsteadOfComplainingAboutBase64() {
        MendProperties props = new MendProperties();
        props.getGithub().getApp().setAppId("12345");
        props.getGithub().getApp().setInstallationId("67890");
        props.getGithub().getApp().setPrivateKey("$(cat mend.private-key.pem)");
        GitHubCredentials credentials = new GitHubCredentials(RestClient.builder(), props);

        assertThatThrownBy(credentials::bearerToken)
                .isInstanceOf(GitHubCredentialsException.class)
                .hasMessageContaining("GITHUB_APP_PRIVATE_KEY")
                .hasMessageContaining("unexpanded shell substitution")
                .hasMessageNotContaining("base64 character");
    }

    @Test
    void rejectsAKeyThatIsNotAPemWithoutRepeatingTheValue() {
        MendProperties props = new MendProperties();
        props.getGithub().getApp().setAppId("12345");
        props.getGithub().getApp().setInstallationId("67890");
        props.getGithub().getApp().setPrivateKey("ghp_not_a_key_at_all");
        GitHubCredentials credentials = new GitHubCredentials(RestClient.builder(), props);

        assertThatThrownBy(credentials::bearerToken)
                .isInstanceOf(GitHubCredentialsException.class)
                .hasMessageContaining("no -----BEGIN PRIVATE KEY----- header")
                .hasMessageNotContaining("ghp_not_a_key_at_all");
    }

    /** Mints one installation token with the configured key, asserting the JWT it signs. */
    private static String mints(String configuredKey, KeyPair keyPair) {
        MendProperties props = new MendProperties();
        props.getGithub().getApp().setAppId("12345");
        props.getGithub().getApp().setInstallationId("67890");
        props.getGithub().getApp().setPrivateKey(configuredKey);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubCredentials credentials = new GitHubCredentials(builder, props);
        server.expect(requestTo("https://api.github.com/app/installations/67890/access_tokens"))
                .andExpect(jwtSignedBy(keyPair.getPublic(), "12345"))
                .andRespond(withSuccess(
                        INSTALLATION_TOKEN_JSON.formatted(Instant.now().plusSeconds(3600)),
                        MediaType.APPLICATION_JSON));

        String token = credentials.bearerToken();
        server.verify();
        return token;
    }

    /** Verifies the request carries a JWT this key actually signed, issued by this app. */
    private static RequestMatcher jwtSignedBy(PublicKey publicKey, String appId) {
        return request -> {
            String authorization = request.getHeaders().getFirst("Authorization");
            assertThat(authorization).startsWith("Bearer ");
            String jwt = authorization.substring("Bearer ".length());
            String[] parts = jwt.split("\\.");
            assertThat(parts).hasSize(3);
            assertThat(decode(parts[0])).contains("\"alg\":\"RS256\"");
            assertThat(decode(parts[1])).contains("\"iss\":\"" + appId + "\"");
            assertThat(signatureValid(publicKey, parts)).as("JWT signature").isTrue();
        };
    }

    private static boolean signatureValid(PublicKey publicKey, String[] parts) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String decode(String segment) {
        return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
    }

    private static String pkcs8Pem(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    /** Re-encodes the JDK's PKCS#8 key as the PKCS#1 body GitHub hands out. */
    private static String pkcs1Pem(KeyPair keyPair) throws Exception {
        byte[] pkcs8 = keyPair.getPrivate().getEncoded();
        java.security.interfaces.RSAPrivateCrtKey key = (java.security.interfaces.RSAPrivateCrtKey)
                java.security.KeyFactory.getInstance("RSA")
                        .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pkcs8));
        byte[] pkcs1 = rsaPrivateKeyDer(key);
        return "-----BEGIN RSA PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pkcs1)
                + "\n-----END RSA PRIVATE KEY-----\n";
    }

    private static byte[] rsaPrivateKeyDer(java.security.interfaces.RSAPrivateCrtKey key) {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes(integer(java.math.BigInteger.ZERO));
        body.writeBytes(integer(key.getModulus()));
        body.writeBytes(integer(key.getPublicExponent()));
        body.writeBytes(integer(key.getPrivateExponent()));
        body.writeBytes(integer(key.getPrimeP()));
        body.writeBytes(integer(key.getPrimeQ()));
        body.writeBytes(integer(key.getPrimeExponentP()));
        body.writeBytes(integer(key.getPrimeExponentQ()));
        body.writeBytes(integer(key.getCrtCoefficient()));
        return tagged((byte) 0x30, body.toByteArray());
    }

    private static byte[] integer(java.math.BigInteger value) {
        return tagged((byte) 0x02, value.toByteArray());
    }

    private static byte[] tagged(byte tag, byte[] content) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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
}
