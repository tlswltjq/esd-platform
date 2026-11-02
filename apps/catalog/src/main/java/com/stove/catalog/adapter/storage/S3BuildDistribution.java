package com.stove.catalog.adapter.storage;

import com.stove.catalog.core.domain.DownloadBuildReference;
import com.stove.catalog.core.port.BuildDistribution;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

@Slf4j
public class S3BuildDistribution implements BuildDistribution {
    public static final String RESTRICTION_TAG = "stove-download-blocked";
    private final S3Client tagger;
    private final S3Client reader;
    private final String bucket;

    public S3BuildDistribution(S3Client tagger, S3Client reader, String bucket) {
        this.tagger = tagger;
        this.reader = reader;
        this.bucket = bucket;
    }
    @Override public void requireConfigured() { }
    @Override public void apply(List<DownloadBuildReference> builds, boolean restricted) {
        // Validate every reference before any external mutation.
        List<String> keys = builds.stream().map(this::key).distinct().toList();
        for (String key : keys) {
            List<Tag> tags = new ArrayList<>(tagger.getObjectTagging(GetObjectTaggingRequest.builder().bucket(bucket).key(key).build()).tagSet());
            tags.removeIf(tag -> RESTRICTION_TAG.equals(tag.key()));
            if (restricted) tags.add(Tag.builder().key(RESTRICTION_TAG).value("true").build());
            if (tags.size() > 10) throw new IllegalStateException("No object tag capacity");
            tagger.putObjectTagging(PutObjectTaggingRequest.builder().bucket(bucket).key(key)
                    .tagging(Tagging.builder().tagSet(tags).build()).build());
            List<Tag> actual = tagger.getObjectTagging(GetObjectTaggingRequest.builder().bucket(bucket).key(key).build()).tagSet();
            boolean tagged = actual.stream().anyMatch(tag -> RESTRICTION_TAG.equals(tag.key()) && "true".equals(tag.value()));
            if (tagged != restricted) throw new IllegalStateException("Object restriction was not applied");
            verifyRead(key, restricted);
        }
    }
    private String key(DownloadBuildReference build) {
        String prefix = "s3://" + bucket + "/";
        if (!build.storagePath().startsWith(prefix) || build.storagePath().length() == prefix.length())
            throw new IllegalArgumentException("Unexpected build bucket");
        String key = build.storagePath().substring(prefix.length());
        if (!key.startsWith("projects/" + build.projectId() + "/"))
            throw new IllegalArgumentException("Build is outside the owning project");
        return key;
    }
    private void verifyRead(String key, boolean restricted) {
        try {
            ResponseBytes<GetObjectResponse> response = reader.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key).range("bytes=0-0").build());
            if (restricted || response.asByteArray().length != 1) throw new IllegalStateException("Storage read restriction mismatch");
        } catch (S3Exception failure) {
            log.debug("Distribution read probe: status={} code={} restricted={}", failure.statusCode(), failure.awsErrorDetails().errorCode(), restricted);
            if (!restricted || failure.statusCode() != 403 || !"AccessDenied".equals(failure.awsErrorDetails().errorCode())) throw failure;
        }
    }
    @PreDestroy public void close() { tagger.close(); reader.close(); }
}
