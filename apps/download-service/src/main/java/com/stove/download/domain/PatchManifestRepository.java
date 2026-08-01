package com.stove.download.domain;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PatchManifestRepository extends MongoRepository<PatchManifest, String> {

    List<PatchManifest> findByProductCodeOrderByReleasedAtDesc(String productCode);
}
