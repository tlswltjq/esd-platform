package com.stove.download.domain;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EntitlementRepository extends MongoRepository<Entitlement, String> {

    List<Entitlement> findByMemberIdAndActiveIsTrue(Long memberId);
}
