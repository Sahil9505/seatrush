# Prompt templates (copy, fill, paste). Precise, not polite.

## 1. Start of session
Read AGENTS.md and docs/PROGRESS.md. Task: <TASK ID + one line>.
Files to touch: <A, B>. Do not touch: <C>.
Behavior: <what it must do>.
Done when: <named test passes / endpoint returns X>.
First give a plan in max 8 bullets. Wait for my OK before editing.

## 2. Implement after plan approved
Implement step 1 only. Minimal edits. Run <test command>. Summarize changes in max 5 lines.

## 3. End of session
Update docs/PROGRESS.md (done, next, known issues, last session summary), under 15 lines.
Append to docs/DECISIONS.md only if we made a new decision. Tick the task in docs/TASKS.md. Stop.

## 4. Debugging (two-strike)
Test <name> fails. Error (trimmed): <paste 20-30 lines>. Relevant file: <path>.
Give hypothesis first, then fix. If the same error persists after one fix attempt, stop and report.

## 5. Review only (cheap, high value)
Review this diff for: race conditions, missing idempotency, seats_taken invariant violations.
List issues only. Do not edit files.

## 6. Resume after a long or confused thread (new conversation)
Read AGENTS.md and docs/PROGRESS.md. Previous attempt on <task> got stuck on: <1-2 lines>.
Start fresh on <task>, touching only <files>.

## 7. Model picker reminder (Rule 3)
Boilerplate/CSS/forms/DTOs -> cheaper model.
Booking, Lua, idempotency, concurrency tests, hard bugs -> strongest model.
