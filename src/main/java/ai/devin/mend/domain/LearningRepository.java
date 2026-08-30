package ai.devin.mend.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningRepository extends JpaRepository<Learning, Long> {

    List<Learning> findByStatusOrderByUpdatedAtDesc(LearningStatus status);

    List<Learning> findByScopeAndStatus(LearningScope scope, LearningStatus status);

    List<Learning> findByRepoAndStatus(String repo, LearningStatus status);

    java.util.Optional<Learning> findByFingerprint(String fingerprint);
}
