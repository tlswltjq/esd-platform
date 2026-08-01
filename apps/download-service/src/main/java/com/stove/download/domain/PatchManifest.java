package com.stove.download.domain;

import com.stove.common.event.payload.BuildUploadedEvent;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 버전별 패치 매니페스트. 문서 ID를 {@code productCode:version} 으로 고정해
 * BuildUploaded 이벤트를 몇 번 받아도 같은 문서를 덮어쓰도록(멱등) 만든다.
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

    private String version;

    private long fileSize;

    private String checksum;

    private String storagePath;

    private Instant releasedAt;

    public static String documentId(String productCode, String version) {
        return productCode + ":" + version;
    }

    public static PatchManifest from(BuildUploadedEvent event) {
        return PatchManifest.builder()
                .id(documentId(event.productCode(), event.version()))
                .productCode(event.productCode())
                .gameId(event.gameId())
                .version(event.version())
                .fileSize(event.fileSize())
                .checksum(event.checksum())
                .storagePath(event.storagePath())
                .releasedAt(event.occurredAt())
                .build();
    }
}
