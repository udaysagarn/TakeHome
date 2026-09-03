package ai.devin.mend.web;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/repositories}: the {@code owner/name} slug (or GitHub URL) to watch. */
public record RegisterRepositoryRequest(@NotBlank(message = "repo is required") String repo) {}
