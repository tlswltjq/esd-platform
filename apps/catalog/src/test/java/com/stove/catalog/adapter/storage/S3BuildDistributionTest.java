package com.stove.catalog.adapter.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.catalog.core.domain.DownloadBuildReference;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

class S3BuildDistributionTest {
    @Test void anotherBucketOrProjectsFileIsRejectedBeforeEveryExternalMutation() {
        S3Client tagger = mock(S3Client.class);
        S3Client reader = mock(S3Client.class);
        S3BuildDistribution distribution = new S3BuildDistribution(tagger, reader, "stove-builds");
        for (String path : List.of("s3://another/projects/1/file.zip", "s3://stove-builds/projects/2/file.zip")) {
            assertThatThrownBy(() -> distribution.apply(List.of(new DownloadBuildReference(1, "s3://stove-builds/projects/1/valid.zip"),
                    new DownloadBuildReference(1, path)), true)).isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(tagger, reader);
        }
    }
}
