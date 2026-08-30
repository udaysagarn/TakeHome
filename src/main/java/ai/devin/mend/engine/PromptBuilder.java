package ai.devin.mend.engine;

import ai.devin.mend.domain.SuccessCriteria;
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

    public String scopingPrompt(
            String repo,
            int issueNumber,
            String issueTitle,
            String issueBody,
            String repositoryProfile,
            String lessons) {
        return """
               You are triaging a GitHub issue to decide whether it can be remediated autonomously and
               verified objectively. This is a READ-ONLY analysis task: do not modify files, do not
               create branches, and do not open a pull request.

               Repository: %s
               Issue #%d: %s

               --- issue body ---
               %s
               --- end issue body ---
               %s%s
               Steps:
               1. Clone/inspect the repository and locate the code, dependencies or tests the issue refers to.
               2. Confirm the problem actually exists on the default branch. If you cannot reproduce or
                  locate it, that is a strong signal it is not a candidate.
               3. Derive acceptance criteria that a machine can check, and the exact commands that check them.
                  Prefer commands that already exist in the repo (test suites, linters, audit tools).
                  State a test plan: read the repository's existing tests for the affected area and name the
                  test file and case that must be added or changed to prove the fix, in that repository's own
                  conventions and framework. Only say no test change is warranted when the change genuinely
                  cannot be covered by one (for example a dependency pin proven by an audit command), and
                  then name the existing check that covers it.
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
                .formatted(
                        repo,
                        issueNumber,
                        issueTitle,
                        truncate(issueBody, 6000),
                        profileSection(repositoryProfile),
                        lessonsSection(lessons));
    }

    public String remediationPrompt(
            String repo,
            int issueNumber,
            String issueTitle,
            String issueBody,
            SuccessCriteria criteria,
            String repositoryProfile,
            String lessons) {
        return """
               Remediate GitHub issue #%d in %s and open a pull request against the default branch.

               Issue title: %s

               --- issue body ---
               %s
               --- end issue body ---
               %s%s
               This work has already been scoped. You are being held to the following contract, which was
               posted publicly on the issue before you started.

               Problem: %s

               Acceptance criteria (all must hold when you are done):
               %s

               Verification commands (run these and keep their output as evidence):
               %s

               Expected files in scope:
               %s

               Test plan agreed at scoping time:
               %s

               Rules:
               - Follow the repository's own conventions and contributor guidance (AGENTS.md / CLAUDE.md /
                 CONTRIBUTING.md), including commit message format and pre-commit hooks.
               - Keep the change minimal and confined to the scope above. Do not opportunistically refactor.
               - Do not weaken, skip or delete tests to make anything pass.
               - Carry out the test plan: add or update the automated tests that prove this fix, in the
                 repository's existing test framework, layout and style. For a new test, first confirm it
                 fails without your fix and passes with it, and report that in test_evidence.
               - Departing from the test plan is allowed only if the repository makes it impossible; then say
                 so explicitly in test_evidence. Do not silently ship a behaviour change with no test.
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
                        profileSection(repositoryProfile),
                        lessonsSection(lessons),
                        criteria.problemRestatement(),
                        bullets(criteria.acceptanceCriteria()),
                        bullets(criteria.verificationCommands()),
                        bullets(criteria.filesInScope()),
                        blankTo(criteria.testPlan(), "none agreed — use your judgement and justify it"),
                        issueNumber);
    }

    /**
     * Prompt for the last-resort verifier session. It is deliberately hostile to the change it is
     * checking and is forbidden from touching the code, so its verdict is worth something.
     */
    public String verifierPrompt(String repo, String prUrl, SuccessCriteria criteria) {
        return """
               You are independently verifying someone else's pull request. You did NOT write this change
               and you must not modify it: do not edit files, do not push, do not comment on GitHub, do not
               open or update a pull request. Your only job is to run commands and report what happened.

               Repository: %s
               Pull request: %s

               Check out the pull request's head commit in a clean working tree, then run exactly these
               commands from the repository root and capture each exit code:
               %s

               These are the acceptance criteria the change claims to meet:
               %s

               Rules:
               - Report the real exit codes. Do not fix, retry differently, or work around a failure.
               - If a command cannot run at all (missing toolchain, needs credentials), report exit code -1
                 and say why in its output.
               - Judging the change as failing is a perfectly good outcome; do not try to make it pass.

               Return only the structured output.
               """
                .formatted(repo, prUrl, bullets(criteria.verificationCommands()), bullets(criteria.acceptanceCriteria()));
    }

    /**
     * Hands a human reviewer's verdict back to the session that wrote the code. The reviewer outranks
     * the contract: menD does not let the session argue that the criteria were met and stop there.
     */
    public String reviewFeedbackMessage(String prUrl, int round, int maxRounds, String feedback) {
        return """
               A human reviewer has asked for changes on your pull request %s (round %d of %d).

               --- reviewer feedback ---
               %s
               --- end reviewer feedback ---

               The reviewer outranks the acceptance criteria you were given: where the two conflict, do what
               the reviewer asked and say so. Address every point, push to the same branch, keep the tests
               you added passing, and add tests for anything the reviewer says is untested. Reply on the pull
               request describing what you changed for each point.

               If a point needs a decision only a human can make, do not guess: state the decision you need
               and finish with remediated=false and that as the blocked_reason.
               """
                .formatted(prUrl, round, maxRounds, truncate(feedback, 6000));
    }

    /**
     * Turns one task's review history into reusable lessons. Kept read-only and cheap: it is a
     * classification job, not an engineering one.
     */
    public String retrospectivePrompt(String repo, int issueNumber, String prUrl, String outcome, String feedback) {
        return """
               You are running a retrospective on one automated remediation so the next one goes better.
               This is READ-ONLY: do not modify files, do not push, do not comment on GitHub.

               Repository: %s
               Issue: #%d
               Pull request: %s

               --- how it ended ---
               %s
               --- reviewer feedback, if any ---
               %s
               --- end ---

               Extract only lessons that would have changed what the engineer did. Skip praise, skip
               restating the change, skip anything already obvious from the repository's contributor guide.
               For each lesson decide:

               - scope REPO when it is about this codebase: its conventions, layout, reviewers' habits,
                 the test framework, files that always need updating together.
               - scope GENERAL when it would hold in any repository.

               Then recommend where it belongs:
               - PROMPT_PREAMBLE: menD should inject it into this repository's future sessions.
               - DEVIN_KNOWLEDGE: worth a Devin knowledge note or playbook for the whole organisation.
               - REPO_INSTRUCTIONS: belongs in the repository's own AGENTS.md / CLAUDE.md / CONTRIBUTING.md.
               - MEND_BACKLOG: the lesson is about menD's own behaviour, for example it accepted an issue it
                 should have rejected, or the acceptance criteria were wrong.

               Phrase each lesson as an instruction a future session can act on, quote the evidence it came
               from, and give an honest confidence. Zero lessons is a valid answer.

               Return only the structured output.
               """
                .formatted(repo, issueNumber, prUrl == null ? "(none opened)" : prUrl, truncate(outcome, 4000), truncate(feedback, 4000));
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
               Status check %d of %d from the menD orchestrator: you appear to be blocked.

               If you are waiting on a human decision, state the decision you need in one sentence and finish
               with remediated=false and a blocked_reason. If you are not actually blocked, continue and open
               the pull request.
               """
                .formatted(nudgeNumber, maxNudges);
    }

    /**
     * menD's persisted profile of the repository, so a session starts from what is already known
     * rather than rediscovering the codebase. Omitted entirely when there is no profile yet — an
     * empty heading would only invite the session to invent one.
     */
    private static String profileSection(String profile) {
        if (profile == null || profile.isBlank()) {
            return "";
        }
        return """

               --- what menD already knows about this repository ---
               %s
               --- end repository profile ---
               Treat this as a starting point, not as truth: if the code contradicts it, trust the code
               and say so in your output.
               """
                .formatted(profile.strip());
    }

    /** What earlier reviews taught menD about this repository, and about Devin's work in general. */
    private static String lessonsSection(String lessons) {
        if (lessons == null || lessons.isBlank()) {
            return "";
        }
        return """

               --- what human reviewers have already taught menD here ---
               %s
               --- end lessons ---
               These came from real review comments on earlier menD pull requests. Follow them unless the
               repository now contradicts them, in which case say so.
               """
                .formatted(lessons.strip());
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
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
