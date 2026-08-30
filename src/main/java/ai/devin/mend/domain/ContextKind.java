package ai.devin.mend.domain;

import java.util.List;
import java.util.Locale;

/**
 * The slices of a repository profile, each with the paths whose change invalidates it. A push
 * refreshes only the slices its changed files touch, which is what keeps a new issue from paying
 * for a full re-read of the codebase.
 */
public enum ContextKind {
    /** Languages, frameworks, package managers, how to install dependencies. */
    STACK("Stack & build", List.of("package.json", "pom.xml", "build.gradle", "requirements.txt", "pyproject.toml",
            "setup.py", "go.mod", "cargo.toml", "gemfile", "composer.json", "dockerfile", "makefile")),

    /** How to build, test, lint and typecheck; the commands a verification contract may use. */
    COMMANDS("Build, test & lint commands", List.of("package.json", "pom.xml", "makefile", "tox.ini", "noxfile.py",
            "pytest.ini", ".eslintrc", "eslint.config", "tsconfig.json", "scripts/")),

    /** Directory map: which tree owns what. */
    LAYOUT("Directory map", List.of()),

    /** Test frameworks, where tests live, and the conventions a new test must follow. */
    TESTS("Test conventions", List.of("test/", "tests/", "spec/", "__tests__/", "conftest.py", "jest.config",
            "vitest.config", "cypress.config", "playwright.config")),

    /** CI workflows and what they gate — the basis for the verification tier menD can reach. */
    CI("CI & required checks", List.of(".github/workflows/", ".circleci/", ".gitlab-ci.yml", "azure-pipelines.yml",
            "jenkinsfile", ".pre-commit-config.yaml")),

    /**
     * Instructions the repository gives to coding agents and contributors. Devin must honour these,
     * so they are carried verbatim rather than summarised.
     */
    AGENT_RULES("Agent & contributor rules", List.of("agents.md", "claude.md", "codex.md", "cursor.md", "gemini.md",
            ".cursorrules", ".cursor/", ".github/copilot-instructions.md", ".agents/", "contributing.md",
            "code_of_conduct.md", ".windsurfrules", ".clinerules")),

    /** Conventions a pull request must follow to be accepted here. */
    PR_CONVENTIONS("Pull request conventions", List.of(".github/pull_request_template.md", ".github/codeowners",
            "codeowners", "contributing.md")),

    /** Areas where changes are risky and reviewers are picky. */
    RISK("Risky areas", List.of());

    private final String label;
    private final List<String> pathTriggers;

    ContextKind(String label, List<String> pathTriggers) {
        this.label = label;
        this.pathTriggers = pathTriggers;
    }

    public String getLabel() {
        return label;
    }

    public List<String> getPathTriggers() {
        return pathTriggers;
    }

    /** True when a commit touching {@code path} invalidates this slice. */
    public boolean invalidatedBy(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        return pathTriggers.stream().anyMatch(trigger -> trigger.endsWith("/")
                ? lower.startsWith(trigger) || lower.contains("/" + trigger)
                : fileName.startsWith(trigger) || lower.startsWith(trigger));
    }
}
