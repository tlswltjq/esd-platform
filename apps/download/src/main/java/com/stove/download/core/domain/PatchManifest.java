package com.stove.download.core.domain;

import com.stove.common.event.payload.ReleasePublishedEvent;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 공개 릴리스별 패치 매니페스트. 업로드 완료가 아니라 ReleasePublished만 소비한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "patch_manifest")
public class PatchManifest {

    @Id
    private String id;

    @Indexed
    private String productCode;

    private Long gameId;

    private Long releaseId;

    private Long buildId;

    private Long metadataRevision;

    private String version;

    private long fileSize;

    private String checksum;

    private String storagePath;

    private Instant releasedAt;

    public static String documentId(String productCode, Long releaseId) {
        return productCode + ":release:" + releaseId;
    }

    public static PatchManifest from(ReleasePublishedEvent event) {
        return PatchManifest.builder()
                .id(documentId(event.productCode(), event.releaseId()))
                .productCode(event.productCode())
                .gameId(event.gameId())
                .releaseId(event.releaseId())
                .buildId(event.buildId())
                .metadataRevision(event.metadataRevision())
                .version(event.productVersion())
                .fileSize(event.fileSize())
                .checksum(event.checksum())
                .storagePath(event.storagePath())
                .releasedAt(event.occurredAt())
                .build();
    }
}
