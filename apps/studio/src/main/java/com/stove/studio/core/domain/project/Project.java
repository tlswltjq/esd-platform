package com.stove.studio.core.domain.project;

import com.stove.common.jpa.BaseTimeEntity;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Entity
@Getter
@Table(name = "studio_project")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String productCode;

    @Column(nullable = false, length = 100)
    private String ownerSubject;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 500)
    private String shortDescription;

    @Column(length = 1000)
    private String thumbnailUrl;

    @Column(length = 1000)
    private String videoUrl;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(nullable = false)
    private long price;

    private Long currentBuildId;

    @Column(nullable = false)
    private long contentVersion;

    private Long currentReviewRequestId;
    private Long currentReleaseRequestId;
    @Version private long entityVersion;

    public static Project create(String code, String title, String subject) {
        Project p = new Project();
        p.productCode = code;
        p.title = title;
        p.ownerSubject = subject;
        p.shortDescription = "";
        p.content = "";
        p.contentVersion = 1;
        return p;
    }

    public void requireOwner(String subject) {
        if (!ownerSubject.equals(subject)) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    public void requireVersion(long expected) {
        if (contentVersion != expected)
            throw new BusinessException(ErrorCode.CONFLICT, "작업본이 변경되었습니다. 다시 조회해 주세요.");
    }

    public StorePage storePage() {
        return new StorePage(title, shortDescription, thumbnailUrl, videoUrl, content, price);
    }

    public void update(StorePage page, long expected) {
        requireVersion(expected);
        if (storePage().equals(page)) return;
        title = page.title();
        shortDescription = page.shortDescription();
        thumbnailUrl = page.thumbnailUrl();
        videoUrl = page.videoUrl();
        content = page.content();
        price = page.price();
        changed();
    }

    public void selectBuild(Long buildId, long expected) {
        requireVersion(expected);
        if (!Objects.equals(currentBuildId, buildId)) {
            currentBuildId = buildId;
            changed();
        }
    }

    private void changed() {
        contentVersion++;
    }

    public void requireReady() {
        if (title.isBlank()
                || shortDescription.isBlank()
                || content.isBlank()
                || thumbnailUrl == null
                || thumbnailUrl.isBlank()
                || currentBuildId == null)
            throw new BusinessException(ErrorCode.CONFLICT, "제목·소개·내용·썸네일·빌드를 준비해 주세요.");
    }

    public void linkReviewRequest(Long id) {
        currentReviewRequestId = id;
    }

    public void linkReleaseRequest(Long id) {
        currentReleaseRequestId = id;
    }
}
