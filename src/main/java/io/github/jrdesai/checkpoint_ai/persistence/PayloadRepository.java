package io.github.jrdesai.checkpoint_ai.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PayloadRepository extends JpaRepository<PayloadRecord, UUID> {
}
