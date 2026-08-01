package com.stove.store.config;

import com.stove.store.core.domain.ProductDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * 기동 시 색인/매핑을 보장한다.
 * 동적 매핑에 맡기면 status·productCode 가 text 로 잡혀 정확 매칭 검색이 어긋나므로
 * {@link ProductDocument} 의 선언적 매핑을 명시적으로 적용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchIndexInitializer implements ApplicationRunner {

    private final ElasticsearchOperations elasticsearchOperations;

    @Override
    public void run(ApplicationArguments args) {
        IndexOperations indexOps = elasticsearchOperations.indexOps(ProductDocument.class);
        if (indexOps.exists()) {
            log.info("검색 색인 확인 완료");
            return;
        }
        indexOps.createWithMapping();
        log.info("검색 색인 생성 완료");
    }
}
