package com.stove.catalog;

import static com.stove.catalog.CatalogFixtures.envelope;
import static com.stove.catalog.CatalogFixtures.event;
import static com.stove.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.api.listener.ReleaseSubmissionMapper;
import com.stove.catalog.core.domain.CatalogProductRepository;
import com.stove.catalog.core.domain.CatalogReleaseRepository;
import com.stove.catalog.core.port.BuildDistribution;
import com.stove.catalog.core.domain.DownloadRestrictionChange;
import com.stove.catalog.core.domain.SellerSaleChange;
import com.stove.catalog.core.domain.PlatformSaleRestriction;
import com.stove.catalog.core.service.ChangeSellerSaleStatus;
import com.stove.catalog.core.service.ChangePlatformSaleRestriction;
import com.stove.catalog.core.service.ChangeDownloadRestriction;
import com.stove.catalog.core.service.PublishProjectRelease;
import com.stove.catalog.core.service.ReadDownloadRestriction;
import com.stove.catalog.core.service.ReconcileDownloadStorage;
import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.web.error.BusinessException;
import java.net.URI;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Testcontainers
class CatalogDownloadRestrictionIntegrationTest extends CatalogIntegrationTestBase {
    static final String BUCKET = "stove-builds";
    static final String READER = "restriction-reader";
    static final String TAGGER = "restriction-tagger";
    static final String SECRET = "isolated-restriction-password";
    static final byte[] FILE = "immutable ZIP fixture".getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2030-11-02T01:00:00Z");
    @Container static final MinIOContainer MINIO = new MinIOContainer(InfraContainers.minioImage())
            .withUserName("stove").withPassword("stove1234");
    static S3Client root;
    static S3Presigner signer;
    @Autowired PublishProjectRelease publish;
    @Autowired ChangeSellerSaleStatus seller;
    @Autowired ChangePlatformSaleRestriction platform;
    @Autowired ChangeDownloadRestriction change;
    @Autowired ReadDownloadRestriction read;
    @Autowired ReconcileDownloadStorage reconcile;
    @Autowired CatalogProductRepository products;
    @Autowired CatalogReleaseRepository releases;
    @Autowired BuildDistribution distribution;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean Clock clock;
    @LocalServerPort int port;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("stove.catalog.download-service-key", () -> "restriction-service");
        p.add("stove.catalog.download-storage.enabled", () -> true);
        p.add("stove.catalog.download-storage.worker-enabled", () -> false);
        p.add("stove.catalog.download-storage.endpoint", MINIO::getS3URL);
        p.add("stove.catalog.download-storage.bucket", () -> BUCKET);
        p.add("stove.catalog.download-storage.access-key", () -> TAGGER);
        p.add("stove.catalog.download-storage.secret-key", () -> SECRET);
        p.add("stove.catalog.download-storage.reader-access-key", () -> READER);
        p.add("stove.catalog.download-storage.reader-secret-key", () -> SECRET);
    }
    static S3Client client(String user, String secret) {
        return S3Client.builder().endpointOverride(URI.create(MINIO.getS3URL())).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(user, secret)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }
    @BeforeAll static void storage() throws Exception {
        root = client("stove", "stove1234");
        root.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        check(MINIO.execInContainer("mc", "alias", "set", "admin", "http://localhost:9000", "stove", "stove1234").getExitCode());
        account(READER, "s3:GetObject");
        account(TAGGER, "s3:GetObjectTagging", "s3:PutObjectTagging");
        signer = S3Presigner.builder().endpointOverride(URI.create(MINIO.getS3URL())).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(READER, SECRET)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        policy();
    }
    static void account(String user, String... actions) throws Exception {
        String json = TAGGER.equals(user)
                ? Files.readString(Path.of("../../infra/minio/policies/catalog-download-tagger.json"))
                : new ObjectMapper().writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(
                        Map.of("Effect", "Allow", "Action", List.of(actions), "Resource", List.of("arn:aws:s3:::" + BUCKET + "/*")))));
        MINIO.copyFileToContainer(Transferable.of(json), "/tmp/" + user + ".json");
        check(MINIO.execInContainer("mc", "admin", "policy", "create", "admin", user, "/tmp/" + user + ".json").getExitCode());
        check(MINIO.execInContainer("mc", "admin", "user", "add", "admin", user, SECRET).getExitCode());
        check(MINIO.execInContainer("mc", "admin", "policy", "attach", "admin", user, "--user", user).getExitCode());
    }
    static void check(int code) { assertThat(code).isZero(); }
    static void policy() {
        try { readerPolicy(Files.readString(Path.of("../../infra/minio/policies/download-reader.json"))); }
        catch (IOException failure) { throw new IllegalStateException("Missing restriction policy fixture", failure); }
    }
    static void readerPolicy(String json) {
        try {
            MINIO.copyFileToContainer(Transferable.of(json), "/tmp/reader-current.json");
            check(MINIO.execInContainer("mc", "admin", "policy", "create", "admin", READER, "/tmp/reader-current.json").getExitCode());
        } catch (Exception failure) { throw new IllegalStateException("Failed to prepare isolated reader policy", failure); }
    }
    @AfterAll static void close() { if (signer != null) signer.close(); if (root != null) root.close(); }
    @BeforeEach void tokens() {
        when(clock.instant()).thenReturn(NOW);
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String token = call.getArgument(0, String.class);
            return Jwt.withTokenValue(token).header("alg", "RS256").subject(token)
                    .claim("roles", List.of("admin".equals(token) ? "ADMIN" : "CREATOR"))
                    .issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(300)).build();
        });
        policy();
    }
    String publish(long project, long version) {
        ProjectReleaseRequestedEvent template = event(project, version);
        ProjectSnapshot s = template.snapshot();
        String key = "projects/" + project + "/" + version + ".zip";
        root.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).tagging("source=studio").build(), RequestBody.fromBytes(FILE));
        ProjectSnapshot snapshot = new ProjectSnapshot(s.projectId(), s.productCode(), s.ownerSubject(), s.contentVersion(),
                s.title(), s.shortDescription(), s.thumbnailUrl(), s.videoUrl(), s.content(), s.price(), s.currency(), s.buildId(),
                s.version(), FILE.length, s.checksum(), "s3://" + BUCKET + "/" + key);
        ProjectReleaseRequestedEvent event = ProjectReleaseRequestedEvent.of(template.releaseRequestId(), template.reviewRequestId(), snapshot);
        publish.execute(ReleaseSubmissionMapper.from(envelope(event), event));
        return s.productCode();
    }
    String url(long project, long version) {
        return signer.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(5))
                .getObjectRequest(GetObjectRequest.builder().bucket(BUCKET).key("projects/" + project + "/" + version + ".zip").build())
                .build()).url().toString();
    }
    int get(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    record Response(int status, JsonNode body, String cache) {}
    Response http(String code, String token, Object body) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port)
                .method(body == null ? HttpMethod.GET : HttpMethod.PUT)
                .uri("/api/v1/admin/products/{code}/downloads/restriction", code);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json").body(body);
        return request.exchange((r, s) -> new Response(s.getStatusCode().value(), mapper.readTree(s.getBody()), s.getHeaders().getCacheControl()));
    }
    Map<String, Object> command(boolean restricted, long version) {
        return Map.of("restricted", restricted, "expectedRestrictionVersion", version, "reason", "file safety review");
    }
    int build(String code) {
        return RestClient.create("http://127.0.0.1:" + port).get().uri("/internal/v1/products/{code}/build", code)
                .header("X-Download-Service-Key", "restriction-service").exchange((r, s) -> s.getStatusCode().value());
    }
    @Test void newIssueGateAndEveryPreviouslyPublishedObjectAreBlockedAndExplicitlyRestored() throws Exception {
        long project = id();
        String code = publish(project, 1);
        String first = url(project, 1);
        publish(project, 2);
        String second = url(project, 2);
        seller.execute("creator", new SellerSaleChange(code, "STOPPED", 2));
        platform.execute("admin", new PlatformSaleRestriction(code, true, 3));
        assertThat(build(code)).isEqualTo(200);
        long control = id();
        publish(control, 1);
        String unrelated = url(control, 1);
        assertThat(get(first)).isEqualTo(200);
        assertThat(get(second)).isEqualTo(200);
        Response accepted = http(code, "admin", command(true, 1));
        assertThat(accepted.status()).isEqualTo(202);
        assertThat(accepted.cache()).isEqualTo("no-store");
        assertThat(accepted.body().path("data").path("storageStatus").asText()).isEqualTo("PENDING");
        assertThat(build(code)).isEqualTo(403);
        assertThat(get(first)).isEqualTo(200);
        reconcile.execute(code);
        assertThat(read.execute(code).storageStatus()).isEqualTo("APPLIED");
        assertThat(get(first)).isEqualTo(403);
        assertThat(get(second)).isEqualTo(403);
        assertThat(get(unrelated)).isEqualTo(200);
        publish(project, 3);
        assertThat(read.execute(code).restricted()).isTrue();
        assertThat(read.execute(code).restrictionVersion()).isEqualTo(2);
        assertThat(read.execute(code).storageStatus()).isEqualTo("PENDING");
        reconcile.execute(code);
        assertThat(get(url(project, 3))).isEqualTo(403);
        assertThat(http(code, "admin", command(false, 2)).status()).isEqualTo(202);
        assertThat(build(code)).isEqualTo(403);
        reconcile.execute(code);
        assertThat(build(code)).isEqualTo(200);
        assertThat(get(first)).isEqualTo(200);
        assertThat(get(second)).isEqualTo(200);
        assertThat(get(url(project, 3))).isEqualTo(200);
        assertThat(products.findByProductCode(code).orElseThrow().getSaleStatus()).isEqualTo("BLOCKED");
        assertThat(products.findByProductCode(code).orElseThrow().getSellerSaleStatus()).isEqualTo("STOPPED");
        byte[] restored = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(first)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
        assertThat(restored).isEqualTo(FILE);
        List<Tag> tags = root.getObjectTagging(GetObjectTaggingRequest.builder().bucket(BUCKET).key("projects/" + project + "/1.zip").build()).tagSet();
        assertThat(tags).containsExactly(Tag.builder().key("source").value("studio").build());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_download_change WHERE product_id=?", Integer.class,
                products.findByProductCode(code).orElseThrow().getId())).isEqualTo(2);
    }
    @Test void missingStoragePolicyIsAFailedApplicationAndDurableRetryConverges() throws Exception {
        long project = id();
        String code = publish(project, 1);
        change.execute("admin", new DownloadRestrictionChange(code, true, 1, "unsafe"));
        readerPolicy("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::stove-builds/*\"]}]}");
        try {
            reconcile.execute(code);
            assertThat(read.execute(code).storageStatus()).isEqualTo("FAILED");
            assertThat(build(code)).isEqualTo(403);
        } finally { policy(); }
        when(clock.instant()).thenReturn(NOW.plusSeconds(31));
        assertThat(reconcile.due()).contains(code);
        reconcile.execute(code);
        assertThat(read.execute(code).storageStatus()).isEqualTo("APPLIED");
        assertThat(get(url(project, 1))).isEqualTo(403);
    }
    @Test void failedUnblockKeepsTheGateAndAFreshWorkerLoadsTheDurableDesiredState() throws Exception {
        long project = id();
        String code = publish(project, 1);
        change.execute("admin", new DownloadRestrictionChange(code, true, 1, "unsafe"));
        reconcile.execute(code);
        change.execute("admin", new DownloadRestrictionChange(code, false, 2, "review completed"));
        readerPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":"*",
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::stove-builds/*"]}]}
                """);
        try {
            reconcile.execute(code);
            assertThat(read.execute(code).storageStatus()).isEqualTo("FAILED");
            assertThat(read.execute(code).restricted()).isFalse();
            assertThat(build(code)).isEqualTo(403);
        } finally { policy(); }
        when(clock.instant()).thenReturn(NOW.plusSeconds(31));
        ReconcileDownloadStorage freshWorker = new ReconcileDownloadStorage(products, releases, distribution, mapper, clock);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> freshWorker.execute(code));
        assertThat(read.execute(code).storageStatus()).isEqualTo("APPLIED");
        assertThat(build(code)).isEqualTo(200);
        assertThat(get(url(project, 1))).isEqualTo(200);
    }

    @Test void periodicRecheckingRepairsObjectStateDrift() throws Exception {
        long project = id();
        String code = publish(project, 1);
        change.execute("admin", new DownloadRestrictionChange(code, true, 1, "unsafe"));
        reconcile.execute(code);
        root.putObjectTagging(PutObjectTaggingRequest.builder().bucket(BUCKET).key("projects/" + project + "/1.zip")
                .tagging(Tagging.builder().tagSet(Tag.builder().key("source").value("studio").build()).build()).build());
        when(clock.instant()).thenReturn(NOW.plusSeconds(61));
        assertThat(reconcile.due()).contains(code);
        reconcile.execute(code);
        assertThat(read.execute(code).storageStatus()).isEqualTo("APPLIED");
        assertThat(get(url(project, 1))).isEqualTo(403);
    }

    @Test void rolesInputAndVersionProtectCommandsAndAuditFailureRollsBackTheGate() {
        String code = publish(id(), 1);
        assertThat(http(code, null, command(true, 1)).status()).isEqualTo(401);
        assertThat(http(code, "creator", command(true, 1)).status()).isEqualTo(403);
        assertThat(http(code, "admin", Map.of("expectedRestrictionVersion", 1, "reason", "unsafe")).status()).isEqualTo(400);
        assertThat(http(code, "admin", Map.of("restricted", true, "expectedRestrictionVersion", 1, "reason", " ")).status()).isEqualTo(400);
        try {
            jdbc.execute("ALTER TABLE catalog_download_change ADD CONSTRAINT reject_download_audit CHECK (product_id <> "
                    + products.findByProductCode(code).orElseThrow().getId() + ")");
            assertThatThrownBy(() -> change.execute("admin", new DownloadRestrictionChange(code, true, 1, "unsafe")))
                    .isInstanceOf(RuntimeException.class);
        } finally { jdbc.execute("ALTER TABLE catalog_download_change DROP CHECK reject_download_audit"); }
        assertThat(read.execute(code).restrictionVersion()).isEqualTo(1);
        assertThat(build(code)).isEqualTo(200);
        assertThat(http(code, "admin", command(true, 1)).status()).isEqualTo(202);
        assertThat(http(code, "admin", command(false, 1)).status()).isEqualTo(409);
        assertThat(http(code, "admin", null).body().path("data").path("restricted").asBoolean()).isTrue();
    }
    @Test void concurrentCommandsCannotOverwriteOneAnotherAndControllerCannotWriteOrReadFiles() throws Exception {
        String code = publish(id(), 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = pool.submit(() -> concurrent(code, start));
            Future<Integer> second = pool.submit(() -> concurrent(code, start));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(202, 409);
        } finally { pool.shutdownNow(); }
        try (S3Client tagger = client(TAGGER, SECRET); S3Client reader = client(READER, SECRET)) {
            assertThatThrownBy(() -> tagger.putObject(PutObjectRequest.builder().bucket(BUCKET).key("forbidden.zip").build(), RequestBody.fromBytes(FILE)))
                    .isInstanceOf(S3Exception.class);
            assertThatThrownBy(() -> tagger.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key("forbidden.zip").build()))
                    .isInstanceOf(S3Exception.class);
            assertThatThrownBy(() -> reader.putObject(PutObjectRequest.builder().bucket(BUCKET).key("forbidden.zip").build(), RequestBody.fromBytes(FILE)))
                    .isInstanceOf(S3Exception.class);
        }
    }
    int concurrent(String code, CountDownLatch start) throws Exception { start.await(); return http(code, "admin", command(true, 1)).status(); }
}
