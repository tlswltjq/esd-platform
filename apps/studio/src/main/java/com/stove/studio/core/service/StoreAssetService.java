package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.studio.core.domain.StoreAsset;
import com.stove.studio.core.port.BuildStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 공개 URL은 고정하고, 원본은 비공개 S3 버킷에 보관한다. */
@Service
public class StoreAssetService {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private final GameProjectService projectService;
    private final BuildStorage storage;
    private final String publicBaseUrl;
    private final String bucket;

    public StoreAssetService(GameProjectService projectService, BuildStorage storage,
                             @Value("${stove.asset.public-base-url}") String publicBaseUrl,
                             @Value("${stove.storage.bucket}") String bucket) {
        this.projectService = projectService;
        this.storage = storage;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.bucket = bucket;
    }

    public StoreAsset upload(Long gameId, Long workspaceId, byte[] bytes) {
        projectService.requireOwned(gameId, workspaceId);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw invalid("이미지는 1바이트 이상 5MB 이하여야 합니다.");
        }
        String type = imageType(bytes);
        String assetId = UUID.randomUUID() + ("image/png".equals(type) ? ".png" : ".jpg");
        String path = storagePath(gameId, assetId);
        storage.putAsset(path, bytes, type);
        if (storage.head(path).size() != bytes.length) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미지 저장 크기를 확인할 수 없습니다.");
        }
        return new StoreAsset(assetId, publicUrl(gameId, assetId), type, bytes.length);
    }

    public void validatePublished(Long gameId, List<String> screenshots, String coverUrl,
                                  String iconUrl) {
        if (screenshots == null || screenshots.isEmpty() || coverUrl == null || coverUrl.isBlank()) {
            throw invalid("출시할 상점 페이지에는 업로드한 스크린샷과 커버가 필요합니다.");
        }
        screenshots.forEach(url -> requireOwnedUpload(gameId, url));
        requireOwnedUpload(gameId, coverUrl);
        if (iconUrl != null && !iconUrl.isBlank()) requireOwnedUpload(gameId, iconUrl);
    }

    public String downloadUrl(Long gameId, String assetId) {
        validateAssetId(assetId);
        String path = storagePath(gameId, assetId);
        try {
            if (storage.head(path).size() <= 0) throw new IllegalStateException("empty asset");
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "assetId=" + assetId);
        }
        return storage.presignDownload(path);
    }

    private void requireOwnedUpload(Long gameId, String url) {
        String prefix = publicBaseUrl + "/api/v1/studio/assets/" + gameId + "/";
        if (url == null || !url.startsWith(prefix)) throw invalid("해당 프로젝트에 업로드한 이미지 URL이 필요합니다.");
        String assetId = url.substring(prefix.length());
        validateAssetId(assetId);
        try {
            if (storage.head(storagePath(gameId, assetId)).size() <= 0) {
                throw new IllegalStateException("empty asset");
            }
        } catch (RuntimeException exception) {
            throw invalid("이미지 업로드를 확인할 수 없습니다: " + assetId);
        }
    }

    private String publicUrl(Long gameId, String assetId) {
        return publicBaseUrl + "/api/v1/studio/assets/" + gameId + "/" + assetId;
    }

    private String storagePath(Long gameId, String assetId) {
        return "s3://" + bucket + "/store-assets/" + gameId + "/" + assetId;
    }

    private void validateAssetId(String assetId) {
        if (assetId == null || !assetId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg)")) {
            throw invalid("이미지 ID가 올바르지 않습니다.");
        }
    }

    private String imageType(byte[] bytes) {
        String type;
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G') {
            type = "image/png";
        } else if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            type = "image/jpeg";
        } else {
            throw invalid("PNG 또는 JPEG 이미지만 업로드할 수 있습니다.");
        }
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid("이미지 형식을 읽을 수 없습니다.");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
                    throw invalid("이미지 크기는 8192픽셀 이하여야 합니다.");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw invalid("이미지가 손상되었습니다.");
        }
        return type;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

}
