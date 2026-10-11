package com.stove.studio.core.domain.build;

/**
 * 객체 저장소에서 조회한 파일 크기와 식별 태그.
 *
 * @param size 저장된 파일의 크기(바이트).
 * @param eTag 객체 저장소가 반환한 파일 식별 태그.
 */
public record StoredObjectInfo(long size, String eTag) {
}
