package ai.devin.mend.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Takes ownership of a repository's profile in one atomic statement, exactly as
     * {@code TaskRepository.claim} does for a task: the where clause is the mutual exclusion, so of
     * two workers about to spend a profiling session on the same repository only one proceeds.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Repository r
               set r.ownerId = :owner,
                   r.leaseAcquiredAt = case when r.ownerId = :owner then r.leaseAcquiredAt else :now end,
                   r.leaseExpiresAt = :expiresAt,
                   r.leaseTakeovers = r.leaseTakeovers
                        + case when r.ownerId is null or r.ownerId = :owner then 0 else 1 end,
                   r.updatedAt = :now
             where r.id = :id
               and (r.ownerId is null
                    or r.ownerId = :owner
                    or r.leaseExpiresAt is null
                    or r.leaseExpiresAt <= :now)""")
    int claim(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    /** Heartbeat. Fails (returns 0) if the lease was taken over while this worker was busy. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Repository r
               set r.leaseExpiresAt = :expiresAt,
                   r.updatedAt = :now
             where r.id = :id and r.ownerId = :owner""")
    int renew(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Repository r
               set r.ownerId = null,
                   r.leaseExpiresAt = null
             where r.id = :id and r.ownerId = :owner""")
    int release(@Param("id") Long id, @Param("owner") String owner);
}
