# Implementation Plan

Conduct a deterministic, evidence-driven static audit of the current working tree. Use an isolated fixture or test server only for safe validation; do not change the audited application as part of this plan.

## 1. Baseline and threat model

- [~] Capture the current working-tree baseline, source/dependency/build inventory, and security-relevant uncommitted diff.
- [ ] Reconcile docs/security-audit-2026-08-30.md against current behavior; classify each prior finding as fixed, partial, regressed, or untested.
- [ ] Document trust boundaries and an actor-to-privileged-action authorization matrix for HTTP clients, Git writers, Minecraft users, local filesystem writers, and CI/CD.

## 2. Control plane and lifecycle review

- [ ] Trace every HTTP control, status, and SSE endpoint through request parsing, HMAC verification, authorization, and response handling; validate fail-closed handling of malformed and oversized input.
- [ ] Review nonce replay prevention, timestamp validation, request-ID binding, rate limiting, host/path/config validation, and concurrency behavior for bypasses or denial of service.
- [ ] Model request persistence, in-flight locking, restart/resume, retries, terminal status retention, and corrupted/tampered pending records; validate with isolated lifecycle tests.
- [ ] Review TLS configuration, binding defaults, reverse-proxy assumptions, SSE resource lifecycle, slow-client behavior, and transport exposure.

## 3. Privileged execution and repository review

- [ ] Audit policy parity and configuration gates for HTTP actions, commit-message directives, player/console commands, plugin reloads, and restarts.
- [ ] Audit script execution for filename/path/symlink confinement, shell/process safety, timeout/child cleanup, environment exposure, and output redaction.
- [ ] Audit Git remote validation, credential handling, checkout/reset/staging operations, repository-controlled files/attributes, path operations, and secret-commit prevention.

## 4. Secrets, disclosure, and supply chain

- [ ] Map secrets from input through storage, filtering, process arguments, logs, HTTP/SSE/status output, and Git metadata; review permissions and failure modes across supported platforms.
- [ ] Audit secret clean/smudge filter construction and replacement semantics for injection, data loss, resource exhaustion, and raw-secret leakage.
- [ ] Taint-trace untrusted input to JSON, SSE, MiniMessage, logs, shell, and filesystem sinks; validate escaping and redaction with sentinel values and adversarial payloads.
- [ ] Review Gradle wrapper, catalog, repositories, direct/transitive dependencies, shadow artifact contents, GitHub Actions permissions/events/action pinning, and release artifact selection.

## 5. Validation and reporting

- [ ] Run safe static checks and isolated unit/integration validation for confirmed hypotheses, including malformed request, lifecycle, symlink, URL, TLS, and permission scenarios.
- [ ] Produce the security audit report with scope, methodology, threat model, severity-ranked findings, evidence, remediation, regression tests, and configuration-dependent risks.
- [ ] Produce an executive summary and prioritized remediation/retest roadmap, including deployment hardening prerequisites and residual risks.