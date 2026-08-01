package com.stove.studio.core.domain;

/** 빌드 등록 입력. 바이너리는 별도 경로로 올라가고 여기에는 메타데이터만 담긴다. */
public record NewBuild(String version, long fileSize, String checksum) {
}
