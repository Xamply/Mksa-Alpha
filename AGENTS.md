# MKSA Codex Operating Notes

- Always read `docs/session-handoff.md` before starting work in this repo.
- If `docs/session-handoff.md` contains an `in_progress` task without a matching completion entry, first decide whether the user wants to continue that task or explicitly supersede it.
- Before starting any non-trivial edit, long-running command, build, test, launch, or refactor, update `docs/session-handoff.md` with an `in_progress` checkpoint: intent, files likely touched, command to run, expected success signal, and recovery notes.
- When the task finishes, update `docs/session-handoff.md` to `completed` with the actual result and verification. Also update `docs/log.txt` / `docs/proyecto.md` for durable project milestones.
- Do not rely on chat history as the only record of current work. The handoff file is the recovery source for Codex/CLI/API restarts.
