package com.skateboard.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringProcessedEventRepository extends JpaRepository<ProcessedEventJpaEntity, UUID> {
}
