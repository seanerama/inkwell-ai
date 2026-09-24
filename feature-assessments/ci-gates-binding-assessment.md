# Assessment: make the CI gates binding (Revisit 2026-09-24, proposal 1)

- **Source:** `docs/revisit/2026-09-24-revisit.md`, proposal 1 ("branch protection on
  `main` + release gated on the instrumented lane").
- **Date:** 2026-09-24
- **Decision:** **Not a stage.** Rerouted as two operator changes, each with its exact
  configuration below. No stage spec, contract, or ADR.

## Claim / reality

| Claim | Checked | Reality |
|---|---|---|
| `main` has no protection | `gh api …/branches/main/protection` → 404; rulesets `[]` | true |
| So red work can merge | `verity review merge` refuses unless CI is green; the reviewer role checks CI first | Mostly mitigated already. What is left is a manual UI merge or a direct `gh pr merge`, plus force-push or deletion of `main` |
| Branch protection is free here | Repo is PUBLIC | true |
| Protection would bind every actor | Collaborators: `seanerama` (admin) only; there is no `.verity/autonomy.yml` and no bot account. **22 of the last 40** first-parent commits on `main` are direct pushes (plan specs, ship STATUS/CHANGELOG, smoke results) | With `enforce_admins: true` every one of those pushes is rejected, because a fresh local commit cannot already have passed the required checks. With `false`, admins bypass it. **So protection binds PR merges and force-push/deletion, not direct pushes** |
| Releases publish on a red emulator lane | `.github/workflows/release.yml`: `server-image`, `android-apk` and `android-instrumented` run in parallel with no `needs:` between them. `server-image` pushes to GHCR (`push: true`) before its Trivy step | true. It happened on v0.0.13, where the APK was released while stage 16's instrumented test was red |
| It can be built as a stage | The planner must not plan a stage whose build writes `.github/workflows/` (containment-protected; ADR-0011, issue #203). Branch protection is a repo setting, not code | **Not stage-shaped** |

## Change A: branch protection on `main` (repo setting; operator)

Apply with one `PUT repos/seanerama/inkwell-ai/branches/main/protection`:

- `required_status_checks`: `{ strict: false, contexts: ["gates", "structure", "secret-scan"] }`.
  These are the three job names in `ci.yml`. `strict: false` because stages branch off a
  `main` that plan and ship commits keep advancing, and requiring an up-to-date branch
  would force a rebase on every one of those commits.
- `enforce_admins: false`. This keeps the plan/ship/STATUS direct-push flow working. Admin
  pushes show as "bypassed" in the GitHub audit log.
- `required_pull_request_reviews: null`. This is a one-person repo; the review is the
  `/verity:review` role, not a GitHub approval.
- `restrictions: null`, `allow_force_pushes: false`, `allow_deletions: false`.

**What it buys:** the merge button and `gh pr merge` refuse on red or pending CI unless
`--admin` is passed, and `main` can no longer be force-pushed or deleted. The deletion
guard matters here: on 2026-09-21 a reset of `main` in the shared checkout silently
dropped the Phase 5 plan.

**What it does not buy:** it cannot bind direct pushes by the only (admin) account. Real
enforcement for direct pushes would mean moving plan/ship commits to PRs. That changes the
Verity workflow and belongs upstream, not in this repo.

**Rollback:** `DELETE …/branches/main/protection`.

## Change B: gate release publishing on the instrumented lane (workflow edit; operator)

The minimal patch to `.github/workflows/release.yml`:

```yaml
  server-image:
    needs: android-instrumented   # nothing is pushed or released unless the emulator lane is green
    runs-on: ubuntu-latest
  …
  android-apk:
    needs: android-instrumented
    runs-on: ubuntu-latest
```

**Cost:** a release takes as long as the instrumented lane plus the image build, instead
of the slowest of the three. On v0.0.19 (run 36029115120) that is 4 + 2 = about 6 min,
up from about 4 min.

**Not included, possible later:** moving the GHCR push after Trivy (build with `load: true`,
scan, then push). Trivy failures have not happened in practice, and splitting a
multi-arch build/push is a larger workflow change.

**Rollback:** remove the two `needs:` lines.

## Why not a stage

Change A has no code, and Change B is a CI-workflow edit that the planner must not route
through a build stage. Both are small, reversible operator changes. Change B gets its own
commit on `main`, and the next tag exercises it.

## Follow-ups

- If the Verity autonomy worker is ever enabled with a non-admin bot account, direct pushes
  become bindable. Revisit `enforce_admins` then.
- Revisit proposals 2, 3 and 9 are code changes and stage-shaped, and they come next through
  `/verity:plan`.
