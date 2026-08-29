package ai.devin.d1.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only audit record of a single state transition. The dashboard is a projection of these. */
@Entity
@Table(name = "task_event", indexes = @Index(name = "idx_event_task", columnList = "task_id"))
public class TaskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private String taskKey;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private IssueState fromState;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IssueState toState;

    @Column(length = 2048)
    private String reason;

    @Column(nullable = false, length = 32)
    private String actor;

    @Column(nullable = false)
    private Instant occurredAt = Instant.now();

    protected TaskEvent() {}

    public TaskEvent(Long taskId, String taskKey, IssueState fromState, IssueState toState, String reason, String actor) {
        this.taskId = taskId;
        this.taskKey = taskKey;
        this.fromState = fromState;
        this.toState = toState;
        this.reason = reason;
        this.actor = actor;
    }

    public Long getId() {
        return id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public IssueState getFromState() {
        return fromState;
    }

    public IssueState getToState() {
        return toState;
    }

    public String getReason() {
        return reason;
    }

    public String getActor() {
        return actor;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
