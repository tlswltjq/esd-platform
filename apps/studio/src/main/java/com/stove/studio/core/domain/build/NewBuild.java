package com.stove.studio.core.domain.build;

/**
 *  빌드 등록 입력. 바이너리는 별도 경로로 올라가고 여기에는 메타데이터만 담긴다.
 *
 * @param version 배포 파일의 제품 버전 문자열.
 * @param fileSize 배포 파일의 크기(바이트).
 * @param checksum 파일 무결성 검증에 사용하는 체크섬.
 */
public record NewBuild(String version, long fileSize, String checksum) {
}
