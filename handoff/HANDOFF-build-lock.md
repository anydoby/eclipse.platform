# Handoff: Eclipse save/edit blocking — root cause found, two fixes pushed; next: verify the platform patch compiles

**Date:** 2026-11-13 13:44 · **Workspace:** `~/ws` (several repos, see below) · **User:** Sergii Zolotarov

The user's Eclipse blocks on every save, every file open, and even on typing, with the dialog
"User Operation is Waiting … waiting for 'Building' to complete". Work spans three repos:
`m2e-core` (his fork), `eclipse.platform` (his fork), and `infra` (read only, for diagnosis).

## Read first (source of truth, don't restate)

- m2e test PR: <https://github.com/anydoby/m2e-core/pull/1> (private test PR, do not merge; description was updated this session)
- m2e input filter branch (pushed): `anydoby/m2e-core` branch `skip-non-input-deltas`, commit `e2ca22ab`
- Platform yield patch (pushed): `anydoby/eclipse.platform` branch `build-yield-enforcement`, commit `4c49d41500`
- Local worktrees and their state: see "Gotchas / environment" below

## Root cause (verified this session)

The chain, all read from source:

1. **The lock is workspace-wide.** `AutoBuildJob` sets `setRule(workspace.getRoot())`
   (`AutoBuildJob.java:62`), and `Rules.buildRule()` returns the workspace root
   (`Rules.java:57-59`, with the comment "team hook currently cannot change this rule").
   So one project's build blocks every project. This answers the user's question about why a
   `cron` edit waits on a `connectapi-app` build.
2. **During a builder, the global `ILock` is NOT the blocker.** `BuildManager.basicBuild` calls
   `getWorkManager().beginUnprotected()` before invoking the builder, and `beginUnprotected()`
   releases the whole `ILock` (`WorkManager.java:106-112`). The remaining blocker is the
   **job-level scheduling rule** (root), plus the builder's own `getRule()`, which defaults to
   the workspace root (`IncrementalProjectBuilder.java:534-536`).
   **Correction for the next agent:** earlier in the session I told the user the global `ILock`
   serialises everything. That was wrong and I corrected it. Do not repeat the original claim.
3. **The platform already asks the build to stop.** `Workspace.prepareOperation`
   (`Workspace.java:2444-2453`) calls `buildManager.interrupt()` when the incoming rule conflicts
   with the build rule. The request is advisory only; a builder that never checks it runs to its end.
4. **What actually held the lock:** `TranslationGeneratorMojo` (Booking's
   `booking-translation-maven-plugin`), blocked on `translateapi.booking.com`.
   `TRANSLATE_API_CLIENT_TIMEOUT_MS = 1800000` — **30 minutes** —
   in `~/ws/infra/libs/translation/codegen/src/main/java/com/booking/translation/codegen/TranslateapiCodegenClient.java:46`.
   Evidence: 99 of 99 thread dumps taken between 12:07 and 12:11 showed the build thread parked in
   `CompletableFuture.get()` inside `fetchRawTranslations`; one build thread had run 272 s.
5. **A cancelled builder silently loses its delta.** The `finally` block in `BuildManager.basicBuild`
   (around `BuildManager.java:316-331`) records the current tree as the last built tree **even when
   the builder was cancelled**, unless the builder called `rememberLastBuiltState()`. So any
   externally forced cancel must preserve that state, or the skipped changes never reach a later build.
6. **JDT already solves this per-builder.** `BuildNotifier.java:44-47` has
   `millisecondsBeforeCancelAutoBuild` (default 1000) and `millisecondsBeforeInterruptAutoBuild`
   (default 3000). Strong precedent for doing the same centrally.

## What changed this session

### Code, pushed

- **`anydoby/m2e-core` `skip-non-input-deltas` (`e2ca22ab`)** — one commit on top of the user's
  `build-interrupt-test`. `MavenBuilderImpl.hasRelevantDelta` now treats a change as relevant only
  for the project `pom.xml`, anything under `.mvn/`, or a compile-source / test-source / resource /
  test-resource folder. A YAML or Markdown save no longer starts the Maven build.
  **Known limitation:** the check sits inside `MavenBuilderImpl.build()`, which runs only *after*
  `MavenBuilder.BuildMethod.execute()` has refreshed the project facade, loaded the `MavenProject`,
  resolved the lifecycle mapping and computed every build participant. So it skips the mojos but
  still pays that preamble under the lock. It belongs earlier, in `MavenBuilder` — see Next steps.
- **`anydoby/eclipse.platform` `build-yield-enforcement` (`4c49d41500`)** — items 1 and 2 of the
  design the user approved. New `BuilderYieldEnforcer` (in `org.eclipse.core.internal.events`)
  watches an auto-build while one builder runs: cancels the progress monitor after 1 s, interrupts
  the build thread after 3 s, and logs a WARNING naming the builder and project after 3 s.
  Thresholds are system properties (`org.eclipse.core.resources.millisecondsBefore*`), each
  disabled by `<= 0`. `BuildManager` calls `currentBuilder.requestRememberState()` (new
  package-private method on `InternalBuilder`) when the enforcer forced the yield, which fixes
  hazard 5 above. **This has NOT been compiled** — see Next steps.

### Earlier in the session, also pushed

- `e2b9feb8` "Interrupt a running build participant when the build must stop" was merged into
  `build-interrupt-test` and pushed. The user then rewrote it into `ParticipantInterrupter` and made
  it opt-in; his `72eb70f8` is now the tip.

### The user's machine (all reversible, see Gotchas)

- **JDK `cacerts`:** added two Booking.com CA certs so Maven can reach `repo.eclipse.org`.
- **`eclipse.ini`:** added `-Dm2e.showExperimentalFeatures=true` so the interrupt checkbox appears.
- **`bundles.info`:** repointed m2e core to `2.8.0.20261113-1038` (input filter + interrupt) and
  m2e UI to `2.4.201.20261111-2123`.
- **Workspace m2e lifecycle mapping:** created
  `~/ws/.metadata/.plugins/org.eclipse.m2e.core/lifecycle-mapping-metadata.xml` setting
  `runOnIncremental=false` for `booking-translation-maven-plugin`. This is the single biggest
  practical win and it needs no repo change.
- **Workspace m2e logback:** added a DEBUG logger for `MavenBuilderImpl` to get per-participant
  timings. **It produced no output** and the m2e log files under
  `~/ws/.metadata/.plugins/org.eclipse.m2e.logback/` are unchanged since August. Unexplained; the
  thread-dump sampler was used instead.
- **`~/ws/infra`:** created a local, git-ignored `libs/translation/codegen/pom.xml` (sb4 / Java 25)
  so the codegen module opens in Eclipse. It compiles. Also added that path to `.git/info/exclude`.

## Decisions and why

- **Workspace-level lifecycle mapping, not a pom change:** the user is mid-feature on
  `connect-api` branch `feature/METAAP-2546-pricing-publisher-channels` with uncommitted work; a pom
  edit would land in that diff. The workspace file is local and invisible to git.
- **Interrupt left opt-in:** the user deliberately made it experimental and off by default in his
  fork. Do not change the default without asking him.
- **Thread interrupt as stage 2, not stage 1:** cancelling the monitor is the documented cooperative
  path and most builders honour it. The interrupt is the fallback for a builder blocked in a wait.
- **Platform patch in a separate worktree:** `~/ws/eclipse.platform` still holds 7 **staged,
  uncommitted** files for an unrelated branch (`launch-wait-for-families`). Keep them untouched.
- **Fine-grained locking rejected as the first move:** the tree (`Workspace.tree` /
  `operationTree`) is a single global structure and deltas are a diff of two of its snapshots, so
  narrowing rules alone would be unsafe. Enforced yielding gives most of the benefit for far less risk.

## Next steps (priority order)

1. **Verify the platform patch compiles.** `cd ~/ws/eclipse-platform-yield/resources/bundles/org.eclipse.core.resources`
   then `~/.m2/wrapper/dists/apache-maven-3.9.16/b37572d3/bin/mvn -B clean package -DskipTests`.
   **Do not pass `-U`** — it forces a full re-resolution of the Tycho target platform through the
   company mirror and took over 10 minutes before I killed it. If a download is truncated and
   cached, delete just that artifact directory under `~/.m2/repository` and retry without `-U`.
   The risky points to confirm: `message` is in scope at the `BuilderYieldEnforcer.start(...)` call
   site, and `autoBuildJob` is reachable there (it is `final AutoBuildJob autoBuildJob` at
   `BuildManager.java:162`).
2. **Confirm the current Eclipse state with the user** before changing anything else. Three things
   are unverified: whether he ticked the interrupt checkbox, whether he ran
   Maven > Update Project on `connectapi-app` so the new lifecycle mapping took effect, and whether
   saves are now fast. All three are assumptions.
3. **Move the m2e input filter earlier.** Put the relevance check in `MavenBuilder` before the
   facade refresh and `getBuildParticipants`. Sketch: in `MavenBuilder.build(...)`, for
   `INCREMENTAL_BUILD` / `AUTO_BUILD`, take the cached facade via
   `projectManager.getProject(project)` (a cheap registry lookup, no refresh), and if it is non-null
   and not stale, call the existing `hasRelevantDelta(facade, delta)` (make it `static`) and return
   early on `IRRELEVANT`. Keep the current early return in `MavenBuilderImpl` as a backstop.
4. **Raise the 30-minute timeout with the translation library owners.** That value is wrong for IDE
   use and hurts everyone, not just this user. `TranslateapiCodegenClient.java:46` in `~/ws/infra`.
5. **Open the upstream discussion.** The user asked where this belongs. Verified with `gh`:
   his fork `anydoby/eclipse.platform` has **Issues disabled** (GitHub's default for forks) and
   nobody upstream watches a fork, so a fork issue is the wrong venue. Upstream
   `eclipse-platform/eclipse.platform` has **both Issues and Discussions enabled**. Recommended:
   a GitHub **issue** for the concrete bug plus items 1 and 2 (link the pushed branch and open a
   draft PR against it), and a GitHub **Discussion** for the fine-grained-locking question, since
   that changes a documented contract. Eclipse Platform has no formal RFC process; architectural
   changes are discussed in issues and on `platform-dev@eclipse.org`. **Not done this session** —
   the user said to stop.
6. **Optional, the real structural fix (item 3).** `Job.yieldRule(IProgressMonitor)` exists
   (`Job.java:964`) and releases the job's scheduling rule, which fact 2 shows is the actual
   blocker. Yielding it around the builder call — at the point where `beginUnprotected()` already
   releases the `ILock` and the tree is immutable — would let a waiting save in without splitting
   the `ElementTree`. Needs care: nested `beginRule(builderRule)` is held there too, and
   `PRE_BUILD`/`POST_BUILD` are documented as once per build. Discuss before coding.

## Gotchas / environment

### Reverting the user's machine

All backups are in `~/ws/backups/eclipse-m2e-2026-11-10/`. Quit Eclipse before running any script.

| What | How to revert |
|---|---|
| m2e jars (current) | `revert-filter.sh` → state before the input-filter install |
| m2e jars (older steps) | `revert-ui-to-before.sh`, `revert-m2e-to-1550.sh`, `revert-m2e-to-build-interrupt.sh`, `revert-m2e-to-2.8.1.sh` |
| JDK `cacerts` | copy `cacerts.before` over the JDK's `lib/security/cacerts` |
| `eclipse.ini` | copy `eclipse.ini.before`, or delete the `-Dm2e.showExperimentalFeatures=true` line |
| m2e logback DEBUG | copy `logback.m2e.before` over the workspace logback XML |
| Lifecycle mapping | delete `~/ws/.metadata/.plugins/org.eclipse.m2e.core/lifecycle-mapping-metadata.xml` |

### Building m2e (hard-won recipe)

- Use **Maven 3.9.16**: `~/.m2/wrapper/dists/apache-maven-3.9.16/b37572d3/bin/mvn`.
  Maven 3.10.0 (on `PATH`) fails with `No implementation for MavenTargetLocationFactory was bound`.
- Two **local-only, never-commit** edits are needed in the build worktree:
  `target-platform/m2e-runtimes.target` → point the m2e 2.2.1 repo at `archive.eclipse.org`
  (the `download.eclipse.org` URL now 301-redirects and Tycho does not follow it), and
  `m2e-parent/pom.xml` → add `<skip>true</skip>` to the `tycho-baseline-plugin` configuration.
- Command: `mvn -B -pl org.eclipse.m2e.core -am clean package -DskipTests` (add
  `org.eclipse.m2e.core.ui` for the preference page).
- Installing: copy the jar into `~/.p2/pool/plugins/` and edit the matching line in
  `~/eclipse/jee-2026-06/Eclipse.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`.
  **`dropins/` does not work** on this install — Eclipse loads m2e from the shared p2 pool and
  ignores `dropins/`. Back up `bundles.info` first. Eclipse must be restarted; a running Eclipse
  keeps the old bundle.

### Worktrees (do not disturb)

| Path | Branch | State |
|---|---|---|
| `~/ws/m2e-core` | `build-interrupt-test` | at the user's `72eb70f8`, clean |
| `~/ws/m2e-input-filter` | `skip-non-input-deltas` | `e2ca22ab` pushed; 2 uncommitted build-only edits |
| `~/ws/m2e-build-interrupt`, `~/ws/m2e-build-latest` | detached | throwaway build worktrees, build-only edits |
| `~/ws/eclipse.platform` | `launch-wait-for-families` | **7 staged, uncommitted files**, unrelated and unbuilt — leave alone |
| `~/ws/eclipse-platform-yield` | `build-yield-enforcement` | `4c49d41500` pushed, clean |

`~/ws/eclipse.platform`'s `origin` is **upstream** `eclipse-platform/eclipse.platform`. The user's
fork is on remote `fork` in the `eclipse-platform-yield` worktree. Never push to `origin` there.

### Other traps

- `gh` is at `/opt/homebrew/bin/gh` and is authenticated as `anydoby`. This shell's `PATH` does not
  include `/opt/homebrew/bin`, so call it by full path. (I wrongly reported it as not installed early on.)
- The m2e DEBUG logger produced nothing. To see what a build is doing, sample instead:
  `jcmd <eclipse-pid> Thread.print` in a loop, keep only dumps containing `MavenBuilder`, and read
  the frames above `MojoExecutionBuildParticipant.build`. That is how the translation mojo was found.
- There is an unrelated, pre-existing `NoSuchMethodError` on
  `IMavenProjectFacade.getMojoParameterValue` when the installed m2e core is older than
  `m2e.apt.core` / `m2e.jdt` / `m2e.pde.connector`. The jars built from `build-interrupt-test` do
  declare that method, so the current install should not show it (**assumed, not verified** — check
  `~/ws/.metadata/.log` for `NoSuchMethodError` in the newest `!SESSION` block).
- A slow-mojo test project exists at `~/ws/m2e-slow-test` (five 3 s Ant sleeps, from the branch's
  `slow-builder-pom.xml`). Two quick saves should wait ~2 s with the interrupt on, ~15 s without.
  **Never run.**

## Suggested skills

- `glab-cli` / `gh`: only if the user asks to open the upstream issue or PR (next step 5).
- `src-cli`: to find other callers of `booking-translation-maven-plugin` before proposing the
  timeout change in `infra` (next step 4).
- `handoff`: when this work moves again.

## User preferences seen this session

- He splits a fix into separate pieces before offering them to maintainers; keep commits narrow and
  single-purpose.
- He does not want unsolicited MRs or PRs. Ask first.
- Use the Edit tool on repo files, not `sed`/`python`. I slipped twice with `sed` (on
  `m2e-runtimes.target` in a throwaway worktree, and on the Eclipse `bundles.info`, which is not a
  repo file). Do not repeat it on tracked files.
