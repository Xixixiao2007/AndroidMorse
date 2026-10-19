# Agent notes — AndroidMorse

## Agent skills
This workspace runs Matt Pocock's AI-coding workflow (ask-matt).
- Start from `CONTEXT.md` (verified facts + glossary) and `docs/adr/` (decisions worth not re-litigating).
- Idea → ship flow: grilling → `to-spec` → `to-tickets` → one `implement` per ticket → `code-review` → push.
- Issue tracker: **not yet configured** — engineering-skill setup runs the first time a tracker-dependent
  skill (to-spec / to-tickets / triage) is used.

## Repo map
- `upstream/MorseRunnerCE/` — read-only reference: MRCE 1.85.4 source (MPL-2.0, Delphi/VCL). Never edit.
- `docs/` — ADRs and specs.
- `.scratch/` — session snapshots, batch state, run logs (not product code).

## Hard constraints learned during recon
- The original is Delphi/VCL and cannot be compiled here (no Delphi, no FPC/Lazarus).
  A port is a **rewrite**, not a build.
- `github.com` needs the local proxy; `git` is preconfigured for `127.0.0.1:7892`.
- Do not make credentials, callsigns, or personal data part of committed source.
