package com.stove.studio.core.domain.revision;

/**
 * 상점 소개용으로 업로드한 이미지·영상 자료 정보.
 *
 * @param assetId 업로드한 상점 이미지·영상 자료의 식별자.
 * @param url 업로드한 상점 자료에 접근할 URL.
 * @param contentType 자료의 MIME 형식.
 * @param size 저장된 파일의 크기(바이트).
 */
public record StoreAsset(String assetId, String url, String contentType, long size) {}
