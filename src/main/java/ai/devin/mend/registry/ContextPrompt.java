package ai.devin.mend.registry;

import ai.devin.mend.domain.ContextKind;
import java.util.Collection;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The read-only prompt and structured-output schema for profiling a repository. Only the requested
 * slices are asked for, so a refresh after a push costs a fraction of the first index.
 */
final class ContextPrompt {

    private ContextPrompt() {}

    /** What each slice must contain, phrased as an instruction to the profiling session. */
    private static String instructionFor(ContextKind kind) {
        return switch (kind) {
            case STACK -> "Languages, frameworks, runtime versions and package managers, and how a fresh"
                    + " checkout installs its dependencies. Name the exact files you read.";
            case COMMANDS -> "The exact commands for install, build, unit tests, integration tests, lint,"
                    + " format and typecheck, taken from the repository's own configuration rather than"
                    + " guessed. Say which are slow (> 5 minutes) and which need services or credentials.";
            case LAYOUT -> "A directory map of the top two levels: what each tree owns, and where a change of"
                    + " each kind belongs.";
            case TESTS -> "Test frameworks, where tests live relative to the code they cover, naming and"
                    + " fixture conventions, and what a reviewer expects a new test to look like here.";
            case CI -> "The CI workflows, what triggers each, which checks are required to merge, and how long"
                    + " they take. Note whether a pull request gets an automatic verdict at all.";
            case AGENT_RULES -> "Read AGENTS.md, CLAUDE.md, codex.md, GEMINI.md, .cursorrules, .cursor/rules/**,"
                    + " .github/copilot-instructions.md, .agents/skills/** and CONTRIBUTING.md if they exist."
                    + " Reproduce the instructions that constrain how code is written here, verbatim where"
                    + " they are short. If none exist, say so explicitly.";
            case PR_CONVENTIONS -> "Branch naming, commit message format, pull request template, required"
                    + " sign-off or DCO, CODEOWNERS review requirements, and anything that gets a pull"
                    + " request rejected on form rather than substance.";
            case RISK -> "The areas where a change is risky or reviewers are demanding: migrations, security"
                    + " boundaries, generated files, public APIs, feature flags, performance-sensitive paths.";
        };
    }

    static String profilePrompt(String repo, String defaultBranch, Collection<ContextKind> kinds) {
        String sections = kinds.stream()
                .map(kind -> "- %s: %s".formatted(fieldName(kind), instructionFor(kind)))
                .collect(Collectors.joining("\n"));
        return """
               Profile the repository %s (branch %s) so an autonomous engineer can work in it without
               re-reading the whole codebase. This is READ-ONLY: do not modify files, do not create
               branches, and do not open a pull request.

               Produce these sections, each as concise markdown (at most ~200 words each). Prefer facts you
               verified in the repository over general knowledge of the frameworks it uses. Where you could
               not determine something, say "unknown" rather than guessing — a wrong build command is worse
               than a missing one.

               %s

               Also return commit_sha: the full SHA of the commit you profiled, from `git rev-parse HEAD`.

               Return only the structured output.
               """
                .formatted(repo, defaultBranch == null ? "default" : defaultBranch, sections);
    }

    /** A schema limited to the requested slices, so the session cannot pad the profile. */
    static String schemaFor(Collection<ContextKind> kinds) {
        String properties = kinds.stream()
                .map(kind -> """
                        "%s": {"type": "string", "description": "%s"}"""
                        .formatted(fieldName(kind), kind.getLabel()))
                .collect(Collectors.joining(",\n    "));
        return """
               {
                 "type": "object",
                 "properties": {
                   %s,
                   "commit_sha": {"type": "string", "description": "Full SHA of the profiled commit"}
                 },
                 "required": ["commit_sha"]
               }"""
                .formatted(properties);
    }

    private static String fieldName(ContextKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
