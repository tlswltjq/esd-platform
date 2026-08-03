package com.stove.review.api.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.web.GlobalExceptionHandler;
import com.stove.review.api.controller.dto.RejectRequest;
import com.stove.review.core.service.ReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 심의 운영툴의 입구 검증.
 *
 * <p>반려는 사유가 창작자에게 그대로 전달되므로 빈 사유가 통과하면
 * "반려됐는데 이유가 없는" 상태가 된다. 승인 쪽은 등급코드에 기본값이 있어
 * 누락돼도 통과하는데, 그 <b>기본값이 무엇인지</b>를 고정해 둔다 —
 * 조용히 바뀌면 전체 이용가로 나가야 할 게임이 청소년 이용불가가 되거나 그 반대가 된다.
 */
class ReviewControllerTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final ReviewService reviewService = mock(ReviewService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ReviewController(reviewService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("사유 없는 반려는 400 이다")
    void blankReasonIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RejectRequest("RATING", ""))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("사유코드 없는 반려도 400 이다 — 분류할 수 없는 반려는 집계에서 사라진다")
    void blankReasonCodeIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RejectRequest("", "등급 부적합"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("정상 반려는 사유가 그대로 넘어간다")
    void validRejectionReachesTheService() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RejectRequest("RATING", "등급 부적합"))))
                .andExpect(status().isOk());

        verify(reviewService).reject(1L, "RATING", "등급 부적합");
    }

    @Test
    @DisplayName("등급코드를 안 주면 전체 이용가(ALL)로 승인된다 — 기본값을 고정한다")
    void approvalDefaultsToAllAges() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/1/approve"))
                .andExpect(status().isOk());

        verify(reviewService).approve(1L, "ALL");
    }

    @Test
    @DisplayName("등급코드를 주면 그대로 쓴다")
    void approvalUsesGivenRating() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/1/approve").param("ratingCode", "ADULT"))
                .andExpect(status().isOk());

        verify(reviewService).approve(anyLong(), anyString());
    }
}
