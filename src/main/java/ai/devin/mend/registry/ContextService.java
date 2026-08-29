package ai.devin.mend.registry;

import ai.devin.mend.devin.DevinApiClient;
import ai.devin.mend.devin.DevinDtos;
import ai.devin.mend.domain.ContextKind;
import ai.devin.mend.domain.IndexState;
import ai.devin.mend.domain.Repository;
import ai.devin.mend.domain.RepositoryContext;
import ai.devin.mend.domain.RepositoryContextRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a durable profile of each registered repository — how it is built, tested and reviewed —
 * so remediation sessions start from what menD already knows instead of re-reading the codebase for
 * every issue.
 *
 * <p>The profile is generated once by a read-only Devin session and then kept current
 * incrementally: a push only invalidates the slices whose files it touched, and only those slices
 * are regenerated.
 */
@Service
public class ContextService {

    private static final Logger log = LoggerFactory.getLogger(ContextService.class);

    /** Regenerating a profile is cheap analysis, not engineering; cap it hard. */
    private static final int PROFILE_ACU_LIMIT = 3;

    private final RepositoryService registry;
    private final RepositoryContextRepository contexts;
    private final DevinApiClient devin;

    public ContextService(
            RepositoryService registry, RepositoryContextRepository contexts, DevinApiClient devin) {
        this.registry = registry;
        this.contexts = contexts;
        this.devin = devin;
    }

    /** The persisted slices for a repository, freshest first in profile order. */
    public List<RepositoryContext> slices(Repository repository) {
        return repository.getId() == null
                ? List.of()
                : contexts.findByRepositoryIdOrderByKindAsc(repository.getId());
    }

    /**
     * The profile rendered for a prompt. Empty when nothing has been indexed yet, in which case the
     * prompts simply omit the section rather than lying about what menD knows.
     */
    public String profileFor(String repoSlug) {
        return registry.find(repoSlug).map(this::renderProfile).orElse("");
    }

    public String renderProfile(Repository repository) {
        List<RepositoryContext> slices = slices(repository);
        if (slices.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("What menD already knows about ")
                .append(repository.slug())
                .append(" (profile at commit ")
                .append(shortSha(repository.getIndexedSha()))
                .append("):\n");
        for (RepositoryContext slice : slices) {
            out.append("\n### ")
                    .append(slice.getKind().getLabel())
                    .append(slice.isStale() ? " (may be out of date)" : "")
                    .append("\n")
                    .append(slice.getContent().strip())
                    .append("\n");
        }
        return out.toString();
    }

    /**
     * Records a push: counts it against the profile and marks exactly the slices whose files
     * changed. Ordinary source commits mark nothing, which is the point.
     */
    @Transactional
    public void onPush(Repository repository, int commits, List<String> changedPaths, String headSha) {
        Set<ContextKind> touched = invalidatedBy(changedPaths);
        registry.notePush(repository, commits, !touched.isEmpty());
        if (touched.isEmpty()) {
            log.debug("push to {} touched no profile slice", repository.slug());
            return;
        }
        for (RepositoryContext slice : slices(repository)) {
            if (touched.contains(slice.getKind())) {
                slice.setStale(true);
                contexts.save(slice);
            }
        }
        log.info(
                "push to {} ({}) aged {} profile slice(s): {}",
                repository.slug(),
                shortSha(headSha),
                touched.size(),
                touched);
    }

    /** The slices a set of changed paths invalidates. */
    public static Set<ContextKind> invalidatedBy(Collection<String> changedPaths) {
        Set<ContextKind> touched = EnumSet.noneOf(ContextKind.class);
        if (changedPaths == null) {
            return touched;
        }
        for (String path : changedPaths) {
            for (ContextKind kind : ContextKind.values()) {
                if (kind.invalidatedBy(path)) {
                    touched.add(kind);
                }
            }
        }
        return touched;
    }

    /** The slices that need generating: everything on a first index, only the aged ones after. */
    public Set<ContextKind> pendingKinds(Repository repository) {
        List<RepositoryContext> existing = slices(repository);
        if (existing.isEmpty()) {
            return EnumSet.allOf(ContextKind.class);
        }
        Set<ContextKind> pending = existing.stream()
                .filter(RepositoryContext::isStale)
                .map(RepositoryContext::getKind)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ContextKind.class)));
        Set<ContextKind> missing = EnumSet.allOf(ContextKind.class);
        existing.forEach(slice -> missing.remove(slice.getKind()));
        pending.addAll(missing);
        return pending;
    }

    /** True when there is profile work worth spending a session on. */
    public boolean needsIndexing(Repository repository) {
        if (!repository.isOperational()
                || repository.getIndexState() == IndexState.INDEXING
                || repository.getIndexState() == IndexState.INDEX_FAILED) {
            return false; // a failed index waits for an operator rather than burning ACUs in a loop
        }
        return !pendingKinds(repository).isEmpty();
    }

    /**
     * Starts a read-only Devin session that produces the pending slices. Returns the repository as
     * stored; callers poll it later through {@link #collect(Repository)}.
     */
    @Transactional
    public Repository startIndexing(Repository repository) {
        Set<ContextKind> pending = pendingKinds(repository);
        if (pending.isEmpty() || !devin.isConfigured()) {
            return repository;
        }
        DevinDtos.SessionDetails session = devin.createSession(
                ContextPrompt.profilePrompt(repository.slug(), repository.getDefaultBranch(), pending),
                "menD profile — " + repository.slug(),
                List.of("mend", "context", repository.slug()),
                PROFILE_ACU_LIMIT,
                ContextPrompt.schemaFor(pending),
                repository.slug());
        repository.setIndexState(IndexState.INDEXING);
        repository.setContextSessionId(session.sessionId());
        repository.setContextSessionUrl(session.url());
        repository.setIndexError(null);
        log.info("indexing {} in session {} ({} slice(s))", repository.slug(), session.sessionId(), pending.size());
        return registry.save(repository);
    }

    /**
     * Reads an in-flight indexing session and persists whatever slices it returned. Partial output
     * is kept: a profile missing one slice is still worth more than no profile.
     */
    @Transactional
    public Repository collect(Repository repository) {
        if (repository.getIndexState() != IndexState.INDEXING || repository.getContextSessionId() == null) {
            return repository;
        }
        Optional<DevinDtos.SessionDetails> maybe = devin.getSession(repository.getContextSessionId());
        if (maybe.isEmpty()) {
            return fail(repository, "the profiling session could not be read back from the Devin API");
        }
        DevinDtos.SessionDetails session = maybe.get();
        if (session.isExpired()) {
            return fail(repository, "the profiling session ended in error");
        }
        if (!session.hasStructuredOutput()) {
            if (session.isFinished() || session.isBlocked()) {
                return fail(repository, "the profiling session finished without returning a profile");
            }
            return repository;
        }

        JsonNode output = session.structuredOutput();
        String sha = text(output, "commit_sha");
        List<ContextKind> written = new ArrayList<>();
        for (ContextKind kind : ContextKind.values()) {
            String content = text(output, kind.name().toLowerCase(Locale.ROOT));
            if (content == null || content.isBlank()) {
                continue;
            }
            RepositoryContext slice = contexts
                    .findByRepositoryIdAndKind(repository.getId(), kind)
                    .orElseGet(() -> new RepositoryContext(repository.getId(), kind, content, sha));
            slice.refresh(content, sha);
            contexts.save(slice);
            written.add(kind);
        }
        if (written.isEmpty()) {
            return fail(repository, "the profiling session returned an empty profile");
        }

        repository.setIndexState(IndexState.INDEXED);
        repository.setIndexedSha(sha);
        repository.setIndexedAt(Instant.now());
        repository.setCommitsSinceIndex(0);
        repository.setIndexError(null);
        log.info("profiled {} at {}: {}", repository.slug(), shortSha(sha), written);
        return registry.save(repository);
    }

    private Repository fail(Repository repository, String reason) {
        boolean hasProfile = !slices(repository).isEmpty();
        repository.setIndexState(hasProfile ? IndexState.STALE : IndexState.INDEX_FAILED);
        repository.setIndexError(reason);
        log.warn("profiling {} failed: {}", repository.slug(), reason);
        return registry.save(repository);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "unknown";
        }
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
