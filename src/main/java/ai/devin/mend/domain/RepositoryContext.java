package ai.devin.mend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One slice of a repository's persisted codebase profile. Sliced rather than stored as one blob so
 * a push that only changes the build files can refresh the build slice alone.
 */
@Entity
@Table(
        name = "repository_context",
        uniqueConstraints = @UniqueConstraint(columnNames = {"repository_id", "kind"}),
        indexes = @Index(name = "idx_context_repo", columnList = "repository_id"))
public class RepositoryContext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ContextKind kind;

    @Lob
    @Column(nullable = false)
    private String content;

    /** The commit this slice was derived from. */
    @Column(name = "source_sha", length = 64)
    private String sourceSha;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected RepositoryContext() {}

    public RepositoryContext(Long repositoryId, ContextKind kind, String content, String sourceSha) {
        this.repositoryId = repositoryId;
        this.kind = kind;
        this.content = content;
        this.sourceSha = sourceSha;
    }

    public Long getId() {
        return id;
    }

    public Long getRepositoryId() {
        return repositoryId;
    }

    public ContextKind getKind() {
        return kind;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSourceSha() {
        return sourceSha;
    }

    public void setSourceSha(String sourceSha) {
        this.sourceSha = sourceSha;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
