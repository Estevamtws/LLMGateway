package com.llmgateway.client;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    @EntityGraph(attributePaths = "client")
    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByClientIdInOrderByCreatedAtDesc(Collection<UUID> clientIds);
}
