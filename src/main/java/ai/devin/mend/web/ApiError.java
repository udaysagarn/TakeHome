package ai.devin.mend.web;

/** The one error shape every {@code /api} failure body uses: {@code {"error": "why"}}. */
public record ApiError(String error) {}
