package ai.devin.mend.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {

    List<TaskEvent> findByTaskIdOrderByOccurredAtAsc(Long taskId);

    List<TaskEvent> findAllByOrderByOccurredAtDesc(Pageable pageable);
}
