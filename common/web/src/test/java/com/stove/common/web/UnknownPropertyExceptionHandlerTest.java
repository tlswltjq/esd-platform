package com.stove.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모르는 속성 요청이 400 으로 나가고, 그 응답이 엔티티를 밀고하지 않는지 본다. [D-024]
 *
 * <p>이 안전망이 없으면 {@link GlobalExceptionHandler} 의 마지막 분기로 흘러 500 이 나간다.
 * 그런데 malformed 목록에 그냥 얹는 것으로는 부족하다 — 그 분기는 {@code e.getMessage()} 를
 * 그대로 싣고, 이 예외의 메시지는 {@code No property 'productId' found for type 'Product'} 다.
 * <b>인증 없는 공개 경로에서 엔티티 타입명이 나가는 것</b>까지 여기서 고정한다.
 */
class UnknownPropertyExceptionHandlerTest {

    /** 메시지에 타입명이 새는지 보기 위해 실제 엔티티처럼 이름을 붙였다. */
    record Product(Long id) {
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/things")
        String list() {
            // Spring Data 가 정렬 키를 엔티티 속성으로 해석하다 던지는 것과 같은 예외다.
            throw new PropertyReferenceException("productId", TypeInformation.of(Product.class), List.of());
        }
    }

    /**
     * <b>두 어드바이스를 함께, 그리고 불리한 순서로 세운다.</b>
     *
     * <p>이 클래스만 세우면 테스트는 통과하지만 아무것도 증명하지 못한다. 실제 앱에는
     * {@link GlobalExceptionHandler} 가 함께 있고 거기에는 {@code Exception.class} 를 받는
     * 마지막 분기가 있다. 어드바이스 사이의 선택은 <b>가장 구체적인 핸들러</b>가 아니라
     * <b>먼저 오는 어드바이스</b>가 이기므로, 순서를 정하지 않으면 이 안전망은 한 번도 실행되지 않는다.
     *
     * <p>실제로 그랬다 — 순서 없이 앱에 태웠을 때 여전히 500 이었다. 그래서 catch-all 을
     * 앞에 두고, {@code @Order} 만이 결과를 뒤집을 수 있는 배치로 고정한다.
     */
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler(), new UnknownPropertyExceptionHandler())
            .build();

    @Test
    @DisplayName("모르는 속성은 INVALID_REQUEST 400 이다 — 500 이 아니다")
    void unknownPropertyIsBadRequest() throws Exception {
        mockMvc.perform(get("/things"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("응답이 엔티티 타입명을 흘리지 않는다 — 요청한 속성 이름만 돌려준다")
    void responseDoesNotLeakTheEntityType() throws Exception {
        String body = mockMvc.perform(get("/things"))
                .andReturn().getResponse().getContentAsString();

        // 부르는 쪽이 무엇을 고쳐야 하는지는 알아야 하므로 속성 이름은 남긴다.
        assertThat(body).contains("productId");
        // 내부 구조는 알려 주지 않는다. 원본 메시지를 그대로 실으면 둘 다 나간다.
        // 타입명은 대문자 P 로 시작하므로 위의 productId 와 겹치지 않는다.
        assertThat(body).doesNotContain("Product", "No property", "found for type");
    }
}
