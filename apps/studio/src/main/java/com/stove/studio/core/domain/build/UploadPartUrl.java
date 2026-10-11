package com.stove.studio.core.domain.build;

/**
 * 분할 업로드의 조각 번호와 서명된 전송 URL.
 *
 * @param partNumber 1부터 시작하는 분할 업로드 조각 번호.
 * @param uploadUrl 파일 또는 업로드 조각을 전송할 서명된 URL.
 */
public record UploadPartUrl(int partNumber, String uploadUrl) {
}
