package io.github.jrdesai.checkpoint_ai.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessedModuleRepository extends JpaRepository<ProcessedModule, UUID> {
    List<ProcessedModule> findByRepoName(String repoName);
    Optional<ProcessedModule> findByRepoNameAndFilePath(String repoName, String filePath);
}


