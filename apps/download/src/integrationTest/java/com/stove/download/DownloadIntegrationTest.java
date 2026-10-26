package com.stove.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.stove.common.test.OpenApiSnapshot;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.download.adapter.storage.S3BuildStorage;
import com.stove.download.core.domain.PublishedBuild;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DownloadIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final byte[] BYTES = "immutable full ZIP fixture".getBytes(StandardCharsets.UTF_8);
    static final String BUCKET = "stove-builds";
    static final String READER = "download-reader";
    static final String SECRET = "read-only-test-password";
    static final String ISSUER = "https://download-test.example";
    static final RSAKey KEY;
    static final HttpServer UPSTREAM;
    static final String OWNER;
    static final AtomicInteger LICENSE_CALLS = new AtomicInteger();
    static final AtomicInteger CATALOG_CALLS = new AtomicInteger();
    static volatile int licenseStatus = 200;
    static volatile String licenseAccessState = "ACTIVE";
    static volatile int catalogStatus = 200;
    static volatile long productId = 1;
    static volatile String storagePath = "s3://stove-builds/game.zip";
    static volatile String seenAuthorization;
    static volatile String seenServiceKey;
    @Container static final MinIOContainer MINIO = new MinIOContainer(InfraContainers.minioImage())
            .withUserName("stove").withPassword("stove1234");

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("download-test").generate();
            OWNER = token("buyer", ISSUER, "esd-api", "MEMBER", Instant.now().plusSeconds(3600));
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                int status;
                String body;
                if (path.equals("/jwks")) {
                    status = 200; body = new JWKSet(KEY.toPublicJWK()).toString();
                } else if (path.startsWith("/api/v1/library/")) {
                    LICENSE_CALLS.incrementAndGet(); seenAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                    if (exchange.getRequestHeaders().getFirst("X-Download-Service-Key") != null) throw new AssertionError("Service key leaked to License");
                    status = ("Bearer " + OWNER).equals(seenAuthorization) ? licenseStatus : 404;
                    ObjectNode ownership = JSON.createObjectNode().put("productId", 1).put("productCode", "game");
                    if (licenseAccessState != null) ownership.put("status", licenseAccessState);
                    body = JSON.writeValueAsString(JSON.createObjectNode().put("success", true).set("data", ownership));
                } else {
                    CATALOG_CALLS.incrementAndGet(); seenServiceKey = exchange.getRequestHeaders().getFirst("X-Download-Service-Key");
                    if (exchange.getRequestHeaders().getFirst("Authorization") != null) throw new AssertionError("User token leaked to Catalog");
                    status = "internal-test-key".equals(seenServiceKey) ? catalogStatus : 401;
                    ObjectNode data = JSON.createObjectNode().put("productId", productId).put("productCode", "game")
                            .put("releaseRequestId", 2).put("contentVersion", 3).put("buildId", 4).put("version", "v1")
                            .put("fileSize", BYTES.length).put("checksum", checksum()).put("storagePath", storagePath);
                    body = JSON.writeValueAsString(JSON.createObjectNode().put("success", true).set("data", data));
                }
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length); exchange.getResponseBody().write(response); exchange.close();
            });
            UPSTREAM.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        String base = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        p.add("stove.download.license-base-url", () -> base);
        p.add("stove.download.catalog-base-url", () -> base);
        p.add("stove.download.catalog-service-key", () -> "internal-test-key");
        p.add("stove.download-storage.endpoint", MINIO::getS3URL);
        p.add("stove.download-storage.presign-endpoint", MINIO::getS3URL);
        p.add("stove.download-storage.bucket", () -> BUCKET);
        p.add("stove.download-storage.access-key", () -> READER);
        p.add("stove.download-storage.secret-key", () -> SECRET);
        p.add("stove.download-storage.presign-ttl", () -> "PT2S");
        p.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        p.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> base + "/jwks");
    }

    @LocalServerPort int port;
    @Autowired S3BuildStorage storage;
    static S3Client client(String user, String password) {
        return S3Client.builder().endpointOverride(URI.create(MINIO.getS3URL())).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(user, password)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }
    @BeforeAll static void prepare() throws Exception {
        try (S3Client admin = client("stove", "stove1234")) { admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()); }
        assertThat(MINIO.execInContainer("mc", "alias", "set", "admin", "http://localhost:9000", "stove", "stove1234").getExitCode()).isZero();
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::stove-builds/*\"]},{\"Effect\":\"Allow\",\"Action\":[\"s3:ListBucket\"],\"Resource\":[\"arn:aws:s3:::stove-builds\"]}]}";
        MINIO.copyFileToContainer(Transferable.of(policy), "/tmp/download-policy.json");
        assertThat(MINIO.execInContainer("mc", "admin", "policy", "create", "admin", "download-read", "/tmp/download-policy.json").getExitCode()).isZero();
        assertThat(MINIO.execInContainer("mc", "admin", "user", "add", "admin", READER, SECRET).getExitCode()).isZero();
        assertThat(MINIO.execInContainer("mc", "admin", "policy", "attach", "admin", "download-read", "--user", READER).getExitCode()).isZero();
    }
    @BeforeEach void reset() {
        licenseStatus = 200; licenseAccessState = "ACTIVE"; catalogStatus = 200; productId = 1; storagePath = "s3://stove-builds/game.zip";
        LICENSE_CALLS.set(0); CATALOG_CALLS.set(0);
        try (S3Client admin = client("stove", "stove1234")) {
            admin.putObject(PutObjectRequest.builder().bucket(BUCKET).key("game.zip").build(), RequestBody.fromBytes(BYTES));
        }
    }
    @Test void suspendedLibraryEntryDoesNotAuthorizeANewUrlAndOldUrlIsIndependent() throws Exception {
        JsonNode ticket = JSON.readTree(get("/api/v1/downloads/game/ticket", OWNER).body()).path("data");
        String originalUrl = ticket.path("downloadUrl").asText();
        licenseAccessState = "SUSPENDED";
        CATALOG_CALLS.set(0);
        assertThat(get("/api/v1/downloads/game/ticket", OWNER).statusCode()).isEqualTo(403);
        assertThat(CATALOG_CALLS.get()).isZero();
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(originalUrl)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    }

    @Test void missingOrUnknownAccessStateCannotReachCatalogOrStorage() throws Exception {
        for (String state : new String[]{null, "UNKNOWN", "active"}) {
            licenseAccessState = state;
            assertThat(get("/api/v1/downloads/game/ticket", OWNER).statusCode()).isEqualTo(503);
            assertThat(CATALOG_CALLS.get()).isZero();
        }
    }

    @AfterAll static void stop() { UPSTREAM.stop(0); }
    static String token(String subject, String issuer, String audience, String role, Instant expires) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(subject).issuer(issuer).audience(List.of(audience))
                .claim("roles", List.of(role)).issuedAt(Instant.now().minusSeconds(600)).expiresAt(expires).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
    static String checksum() { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES)); } catch (Exception e) { throw new IllegalStateException(e); } }
    HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ticket() throws Exception {
        HttpResponse<String> response = get("/api/v1/downloads/game/ticket", OWNER);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode ticket = JSON.readTree(response.body()).path("data");
        assertThat(ticket.size()).isEqualTo(6); return ticket;
    }
    PublishedBuild build(String path, long size) {
        return new PublishedBuild(1, "game", 2, 3, 4, "v1", size, checksum(), path);
    }

    @Test void authenticatesRealJwtAndChecksOwnershipBeforePublication() throws Exception {
        assertThat(get("/api/v1/downloads/game/ticket", null).statusCode()).isEqualTo(401);
        for (String invalid : new String[]{token("buyer", "https://wrong", "esd-api", "MEMBER", Instant.now().plusSeconds(300)),
                token("buyer", ISSUER, "other-api", "MEMBER", Instant.now().plusSeconds(300)),
                token("buyer", ISSUER, "esd-api", "MEMBER", Instant.now().minusSeconds(120)), OWNER + "broken"})
            assertThat(get("/api/v1/downloads/game/ticket", invalid).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/downloads/game/ticket", token("reviewer", ISSUER, "esd-api", "REVIEWER", Instant.now().plusSeconds(300))).statusCode()).isEqualTo(403);
        assertThat(LICENSE_CALLS.get()).isZero();
        assertThat(get("/api/v1/downloads/game/ticket", token("other", ISSUER, "esd-api", "MEMBER", Instant.now().plusSeconds(300))).statusCode()).isEqualTo(403);
        assertThat(CATALOG_CALLS.get()).isZero();
        ticket(); assertThat(seenAuthorization).isEqualTo("Bearer " + OWNER); assertThat(seenServiceKey).isEqualTo("internal-test-key");
    }
    @Test void actualSignedDownloadMatchesBytesSizeAndChecksumAndCannotBeTampered() throws Exception {
        JsonNode ticket = ticket();
        HttpResponse<byte[]> file = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(ticket.path("downloadUrl").asText())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(file.statusCode()).isEqualTo(200); assertThat(file.body()).isEqualTo(BYTES);
        assertThat(ticket.path("fileSize").asLong()).isEqualTo(file.body().length); assertThat(ticket.path("checksum").asText()).isEqualTo(checksum());
        String url = ticket.path("downloadUrl").asText();
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url.substring(0, url.indexOf('?')))).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url.replace("game.zip", "other.zip"))).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
    }
    @Test void actualExpirationRejectsNewGetsAndNewIssuanceStillWorks() throws Exception {
        JsonNode ticket = ticket(); Instant expiry = Instant.parse(ticket.path("expiresAt").asText());
        Thread.sleep(Math.max(1, Duration.between(Instant.now(), expiry.plusSeconds(1)).toMillis()));
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(ticket.path("downloadUrl").asText())).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        assertThat(Instant.parse(ticket().path("expiresAt").asText())).isAfter(expiry);
    }
    @Test void refusesQuerySelectionAndOldApi() throws Exception {
        for (String query : List.of("platform=windows", "architecture=x64", "fromVersion=1", "memberId=other", "storagePath=other"))
            assertThat(get("/api/v1/downloads/game/ticket?" + query, OWNER).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/downloads/-invalid/ticket", OWNER).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/downloads/game/manifests", OWNER).statusCode()).isEqualTo(403);
        assertThat(LICENSE_CALLS.get()).isZero();
    }
    @Test void upstreamFailuresAreNotOwnershipDenialsAndProductMismatchCannotSign() throws Exception {
        licenseStatus = 500; assertThat(get("/api/v1/downloads/game/ticket", OWNER).statusCode()).isEqualTo(503); assertThat(CATALOG_CALLS.get()).isZero();
        licenseStatus = 404; assertThat(get("/api/v1/downloads/game/ticket", OWNER).statusCode()).isEqualTo(403);
        licenseStatus = 200;
        for (int status : new int[]{401, 403, 500}) { catalogStatus = status; assertThat(get("/api/v1/downloads/game/ticket", OWNER).statusCode()).isEqualTo(503); }
        catalogStatus = 404; assertThat(get("/api/v1/downloads/game/ticket", OWNER).statusCode()).isEqualTo(404);
        catalogStatus = 200; productId = 2; assertThat(get("/api/v1/downloads/game/ticket", OWNER).statusCode()).isEqualTo(503);
    }
    @Test void rejectsMissingChangedAndInvalidStorageObjects() throws Exception {
        storagePath = "s3://stove-builds/missing.zip"; assertThat(get("/api/v1/downloads/game/ticket", OWNER).statusCode()).isEqualTo(409);
        for (String invalid : new String[]{"s3://other/game.zip", "s3://stove-builds/", "http://untrusted/game.zip"}) {
            storagePath = invalid; assertThat(get("/api/v1/downloads/game/ticket", OWNER).statusCode()).isEqualTo(503);
        }
        storagePath = "s3://stove-builds/game.zip";
        try (S3Client admin = client("stove", "stove1234")) { admin.putObject(PutObjectRequest.builder().bucket(BUCKET).key("game.zip").build(), RequestBody.fromBytes(new byte[]{1})); }
        assertThat(get("/api/v1/downloads/game/ticket", OWNER).statusCode()).isEqualTo(409);
    }
    @Test void storageAccountCanReadButCannotWriteOrDelete() {
        try (S3Client reader = client(READER, SECRET)) {
            assertThat(reader.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key("game.zip").build()).asByteArray()).isEqualTo(BYTES);
            assertThatThrownBy(() -> reader.putObject(PutObjectRequest.builder().bucket(BUCKET).key("forbidden.zip").build(), RequestBody.fromBytes(BYTES))).isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
            assertThatThrownBy(() -> reader.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key("game.zip").build())).isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
        }
    }
    @Test void committedOpenApiHasOneEndpointAndMatchesSnapshot() throws Exception {
        JsonNode docs = JSON.readTree(get("/v3/api-docs", null).body());
        assertThat(docs.path("paths").size()).isEqualTo(1);
        assertThat(docs.path("paths").path("/api/v1/downloads/{productCode}/ticket").path("get").path("parameters").size()).isEqualTo(1);
        OpenApiSnapshot.verify(port, "download");
    }
}
