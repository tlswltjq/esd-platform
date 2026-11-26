package com.stove.bench.messaging.app.core.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BenchRecordRepository extends JpaRepository<BenchRecord, Long> {
}
