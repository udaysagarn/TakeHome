package ai.devin.d1.engine;

import ai.devin.d1.domain.SuccessCriteria;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the two prompts the control plane issues to Devin. Both are deliberately narrow: the scoping
 * prompt is read-only and must be able to say "no", and the remediation prompt is bound to criteria
 * that were agreed before any work started.
 */
@Component
public class PromptBuilder {

    public String scopingPrompt(String repo, int issueNumber, String issueTitle, String issueBody) {
        return """
               You are triaging a GitHub issue to decide whether it can be remediated autonomously and
               verified objectively. This is a READ-ONLY analysis task: do not modify files, do not
               create branches, and do not open a pull request.

               Repository: %s
               Issue #%d: %s

               --- issue body ---
               %s
               --- end issue body ---

               Steps:
               1. Clone/inspect the repository and locate the code, dependencies or tests the issue refers to.
               2. Confirm the problem actually exists on the default branch. If you cannot reproduce or
                  locate it, that is a strong signal it is not a candidate.
               3. Derive acceptance criteria that a machine can check, and the exact commands that check them.
                  Prefer commands that already exist in the repo (test suites, linters, audit tools).
               4. Judge the blast radius. If the change would touch a very large number of files, or requires
                  a product decision, design choice, credentials, or information not present in the repo,
                  it is NOT a candidate.

               Set is_candidate=false whenever any of the following is true:
               - the desired end state cannot be stated as objectively checkable conditions;
               - there is no command or test that can prove the fix;
               - the issue is a question, discussion, or feature request without an agreed design;
               - essential information is missing (list it in blocking_unknowns).

               Do not inflate confidence. Being honest that an issue is unsuitable is a successful outcome
               of this task.

               Return only the structured output.
               """
                .formatted(repo, issueNumber, issueTitle, truncate(issueBody, 6000));
    }

    public String remediationPrompt(
            String repo, int issueNumber, String issueTitle, String issueBody, SuccessCriteria criteria) {
        return """
               Remediate GitHub issue #%d in %s and open a pull request against the default branch.

               Issue title: %s

               --- issue body ---
               %s
               --- end issue body ---

               This work has already been scoped. You are being held to the following contract, which was
               posted publicly on the issue before you started.

               Problem: %s

               Acceptance criteria (all must hold when you are done):
               %s

               Verification commands (run these and keep their output as evidence):
               %s

               Expected files in scope:
               %s

               Rules:
               - Follow the repository's own conventions and contributor guidance (AGENTS.md / CLAUDE.md /
                 CONTRIBUTING.md), including commit message format and pre-commit hooks.
               - Keep the change minimal and confined to the scope above. Do not opportunistically refactor.
               - Do not weaken, skip or delete tests to make anything pass.
               - Run the verification commands and the repository's lint/type checks before opening the PR.
               - Open exactly one pull request and put the evidence for each acceptance criterion in its body,
                 with "Closes #%d".
               - If you conclude the issue cannot be fixed as scoped, do NOT open a speculative pull request:
                 stop and report remediated=false with blocked_reason explaining why.

               Return the structured output with one entry in criteria_results per acceptance criterion above.
               """
                .formatted(
                        issueNumber,
                        repo,
                        issueTitle,
                        truncate(issueBody, 6000),
                        criteria.problemRestatement(),
                        bullets(criteria.acceptanceCriteria()),
                        bullets(criteria.verificationCommands()),
                        bullets(criteria.filesInScope()),
                        issueNumber);
    }

    public String ciFailureNudge(String prUrl, String failureSummary) {
        return """
               CI is failing on your pull request %s.

               %s

               Fix the cause of the failure rather than the symptom, push to the same branch, and confirm the
               acceptance criteria still hold. If the failure is unrelated to your change, say so explicitly
               with evidence instead of working around it.
               """
                .formatted(prUrl, truncate(failureSummary, 4000));
    }

    public String stallNudge(int nudgeNumber, int maxNudges) {
        return """
               Status check %d of %d from the D1 orchestrator: you appear to be blocked.

               If you are waiting on a human decision, state the decision you need in one sentence and finish
               with remediated=false and a blocked_reason. If you are not actually blocked, continue and open
               the pull request.
               """
                .formatted(nudgeNumber, maxNudges);
    }

    private static String bullets(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "  (none specified)";
        }
        return items.stream().map(i -> "  - " + i).collect(Collectors.joining("\n"));
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "(empty)";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n… (truncated)";
    }
}
