package com.stove.download.core.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PatchManifestRepository extends MongoRepository<PatchManifest, String> {

    List<PatchManifest> findByProductCodeAndReleaseIdIsNotNullOrderByReleasedAtDesc(String productCode);

    Optional<PatchManifest> findByProductCodeAndReleaseId(String productCode, Long releaseId);
}
