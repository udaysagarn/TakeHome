package ai.devin.mend.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EnginePauses extends JpaRepository<EnginePause, Long> {}
