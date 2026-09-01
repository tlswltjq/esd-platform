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
 * 기동 시 색인/매핑을 보장한다. <b>동적 매핑에 맡기면 정확 매칭 검색이 어긋난다</b> —
 * 매핑은 한 번 정해지면 못 바꾸므로 첫 문서 전에 걸어야 한다. docs/code-notes.md
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
