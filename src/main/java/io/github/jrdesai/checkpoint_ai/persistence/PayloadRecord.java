package io.github.jrdesai.checkpoint_ai.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payload_store")
@NoArgsConstructor
@Getter
public class PayloadRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    private Instant createdAt;

    public PayloadRecord(String content, Instant createdAt){
        this.content = content;
        this.createdAt = createdAt;
    }


}
