package io.github.jrdesai.checkpoint_ai.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"repo_name","file_path"}))
public class ProcessedModule {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "repo_name", nullable = false)
    private String repoName;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "narrative_json", nullable = false, columnDefinition = "TEXT")
    private String narrativeJson;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedModule(String repoName, String filePath, String contentHash, String narrativeJson, Instant processedAt) {
        this.repoName = repoName;
        this.filePath = filePath;
        this.contentHash = contentHash;
        this.narrativeJson = narrativeJson;
        this.processedAt = processedAt;
    }

    /**
     * Update an existing record in-place.
     * Preserves the same DB row and ID — avoids unique constraint violations
     * that can occur with delete-then-insert on (repo_name, file_path).
     */
    public void update(String contentHash, String narrativeJson) {
        this.contentHash = contentHash;
        this.narrativeJson = narrativeJson;
        this.processedAt = Instant.now();
    }
}
