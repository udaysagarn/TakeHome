package ai.devin.mend.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositoryContextRepository extends JpaRepository<RepositoryContext, Long> {

    List<RepositoryContext> findByRepositoryIdOrderByKindAsc(Long repositoryId);

    Optional<RepositoryContext> findByRepositoryIdAndKind(Long repositoryId, ContextKind kind);

    void deleteByRepositoryId(Long repositoryId);
}
