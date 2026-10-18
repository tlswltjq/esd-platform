package com.stove.studio.core.domain.build;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
        name = "studio_build",
        uniqueConstraints = @UniqueConstraint(columnNames = {"projectId", "version"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Build extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 100)
    private String version;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 1000)
    private String storagePath;

    @Column(nullable = false)
    private long fileSize;

    @Column(nullable = false, length = 64)
    private String checksum;

    public static Build create(
            Long projectId,
            String version,
            String fileName,
            String path,
            long size,
            String checksum) {
        Build b = new Build();
        b.projectId = projectId;
        b.version = version;
        b.fileName = fileName;
        b.storagePath = path;
        b.fileSize = size;
        b.checksum = checksum;
        return b;
    }
}
