package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.studio.core.domain.BuildStatus;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.StoredObjectInfo;
import com.stove.studio.core.port.BuildStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReleaseSmokeTestService {
    private final BuildStorage buildStorage;

    public void verify(GameBuild build) {
        if (build.getStatus() != BuildStatus.VALIDATED) {
            throw new BusinessException(ErrorCode.CONFLICT, "검증된 빌드만 smoke test할 수 있습니다.");
        }
        StoredObjectInfo object = buildStorage.head(build.getStoragePath());
        if (object.size() != build.getFileSize()) {
            throw new BusinessException(ErrorCode.CONFLICT, "smoke test에서 빌드 객체 크기가 달라졌습니다.");
        }
    }
}
