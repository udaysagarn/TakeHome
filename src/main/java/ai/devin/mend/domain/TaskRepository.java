package ai.devin.mend.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TaskRepository extends JpaRepository<RemediationTask, Long> {

    Optional<RemediationTask> findByRepoAndIssueNumber(String repo, int issueNumber);

    List<RemediationTask> findByStateIn(Collection<IssueState> states);

    long countByStateIn(Collection<IssueState> states);

    List<RemediationTask> findAllByOrderByUpdatedAtDesc();

    @Query("select t.state, count(t) from RemediationTask t group by t.state")
    List<Object[]> countByState();
}
