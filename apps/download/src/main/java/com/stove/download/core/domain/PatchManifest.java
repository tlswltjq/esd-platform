package com.stove.download.core.domain;

import com.stove.common.event.payload.ReleasePublishedEvent;
import com.stove.common.event.payload.BuildVariant;
import java.time.Instant;
import java.util.List;
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

    /** 상품 코드와 릴리스 ID를 결합한 배포 문서 키. */
    @Id
    private String id;

    /** 서비스 간 상품을 연결하는 고유 코드. */
    @Indexed
    private String productCode;

    /** Studio 게임 프로젝트 식별자. */
    private Long gameId;

    /** 대상 릴리스 식별자. */
    private Long releaseId;

    /** 배포 파일을 나타내는 빌드 식별자. */
    private Long buildId;

    /** 상점 소개 자료 판본의 식별자. */
    private Long metadataRevision;

    /** 배포 파일의 제품 버전 문자열. */
    private String version;

    /** 배포 파일의 크기(바이트). */
    private long fileSize;

    /** 파일 무결성 검증에 사용하는 체크섬. */
    private String checksum;

    /** 파일이 보관된 객체 저장소 경로. */
    private String storagePath;

    /** 플랫폼·아키텍처별 배포 파일 목록. */
    private List<BuildVariant> buildVariants;

    /** 릴리스가 공개된 시각. */
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
                .buildVariants(event.buildVariants() == null ? List.of() : List.copyOf(event.buildVariants()))
                .releasedAt(event.occurredAt())
                .build();
    }
}
