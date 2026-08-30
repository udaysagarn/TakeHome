package ai.devin.mend.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<RemediationTask, Long> {

    Optional<RemediationTask> findByRepoAndIssueNumber(String repo, int issueNumber);

    List<RemediationTask> findByStateIn(Collection<IssueState> states);

    long countByStateIn(Collection<IssueState> states);

    List<RemediationTask> findAllByOrderByUpdatedAtDesc();

    @Query("select t.state, count(t) from RemediationTask t group by t.state")
    List<Object[]> countByState();

    /**
     * Active tasks this worker may work on: the ones it already owns, plus the ones that are unowned
     * or whose owner stopped heartbeating.
     */
    @Query("""
            select t from RemediationTask t
            where t.state in :states
              and (t.ownerId is null
                   or t.ownerId = :worker
                   or t.leaseExpiresAt is null
                   or t.leaseExpiresAt <= :now)
            order by t.updatedAt asc""")
    List<RemediationTask> findClaimable(
            @Param("states") Collection<IssueState> states,
            @Param("worker") String worker,
            @Param("now") Instant now);

    /**
     * Takes ownership of a task in one atomic statement. The where clause is the mutual exclusion:
     * only an unowned or expired lease can be claimed, so exactly one worker wins the race.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RemediationTask t
               set t.ownerId = :owner,
                   t.leaseAcquiredAt = case when t.ownerId = :owner then t.leaseAcquiredAt else :now end,
                   t.leaseExpiresAt = :expiresAt,
                   t.etaAt = case when t.etaAt is null then :etaAt else t.etaAt end,
                   t.leaseTakeovers = t.leaseTakeovers
                        + case when t.ownerId is null or t.ownerId = :owner then 0 else 1 end,
                   t.updatedAt = :now
             where t.id = :id
               and (t.ownerId is null
                    or t.ownerId = :owner
                    or t.leaseExpiresAt is null
                    or t.leaseExpiresAt <= :now)""")
    int claim(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt,
            @Param("etaAt") Instant etaAt);

    /** Heartbeat. Fails (returns 0) if the lease was taken over while this worker was busy. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RemediationTask t
               set t.leaseExpiresAt = :expiresAt,
                   t.updatedAt = :now
             where t.id = :id and t.ownerId = :owner""")
    int renew(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RemediationTask t
               set t.ownerId = null,
                   t.leaseExpiresAt = null
             where t.id = :id and t.ownerId = :owner""")
    int release(@Param("id") Long id, @Param("owner") String owner);
}
