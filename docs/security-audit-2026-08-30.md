# MineCICD Security Audit — 2026-08-30

**Project:** Bruderjulian/MineCICD (rewrite, src/ new, 3.0.0) — Paper 1.21+, Java 21, Gradle 9.7.0, JGit 6.10.0, org.json 20240303  
**Scope root:** H:/Meine_Daten/Source/Rewrites/MineCICD  
**Audited artefacts:** src/**/*, build.gradle.kts, settings.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradle/wrapper/*, .github/workflows/build.yml, .gitignore, src/main/java/resources/*, DESIGN.md, git history, file permissions, dependency tree.  
**Methodology:** manual review of every src/main + src/test file (25 Java + 5 resources), grep for secret/password/Runtime.exec/ProcessBuilder/deserialize/ObjectInputStream/MD5/SHA1/SecureRandom/AES/ECB/URIish/MessageDigest/Hmac, thread-safety, HTTP auth, path-traversal, Gradle/CI, CVE cross-check.

## Executive Summary

The 3.0 rewrite is a large improvement over srcOld: mandatory HMAC-SHA256, constant-time compare, replay window + nonce, body-size cap, authenticated SSE/status, single in-flight request, no client-supplied repo, allow-listed commands/scripts, native TLS, pure-Java git filters, fixed sed escaping, and removal of checkip.amazonaws.com leak. DESIGN.md contract is sound.

Residual risk is Medium-High, not Critical, but two logic bugs downgrade posture:

1. ControlServer.inFlight is never cleared — control API becomes permanently unavailable after first successful POST until restart (self-DoS). (ControlServer.java:44,164)
2. Commit-message CICD actions bypass per-action enable flags and command/script allowlists that protect the HTTP control API. Any git push writer can execute global-reload, restart, command, script even when admin disabled those actions. (CicdService.java:103-115 + CommitActions.java:75-133)

CI pipeline grants contents: write to PRs and uses deprecated release asset action, plus medium issues (unbounded seenNonces, weak file perms on secrets.filter, missing canonical-path check, TLS defaults) should be fixed before 3.0.0 tag.


## Findings by Severity

### Critical

#### C-01 — Permanent DoS of the Control API: inFlight atomic is never reset

* **File:** src/main/java/com/lemonlightmc/minecicd/http/ControlServer.java:44 (field `AtomicReference<String> inFlight`), :164-167
* **Description:** handlePost does `if (!inFlight.compareAndSet(null, request.requestId())) { 409 }` then `delegate.acceptRequest(...)` -> 202. No code ever calls `inFlight.set(null)` or `compareAndSet(requestId, null)`. CicdService.removeRequest (CicdService.java:304-306) only clears streams and controlStatus; CicdService.runActions (316-358) calls removeRequest but not inFlight. After one accepted deploy the API returns 409 for every subsequent deploy until restart. An attacker with one valid HMAC or a legitimate first deploy can permanently brick the deploy path.
* **Impact:** Complete denial of GitHub Actions deploy flow; requires manual restart. PendingStore resume path same lock remains held.
* **Recommendation:** Clear atomic at terminal transitions. Minimal patch:

```
in ControlServer, expose void clearInFlight(String id){ inFlight.compareAndSet(id, null); }
// delegate calls it from CicdService.runActions finally blocks or via callback
```

Consider moving inFlight ownership into CicdService alongside single-worker queue. Add unit test sending two sequential HMAC requests.

#### C-02 — Commit-message actions bypass control.actions.* enable flags and allowlists

* **Files:** src/main/java/com/lemonlightmc/minecicd/git/CommitActions.java:12,75-133, src/main/java/com/lemonlightmc/minecicd/CicdService.java:103-115 (processCommitActions), :360-407 (executeAction), src/main/java/com/lemonlightmc/minecicd/http/ControlSecurity.java:131-154 (validateActions), DESIGN.md:57-58,262-265.
* **Description:** HTTP control API enforces validateActions: disabled global-reload is 403, COMMAND/SCRIPT off by default and require exact-name allowlists plus isAllowedScriptName. processCommitActions iterates every CICD line from freshly-fetched commits and dispatches via executeAction without any call to validateActions or flag checks. Commit containing `CICD global-reload`, `CICD command:ban alice`, `CICD script:../../evil.sh` runs even when control.actions.global-reload: false, commands.enabled: false, scripts.enabled: false. Trust boundary collapses: anyone with git push to pinned git.repo (weaker principal than HMAC holder) gains equivalent RCE.
* **Impact:** Privilege escalation from repo writer -> arbitrary command/shell + reload/restart with server-process OS privileges.
* **Recommendation:** Gate commit actions through same policy object. Patch processCommitActions to call controlSecurity.validateActions(parsed) before executeAction. Log and skip disallowed actions rather than failing whole pull.

### High

#### H-01 — CI pipeline over-privileged and pull_request triggered

* **Files:** .github/workflows/build.yml:10-11 (permissions: contents: write), :6 (pull_request: unconditional), :70-74 (actions/upload-release-asset@v1 deprecated), :66-67 (printVersion interpolation).
* **Issues:**
  1. permissions: contents: write is top-level and applies to build job on pull_request from forks. Malicious PR can use GITHUB_TOKEN (write) to push branches or poison artifacts.
  2. on: pull_request without filter means external PRs execute ./gradlew build on default runner — supply-chain risk.
  3. upload-release-asset@v1 is deprecated and archived; upload_url is attacker-injectable.
  4. printVersion output interpolated into asset_path without sanitization.
* **Recommendation:** Set top-level permissions: contents: read; build job contents: read; release job contents: write with if: github.event_name == 'release' && github.repository == 'Bruderjulian/MineCICD'. Replace upload-release-asset@v1 with softprops/action-gh-release@v2. Pin actions to SHAs.

#### H-02 — Secrets material at rest is world-readable / not permission-hardened

* **Files:** src/main/java/com/lemonlightmc/minecicd/secrets/SecretManager.java:64-66,192-208 (writeMappingFile, Files.write default umask), :101-129 (.gitattributes + .git/config write), src/main/java/com/lemonlightmc/minecicd/MineCICDConfig.java:119-121,135-141 (plaintext git.pass, control.tls.password, control.secret), src/main/java/resources/config.yml:2-5,39-46.
* **Issues:** secrets.filter (format base64(key):base64(value) per line at SecretManager.java:199) written via Files.write/FileOutputStream without PosixFilePermissions (600). On shared hosts other users can read it. Same for secrets.yml. config.yml stores git.pass, control.secret, control.tls.password in plaintext with default perms. .git/config filter section contains absolute path to secrets.filter.
* **Recommendation:** After writing secrets.filter/secrets.yml/config.yml set POSIX perms rw------- (Files.setPosixFilePermissions, Windows fallback File.setReadable/setWritable). Document chmod 600 on install and warn if file is world-readable at startup (similar to existing <32 bytes lint).

#### H-03 — PendingRequest idempotency overwrites running state, losing progress

* **File:** src/main/java/com/lemonlightmc/minecicd/CicdService.java:279-290 (acceptRequest).
* **Description:** On retry (same requestId, status == RUNNING) code creates fresh PendingRequest(index=0) and unconditionally pendingStore.save(request), overwriting existing file that carried index > 0. Retried HMAC (replay within window — or after restart where seenNonces empty, see M-01) resets execution to first action. Combined with inFlight bug this also never clears old marker.
* **Recommendation:** If existing.status() == RUNNING, do not overwrite; instead resume from existing. Only save new request when existing.isEmpty().

#### H-04 — ControlTls trusts any valid keystore without protocol/cipher constraints and keeps password in String

* **File:** src/main/java/com/lemonlightmc/minecicd/http/ControlTls.java:17-38, src/main/java/com/lemonlightmc/minecicd/MineCICD.java:122.
* **Issues:** SSLContext.getInstance(TLS) enables all protocols JDK offers, including TLS 1.0/1.1 on older runtimes. No setEnabledProtocols TLSv1.2/1.3, no cipher restriction, no CRL/OCSP. Password held as String then char[] and stays in heap; prefer char[]/Destroyable and zero after use. FileInputStream(keystorePath) not checked for world-readable perms.
* **Recommendation:** Restrict protocols/ciphers or document JDK 21 default jdk.tls.disabledAlgorithms must remain intact and add startup warning if keystore file is world-readable. Zero pass array in finally { Arrays.fill(pass, '\0'); }.


### Medium

#### M-01 — Unbounded, non-expiring seenNonces set -> memory leak and cross-restart replay

* **File:** src/main/java/com/lemonlightmc/minecicd/http/ControlSecurity.java:25,88-91.
* **Description:** seenNonces = ConcurrentHashMap.newKeySet() grows forever. Comment promises replay guard but never evicts; set not cleared per replayWindowSeconds (300s default). Busy server can hold millions of nonces -> heap exhaustion. On restart set is empty -> attacker who captured still-valid request (within 300s) can replay once after restart because Math.abs(now - timestamp) still passes.
* **Recommendation:** Replace with time-windowed cache: Cache with expireAfterWrite(replayWindowSeconds+60, SECONDS) or ConcurrentHashMap<String,Long> with periodic sweep. Clear on runActions completion. Bound to 10k entries with RejectException Nonce flood.

#### M-02 — Permissive CORS on authenticated SSE stream

* **File:** src/main/java/com/lemonlightmc/minecicd/http/ControlServer.java:226 (Access-Control-Allow-Origin: *).
* **Description:** handleStream returns ACAO: * while requiring HMAC. Any web origin that can obtain one-time HMAC (e.g., via XSS on operator dashboard holding control.secret) can stream that requestId from victim browser. No Vary: Origin, no Allow-Credentials.
* **Recommendation:** Remove header entirely (SSE is server-to-server). If browser polling required, echo Origin only when in allowlist (empty default).

#### M-03 — Script file resolution lacks canonical-path confinement

* **File:** src/main/java/com/lemonlightmc/minecicd/scripts/ScriptRunner.java:133-150 (resolve), ControlSecurity.java:161-175 (isAllowedScriptName).
* **Description:** ScriptRunner.resolve checks indexOf('/')/\, .., leading ., length, but returns file.normalize() without verifying file.startsWith(scriptsDir.normalize()). Attacker who can create symlink inside plugins/MineCICD/scripts/ pointing outside (e.g., scripts/link -> /) could then request script:link/etc/passwd if name passes isAllowedScriptName (only checks chars). Control API rejects .. substring and /, but commit-message actions not validated (C-02) so ScriptRunner is last defense.
* **Recommendation:** After normalize add:

```
Path normalized = file.normalize().toAbsolutePath();
if (!normalized.startsWith(scriptsDir.normalize().toAbsolutePath())) return null;
if (!Files.isRegularFile(normalized) || Files.isSymbolicLink(normalized)) // optionally reject symlinks or resolve via toRealPath
```

#### M-04 — ScriptRunner.runShell inherits server-process OS privileges; no sandboxing

* **File:** src/main/java/com/lemonlightmc/minecicd/scripts/ScriptRunner.java:101-131 (ProcessBuilder sh -c / cmd /c), src/main/java/resources/example_script.sh:5, DESIGN.md:257-258.
* **Description:** Any `! ` line in script (tracked in git.repo) executes with OS user of Minecraft server. Documented as host-level trust grant but docs do not warn that scripts.allow empty still permits execution via commit actions (C-02). No timeout, no working-dir restriction, no env sanitization.
* **Recommendation:** Keep scripts.enabled: false default; fix C-02. Add per-script timeout (Future.get + destroyForcibly), set pb.directory(serverRoot), clear env then allowlist PATH/HOME, and log full command + exit code (already INFO at :117).

#### M-05 — GitService.normalizeTrackingEntry symlink/TOCTOU edge

* **File:** src/main/java/com/lemonlightmc/minecicd/git/GitService.java:676-694, GitIgnoreEditor.java:38-89.
* **Description:** normalizeTrackingEntry checks Files.isDirectory(serverRoot.resolve(p)) to decide trailing /. Between that check and editor.add/remove + commitIgnoreChange, path could be replaced with symlink to outside serverRoot (TOCTOU). Subsequent git.add().addFilepattern(".") (GitService.java:91) stages everything, so attacker gains commit of external files. Segment .. check mitigates naive traversal, but not symlink race.
* **Recommendation:** Do not use Files.isDirectory for decision — rely solely on syntactic p.endsWith("/"). Resolve serverRoot.resolve(p).normalize() and verify startsWith(serverRoot) before staging. Commit with addFilepattern(entry) rather than ".".

#### M-06 — Messages.escape / MiniMessage via error messages / SSE broadcast

* **File:** src/main/java/com/lemonlightmc/minecicd/messaging/Messages.java:184-202 (escape, fill), MineCICDCommand.java:182,230, CicdService.java:96,501-508.
* **Description:** Messages.fill escapes each placeholder via Messages.escape (replace \, <, >), which is correct. However CicdService.safeMessage returns root-cause message verbatim and inserts as {error} -> escaped. Raw git error may contain attacker-controlled strings (remote URL https://evil/<red>hacked</red>) after escaping harmless, but logSingleCommit inserts entry.message() escaped only at display (MineCICDCommand.java:198) while ProgressStream.broadcast (CicdService.java:322) includes action.toString() containing user-supplied command argument unescaped via SSE data: action:command:say <red>owned. GitHub Action log prints verbatim could interpret MiniMessage if rendered — low risk but inconsistent.
* **Recommendation:** Always escape user-supplied strings before ProgressStream.broadcast and ControlStatus.error persistence. Add Messages.escape to safeMessage output and CommitActions argument storage.

#### M-07 — Weak / absent rate-limiting and header DoS

* **File:** src/main/java/com/lemonlightmc/minecicd/http/ControlServer.java:289-307 (readBody with maxBodyBytes 65536, headers uncapped), :84-85 (newFixedThreadPool(4)), ControlSecurity.java:27-31 (no brute-force counter).
* **Description:** readBody caps body at maxBodyBytes (DoS guard), but HTTP headers (X-MineCICD-Signature/Timestamp/Nonce) have no size limit — 64 KB per header can exhaust memory. Thread pool is 4; 4 concurrent slow-read bodies block API. No per-IP rate limiting, no backoff on 401. HMAC failures are constant-time (MessageDigest.isEqual) but timing + 300s window still allows online brute force at line-rate.
* **Recommendation:** Reject headers >4 KB, add per-IP failure counter with 429 after 5/10s, move body read to with-timeout (readNBytes + Future 5s).

#### M-08 — Dependency freshness: JGit and org.json

* **File:** gradle/libs.versions.toml:4-5 (jgit = 6.10.0.202406032230-r, json = 20240303).
* **Issues:** org.eclipse.jgit:6.10.0 (June 2024) not latest 7.2.0 and transitive slf4j-api:1.7.36 (EOL) + commons-codec:1.17.0. JGit 6.x had CVE-2023-4759, CVE-2024-3247 (credential leak on redirect). While fixes in 6.7+, 6.10.0 patched but misses recent fixes. org.json:json:20240303 no CVEs since CVE-2023-5072 but vulnerable to recursive JSON bombs; maxBodyBytes mitigates but ControlRequest.parse (ControlRequest.java:35-39 new JSONObject(body)) can stack-overflow on deeply nested JSON within 65 KB.
* **Recommendation:** Bump jgit to 7.2.0.20250304-r and slf4j-api to 2.0.12; json to 20250517 or replace with jackson-databind with StreamReadConstraints depth 32. Add dependencyCheckAnalyze to CI. Verify gradle-wrapper.jar SHA256 7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d matches official (checked 2026-08-30 — matches).


### Low

#### L-01 — HMAC canonical form ambiguous on | in body

* **File:** ControlSecurity.java:78-80 (String canonical = timestamp+"|"+nonce+"|"+requestId+"|"+new String(body,UTF_8)), ControlServer.java:194-200.
* **Description:** body may contain | (JSON {"message":"a|b"}) making canonicalization non-injective. Two (nonce,body) pairs could collide in | count, though HMAC still over bytes, not forgery, but violates header|header|id|body contract. Prefer length-prefix or hash body separately.
* **Fix:** hmac(secret, (ts+"|"+nonce+"|"+requestId+"|"+sha256(body)).getBytes(UTF_8)) or timestamp + 0x00 + nonce.

#### L-02 — Math.abs(now - timestamp) treats far-future and far-past identically

* **File:** ControlSecurity.java:71-74.
* **Description:** if (Math.abs(now - ts) > window) rejects both drifts >300s. Attacker who can skew clock can replay future timestamps; abs masks direction. No iat/exp separation.
* **Fix:** Reject ts - now > window (future) and now - ts > window (past) with distinct messages; consider `if (ts > now + leeway) reject`.

#### L-03 — branchMatches(null) returns true

* **File:** ControlServer.java:180-192.
* **Description:** if (requested == null) return true; plus branch = configured fallback means omitted branch always passes. Intended but branchMatches(null) early true defies allowlist semantics and is hard to audit.
* **Fix:** Normalize `if (requested == null) requested = cfg.git().branch();` then strictly `requested.equals(cfg.git().branch()) || cfg.control().branches().contains(requested)`.

#### L-04 — SSE ProgressStream holds HttpExchange indefinitely (resource leak)

* **File:** ProgressStream.java:18-44,47-56, ControlServer.java:238-254 (daemon waiter Thread.sleep(1000) until isClosed).
* **Description:** Each handleStream holds exchange (blocking thread + socket) until stream.close(). If deploy contains restart (drops SSE), waiter keeps socket open until restart. No idle timeout; unbounded exchanges list per requestId (attacker with valid HMAC can open many /stream?requestId=... connections).
* **Fix:** Set X-Accel-Buffering: no, close idle >60s, cap exchanges.size() <=4, and close() on CicdService.runActions finally.

#### L-05 — PendingStore swallows JSON parse errors silently

* **File:** PendingStore.java:58-61,74 (catch Exception ignored).
* **Description:** Corrupted pending/*.json files are ignored, hiding tampering or disk errors. Attacker who truncates pending file forces silent resume skip.
* **Fix:** Log warning with filename + exception message at ignored sites.

#### L-06 — GitService URIish may allow file:// or ext:: transports

* **File:** GitService.java:355 (new URIish(url) where url = config.get().git().repo()).
* **Description:** URIish supports ssh://, scp-like git@host:path, file:///tmp/evil. File URL would point fetch at local bundle if attacker can write to FS. Not remotely exploitable because repo not client-supplied, but operator copy-pasting malicious URL could trigger.
* **Fix:** Validate git.repo at load: if (!url.matches("https://.*|ssh://.*|git@.*:.*")) reject; forbid file:// and ext::.

#### L-07 — actions/upload-artifact@v4 uploads thin jar + sources

* **File:** .github/workflows/build.yml:40-42.
* **Description:** path: build/libs/*.jar uploads -thin.jar, -sources.jar, shadow jar. Thin jar lacks bundled JGit/json — not runnable — artifact listing may confuse operators. No retention restriction.
* **Fix:** Narrow to build/libs/MineCICD-*.jar with if-no-files-found: error and retention-days: 14.

#### L-08 — .gitignore gaps at rewrite repo root

* **File:** .gitignore:1-33, src/main/java/resources/.gitignore:12-15 (/secrets.ym**), src/main/java/resources/config.yml.
* **Description:** Repo-root .gitignore ignores .gradle/, build/, IDE files but not secrets.yml / secrets.filter / plugins/MineCICD/config.yml. Runtime template .gitignore correctly ignores /plugins/MineCICD/** and /secrets.ym**, but developers could accidentally git add secrets.yml to rewrite repo.
* **Fix:** Add to repo-root .gitignore: /secrets.yml, /secrets.filter, /plugins/MineCICD/.

### Info / Defense-in-Depth Notes (positive)

* HMAC bound to headers+body, constant-time, replay guard — ControlSecurity.java:57-92 uses MessageDigest.isEqual and HmacSHA256 correctly.
* No client-supplied repo — CicdService.acceptRequest only receives requestId, actions, branch; server pins config.get().git().repo() (GitService.java:349). Closes historic RCE via malicious remote.
* Body-size cap — ControlServer.readBody (:289-307) enforces maxBodyBytes (65536) before JSONObject parsing — mitigates JSON bomb.
* Per-action enable flags + exact-name allowlists, fail-closed — ControlSecurity.validateActions (:131-154) and isAllowedScriptName (:161-175) strict (exact firstToken).
* TLS path — ControlTls.build loads JKS/PKCS12 and refuses missing keystore; MineCICD.startControlServer (MineCICD.java:102-133) refuses empty secret and lints <32 bytes — good fail-closed.
* Single worker queue — Threads.singleDaemonWorker (:20-21) serializes git/disk work; main-thread marshaling via getScheduler().runTask avoids async Bukkit access.
* JGit filter — SecretManager + ReplaceFilter (ReplaceFilter.java:37-83, SecretManager.java:96-166) avoids bundled .exe and handles special chars via base64; placeholder leaks no raw key.
* Messages migration — Messages.migrateLegacy (:87-138) and escape correctly neutralize MiniMessage injection for most placeholders.
* Gradle wrapper verified — gradle-wrapper.properties pins gradle-9.7.0-bin.zip with validateDistributionUrl=true; local gradle-wrapper.jar SHA256 7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d matches official (checked 2026-08-30).
* .gitignore server template — src/main/java/resources/.gitignore correctly ignores ALL files (* + !*/) and managed section between BEGIN/END MARKER; priority-excludes /plugins/MineCICD/**.

## Detailed Review Notes

### Source inventory (every file read)

build.gradle.kts:1-78, settings.gradle.kts:1, gradle.properties:1-3, gradle/libs.versions.toml:1-15, gradle/wrapper/gradle-wrapper.properties:1-9, .gitignore:1-33, .github/workflows/build.yml:1-75, .vscode/settings.json:1-3, src/main/java/resources/config.yml:1-80, secrets.yml:1-14, plugin.yml:1-33, messages.yml:1-144, example_script.sh:1-7, .gitignore:1-71, MineCICD.java:1-180, MineCICDConfig.java:1-169, CicdService.java:1-509, http/ControlServer.java:1-354, ControlSecurity.java:1-193, ControlTls.java:1-39, ControlRequest.java:1-70, ProgressStream.java:1-61, ControlStatus.java:1-41, git/GitService.java:1-704, CommitActions.java:1-134, GitIgnoreEditor.java:1-110, GitException.java:1-18, Results.java:1-32, scripts/ScriptRunner.java:1-169, secrets/SecretManager.java:1-209, secrets/ReplaceFilter.java:1-117, command/MineCICDCommand.java:1-237, pending/PendingStore.java:1-83, pending/PendingRequest.java:1-132, messaging/Messages.java:1-228, messaging/Msg.java:1-63, bossbar/BossBars.java:1-79, util/Ids.java:1-20, util/Threads.java:1-23, plus tests ControlSecurityTest.java:1-122, ReplaceFilterTest.java:1-77.

### Dependency + wrapper check

./gradlew dependencies --configuration runtimeClasspath resolves:

```
org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r
  com.googlecode.javaewah:JavaEWAH:1.2.3
  org.slf4j:slf4j-api:1.7.36
  commons-codec:commons-codec:1.17.0
org.json:json:20240303
```

Recommend bumping as in M-08. Wrapper distributionUrl=https://services.gradle.org/distributions/gradle-9.7.0-bin.zip over HTTPS, validateDistributionUrl=true — good. No maven http repos.

### Git history

git log --oneline -20 shows c75cd80 remove old source & rewrite incoming etc. No secrets in patches grepped (secret/password/token only dummy database_password: password in templates). Origin https://github.com/Bruderjulian/MineCICD.git, upstream git@github.com:finder1793/MineCICD.git. No exposed PATs.

### CI

actions/checkout@v4, actions/setup-java@v4, gradle/actions/setup-gradle@v4 are current majors (good). Need SHA pinning and least-privilege as in H-01.

## Input Validation Checklist

| Area | Input | Validation | Verdict |
|---
#### M-02 — Permissive CORS on authenticated SSE stream

* **File:** src/main/java/com/lemonlightmc/minecicd/http/ControlServer.java:226 (Access-Control-Allow-Origin: *)
* **Description:** handleStream returns ACAO: * while requiring HMAC. Any web origin that can obtain one-time HMAC can stream that requestId from victim browser.
* **Recommendation:** Remove header entirely (SSE is server-to-server). If browser polling required, echo Origin only when in allowlist.

#### M-03 — Script file resolution lacks canonical-path confinement

* **File:** src/main/java/com/lemonlightmc/minecicd/scripts/ScriptRunner.java:133-150 (resolve), ControlSecurity.java:161-175
* **Description:** ScriptRunner.resolve checks indexOf/ .. leading . but returns file.normalize() without verifying file.startsWith(scriptsDir). Symlink inside scripts/ could escape.
* **Recommendation:** After normalize check `normalized.startsWith(scriptsDir)` and reject symlinks via toRealPath.

#### M-04 — ScriptRunner.runShell inherits server-process OS privileges; no sandboxing

* **File:** src/main/java/com/lemonlightmc/minecicd/scripts/ScriptRunner.java:101-131 (ProcessBuilder sh -c / cmd /c), src/main/java/resources/example_script.sh:5, DESIGN.md:257-258.
* **Description:** Any `! ` line in script (tracked in git.repo) executes with OS user of Minecraft server. Documented as host-level trust grant but docs do not warn that scripts.allow empty still permits execution via commit actions (C-02). No timeout, no working-dir restriction, no env sanitization.
* **Recommendation:** Keep scripts.enabled: false default; fix C-02. Add per-script timeout, set pb.directory(serverRoot), clear env then allowlist PATH/HOME, and log full command + exit code.

#### M-05 — GitService.normalizeTrackingEntry symlink/TOCTOU edge

* **File:** src/main/java/com/lemonlightmc/minecicd/git/GitService.java:676-694, GitIgnoreEditor.java:38-89.
* **Description:** normalizeTrackingEntry checks Files.isDirectory(serverRoot.resolve(p)) to decide trailing /. Between that check and editor.add/remove + commitIgnoreChange, path could be replaced with symlink to outside serverRoot (TOCTOU). Subsequent git.add().addFilepattern(".") stages everything.
* **Recommendation:** Rely solely on syntactic p.endsWith("/"). Resolve serverRoot.resolve(p).normalize() and verify startsWith(serverRoot) before staging. Use addFilepattern(entry) rather than ".".

#### M-06 — Messages.escape / MiniMessage via error messages / SSE broadcast

* **File:** src/main/java/com/lemonlightmc/minecicd/messaging/Messages.java:184-202 (escape, fill), MineCICDCommand.java:182,230, CicdService.java:96,501-508.
* **Description:** Messages.fill escapes placeholders via Messages.escape (\, <, >) which is correct. However ProgressStream.broadcast (CicdService.java:322) includes action.toString() containing user-supplied command argument unescaped via SSE. Commit containing `CICD command:say <red>owned` becomes data: action:command:say <red>owned and GitHub Action log prints verbatim.
* **Recommendation:** Always escape user-supplied strings before ProgressStream.broadcast and ControlStatus.error persistence.

#### M-07 — Weak / absent rate-limiting and header DoS

* **File:** src/main/java/com/lemonlightmc/minecicd/http/ControlServer.java:289-307 (readBody with maxBodyBytes 65536, headers uncapped), :84-85 (newFixedThreadPool(4)), ControlSecurity.java:27-31.
* **Description:** readBody caps body at maxBodyBytes, but HTTP headers (X-MineCICD-Signature/Timestamp/Nonce) have no size limit — 64 KB per header can exhaust memory. Thread pool 4; 4 concurrent slow-read bodies block API. No per-IP rate limiting, no backoff on 401. HMAC failures are constant-time but brute force at line-rate still possible.
* **Recommendation:** Reject headers >4 KB, add per-IP failure counter with 429 after 5/10s, move body read to with-timeout (5s).

#### M-08 — Dependency freshness: JGit and org.json

* **File:** gradle/libs.versions.toml:4-5 (jgit = 6.10.0.202406032230-r, json = 20240303).
* **Issues:** org.eclipse.jgit:6.10.0 (June 2024) not latest 7.2.0 and transitive slf4j-api:1.7.36 (EOL). JGit 6.x had CVE-2023-4759, CVE-2024-3247. org.json:json:20240303 vulnerable to recursive JSON bombs; maxBodyBytes mitigates but ControlRequest.parse (ControlRequest.java:35-39 new JSONObject(body)) can stack-overflow on deeply nested JSON within 65 KB.
* **Recommendation:** Bump jgit to 7.2.0 and slf4j to 2.0.12; json to 20250517 or replace with jackson-databind with StreamReadConstraints depth 32. Add dependencyCheckAnalyze to CI. Wrapper SHA256 7a9ce74c... matches official (checked 2026-08-30).

### Low

#### L-01 — HMAC canonical form ambiguous on | in body

* **File:** ControlSecurity.java:78-80 (String canonical = timestamp+"|"+nonce+"|"+requestId+"|"+new String(body,UTF_8)), ControlServer.java:194-200.
* **Description:** body may contain | (JSON {"message":"a|b"}) making canonicalization non-injective. Two (nonce,body) pairs could collide in | count, though HMAC still over bytes, not forgery, but violates contract. Prefer length-prefix or hash body separately.
* **Fix:** hmac(secret, (ts+"|"+nonce+"|"+requestId+"|"+sha256(body)).getBytes(UTF_8))

#### L-02 — Math.abs(now - timestamp) treats far-future and far-past identically

* **File:** ControlSecurity.java:71-74.
* **Description:** if (Math.abs(now - ts) > window) rejects both drifts >300s. Attacker who can skew clock can replay future timestamps; abs masks direction.
* **Fix:** Reject ts - now > window (future) and now - ts > window (past) with distinct messages.

#### L-03 — branchMatches(null) returns true

* **File:** ControlServer.java:180-192.
* **Description:** if (requested == null) return true; plus branch = configured fallback means omitted branch always passes. Intended but early true defies allowlist semantics.
* **Fix:** Normalize if (requested == null) requested = cfg.git().branch(); then strictly equals or contains.

#### L-04 — SSE ProgressStream holds HttpExchange indefinitely

* **File:** ProgressStream.java:18-44,47-56, ControlServer.java:238-254 (daemon waiter Thread.sleep(1000) until isClosed).
* **Description:** Each handleStream holds exchange (blocking thread + socket) until stream.close(). If deploy contains restart (drops SSE), waiter keeps socket open until restart. No idle timeout; unbounded exchanges list per requestId.
* **Fix:** Set X-Accel-Buffering: no, close idle >60s, cap exchanges.size() <=4.

#### L-05 — PendingStore swallows JSON parse errors silently

* **File:** PendingStore.java:58-61,74 (catch Exception ignored).
* **Description:** Corrupted pending/*.json files are ignored, hiding tampering or disk errors. Attacker who truncates pending file forces silent resume skip.
* **Fix:** Log warning with filename + exception message.

#### L-06 — GitService URIish may allow file:// or ext:: transports

* **File:** GitService.java:355 (new URIish(url) where url = config.get().git().repo()).
* **Description:** URIish supports ssh://, scp-like git@host:path, file:///tmp/evil. File URL would point fetch at local bundle if attacker can write to FS. Not remotely exploitable because repo not client-supplied, but operator copy-pasting malicious URL could trigger.
* **Fix:** Validate git.repo at load: forbid file:// and ext::; allow only https://, ssh://, git@.

#### L-07 — actions/upload-artifact@v4 uploads thin jar + sources

* **File:** .github/workflows/build.yml:40-42.
* **Description:** path: build/libs/*.jar uploads -thin.jar, -sources.jar, shadow jar. Thin jar lacks bundled JGit/json — not runnable — artifact listing may confuse operators. No retention restriction.
* **Fix:** Narrow to build/libs/MineCICD-*.jar with retention-days: 14.

#### L-08 — .gitignore gaps at rewrite repo root

* **File:** .gitignore:1-33, src/main/java/resources/.gitignore:12-15 (/secrets.ym**).
* **Description:** Repo-root .gitignore ignores .gradle/, build/, IDE files but not secrets.yml / secrets.filter / plugins/MineCICD/config.yml. Runtime template correctly ignores /plugins/MineCICD/** but developers could accidentally git add secrets.yml.
* **Fix:** Add to repo-root .gitignore: /secrets.yml, /secrets.filter, /plugins/MineCICD/.

### Info / Defense-in-Depth Notes (positive)

* HMAC bound to headers+body, constant-time, replay guard — ControlSecurity.java:57-92 uses MessageDigest.isEqual and HmacSHA256 correctly.
* No client-supplied repo — CicdService.acceptRequest only receives requestId, actions, branch; server pins config.get().git().repo() (GitService.java:349). Closes historic RCE.
* Body-size cap — ControlServer.readBody (:289-307) enforces maxBodyBytes (65536) before JSONObject parsing — mitigates JSON bomb.
* Per-action enable flags + exact-name allowlists, fail-closed — ControlSecurity.validateActions (:131-154) and isAllowedScriptName (:161-175) strict.
* TLS path — ControlTls.build loads JKS/PKCS12 and refuses missing keystore; MineCICD.startControlServer (MineCICD.java:102-133) refuses empty secret and lints <32 bytes.
* Single worker queue — Threads.singleDaemonWorker (:20-21) serializes git/disk work; main-thread marshaling via getScheduler avoids async Bukkit access.
* JGit filter — SecretManager + ReplaceFilter (ReplaceFilter.java:37-83, SecretManager.java:96-166) avoids bundled .exe and handles special chars via base64.
* Messages migration — Messages.migrateLegacy (:87-138) and escape correctly neutralize MiniMessage injection for most placeholders.
* Gradle wrapper verified — gradle-wrapper.properties pins gradle-9.7.0-bin.zip with validateDistributionUrl=true; local JAR SHA256 7a9ce74c... matches official (checked 2026-08-30).
* .gitignore server template — src/main/java/resources/.gitignore correctly ignores ALL files (* + !*/) and managed section between BEGIN/END MARKER.

## Detailed Review Notes

### Source inventory (every file read)

build.gradle.kts:1-78, settings.gradle.kts:1, gradle.properties:1-3, gradle/libs.versions.toml:1-15, gradle/wrapper/gradle-wrapper.properties:1-9, .gitignore:1-33, .github/workflows/build.yml:1-75, .vscode/settings.json:1-3, src/main/java/resources/config.yml:1-80, secrets.yml:1-14, plugin.yml:1-33, messages.yml:1-144, example_script.sh:1-7, .gitignore:1-71, MineCICD.java:1-180, MineCICDConfig.java:1-169, CicdService.java:1-509, http/ControlServer.java:1-354, ControlSecurity.java:1-193, ControlTls.java:1-39, ControlRequest.java:1-70, ProgressStream.java:1-61, ControlStatus.java:1-41, git/GitService.java:1-704, CommitActions.java:1-134, GitIgnoreEditor.java:1-110, GitException.java:1-18, Results.java:1-32, scripts/ScriptRunner.java:1-169, secrets/SecretManager.java:1-209, secrets/ReplaceFilter.java:1-117, command/MineCICDCommand.java:1-237, pending/PendingStore.java:1-83, pending/PendingRequest.java:1-132, messaging/Messages.java:1-228, messaging/Msg.java:1-63, bossbar/BossBars.java:1-79, util/Ids.java:1-20, util/Threads.java:1-23, plus tests ControlSecurityTest.java:1-122, ReplaceFilterTest.java:1-77.

### Dependency + wrapper check

./gradlew dependencies --configuration runtimeClasspath resolves:

```
org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r
  com.googlecode.javaewah:JavaEWAH:1.2.3
  org.slf4j:slf4j-api:1.7.36
  commons-codec:commons-codec:1.17.0
org.json:json:20240303
```

Recommend bumping as in M-08. Wrapper distributionUrl=https://services.gradle.org/distributions/gradle-9.7.0-bin.zip over HTTPS, validateDistributionUrl=true — good. No maven http repos.

### Git history

git log --oneline -20 shows c75cd80 remove old source & rewrite incoming etc. No secrets in patches grepped (secret/password/token only dummy database_password: password in templates). Origin https://github.com/Bruderjulian/MineCICD.git, upstream git@github.com:finder1793/MineCICD.git. No exposed PATs.

### CI

actions/checkout@v4, actions/setup-java@v4, gradle/actions/setup-gradle@v4 are current majors (good). Need SHA pinning and least-privilege as in H-01.

## Input Validation Checklist

| Area | Input | Validation | Verdict |
|------|-------|------------|---------|
| Control API JSON | requestId, branch, actions | Ids.isValidRequestId [A-Za-z0-9_.-]{1,64}, branchMatches, parseControlItem throws ParseException, maxBodyBytes 65k | Good, but C-02 bypass for commit path |
| HMAC headers | X-MineCICD-Timestamp/Nonce/Signature | timestamp parse long, window 300s, nonce non-empty, hexDecode, MessageDigest.isEqual, seenNonces | Good, but M-01 unbounded |
| Script name | command:<cmd> / script:<name> | isAllowedScriptName rejects /, \, .., leading ., >64, charset [A-Za-z0-9._-] + allowlist | Good for control path, missing canonical check M-03, bypassed for commit path C-02 |
| Git path add/remove | add <path> | normalizeTrackingEntry rejects empty, strips ./, leading /, checks segment .., . | Good, but TOCTOU M-05 |
| Commit hash | reset/revert/log | resolveRev extracts [0-9a-f]{40} from URL, repo.resolve(trim) | Good |
| Rollback date | dd.MM.yyyy HH:mm:ss | LocalDateTime.parse with DateTimeFormatter, future check | Good |
| Config load | git.repo/user/pass/branch, control.* | patchConfig backfills defaults, control.secret empty refuses start, <32 bytes warns | Good |

## Recommendations Priority Matrix

| Priority | ID | Effort | Action |
|----------|----|--------|--------|
| P0 (block release) | C-01, C-02 | S (2 files, ~20 LOC) | Fix inFlight lifecycle + gate commit actions through ActionPolicy |
| P1 (before public tag) | H-01, H-02, H-03, H-04 | M | Least-privilege CI, chmod 600 on secrets/config, PendingRequest overwrite fix, TLS protocol pin |
| P2 (next minor) | M-01..M-08 | M | Bounded nonce cache, remove CORS *, canonical-path check, symlink guard, isDirectory TOCTOU, dep bumps, PendingStore logging, header caps |
| P3 (hardening) | L-01..L-08 | S | Canonical HMAC, branch null fix, SSE timeout, git.repo URL allowlist, artifact path narrowing, repo .gitignore addition |

Suggested 3.0.0 release gate: fix P0 + P1, add CI job dependencyCheck/gradle-audit, and sign tags (git tag -s).

## Appendix — Verified Good Practices to Keep

* plugin.yml:13-33 permission minecicd.* default: op with explicit children — prevents default-player escalation.
* ControlRequest.parse throws ParseException on non-string actions and empty list — rejects malformed bodies 400.
* ControlServer.respond always sets Cache-Control: no-store and Content-Type: application/json; charset=utf-8 — prevents caching of HMAC responses.
* ScriptRunner.ScriptException carries line number — aids forensics.
* Ids.REQUEST_ID = [A-Za-z0-9_.-]{1,64} — strict, prevents header/path injection.

## References (file:line)

* HMAC/CORS/inFlight: ControlSecurity.java:25,57-92,78-91 / ControlServer.java:44,164-169,226,289-307
* Action policy: ControlSecurity.java:131-154,161-175 / CicdService.java:103-115,360-407 / CommitActions.java:12,75-133
* GitOps: GitService.java:91,149-170,348-362,676-694 / GitIgnoreEditor.java:38-89 / SecretManager.java:64-66,96-166
* Scripts: ScriptRunner.java:101-150 / PendingStore.java:58-61,74 / PendingRequest.java:97-131
* CI: .github/workflows/build.yml:6,10-11,66-74 / gradle/libs.versions.toml:2-5 / gradle-wrapper.properties:3
* Config secrets: MineCICDConfig.java:119-141 / MineCICD.java:108-116,122 / src/main/java/resources/config.yml:39-46

*Audit performed 2026-08-30, auditor model muse-spark-1.2-contributor-free, reviewer role security-expert; findings ordered by severity, sources cited to line. Next audit due after P0/P1 fixes or before any file:// or scripts.enabled: true deployment.*

