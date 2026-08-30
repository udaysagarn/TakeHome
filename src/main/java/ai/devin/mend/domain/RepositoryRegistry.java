package ai.devin.mend.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositoryRegistry extends JpaRepository<Repository, Long> {

    Optional<Repository> findByOwnerAndName(String owner, String name);

    List<Repository> findAllByOrderByOwnerAscNameAsc();

    @Query("""
            select r from Repository r
            where r.enabled = true and r.accessState = ai.devin.mend.domain.AccessState.VALIDATED
            order by r.owner asc, r.name asc""")
    List<Repository> findOperational();

    @Query("select r from Repository r where r.indexState = :state")
    List<Repository> findByIndexState(@Param("state") IndexState state);
}
