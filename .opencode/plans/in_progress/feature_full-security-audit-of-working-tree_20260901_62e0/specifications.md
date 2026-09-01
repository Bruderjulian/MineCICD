# Specifications

Audit the current working tree, including uncommitted changes and the prior untracked audit, as the authoritative baseline. Review source code, configuration, secrets handling, embedded control API, Git and script execution, persistence, dependencies, build/release pipeline, and documentation. Treat the prior audit as retest hypotheses only. This plan produces an audit report; it does not remediate findings.

## Functional Requirements

- Establish and record the working-tree baseline and reconcile prior audit findings as fixed, partial, regressed, or untested.
- Create a threat model and authorization matrix across HTTP clients, Git remote writers, Minecraft users, local files, and GitHub Actions.
- Review all control API endpoints for authentication, authorization, parsing, replay prevention, lifecycle correctness, TLS/SSE exposure, and denial-of-service resistance.
- Review privileged actions, command and script execution, Git operations, repository-controlled content, filesystem boundaries, and persistent request state.
- Review secret storage/replacement, configuration validation, logging and output sinks for disclosure or injection risks.
- Review direct/transitive dependencies, Gradle wrapper/build configuration, shaded artifacts, and GitHub Actions supply-chain controls.
- Deliver a severity-ranked report with affected paths, evidence, reproduction/validation steps, exploit preconditions, remediation guidance, and residual risk.
- Provide a test and deployment-hardening checklist for validating remediations.

## Non-Functional Requirements

- Use read-only, isolated, non-production audit activities; never exercise privileged API actions against a live server.
- Keep evidence reproducible and distinguish verified findings from hypotheses and configuration-dependent risks.
- Do not expose live credentials, secret values, tokens, or private deployment data in audit artifacts.
- Use severity scoring and clearly state assumptions, trust boundaries, and scope exclusions.

## Acceptance Criteria

- The audit baseline includes the current commit, working-tree diff, dependency/build inventory, and disposition of every prior-audit finding.
- Every externally reachable or privilege-bearing path has a documented data/control-flow review and authentication/authorization result.
- Potential findings are validated with safe unit, static, or isolated integration evidence before being reported as confirmed.
- The final report is actionable: each confirmed finding includes severity, precise location, impact, preconditions, remediation, and regression test recommendation.
- The report covers dependencies, CI/CD, configuration, code, and deployment posture, with a final executive summary and prioritized remediation roadmap.

## Out of Scope

- Changing application code, runtime configuration, repository secrets, or CI/CD settings.
- Penetration testing a live/public deployment, production Minecraft server, or third-party Git host.
- Operating or retaining user-provided credentials.
- Formal certification against a compliance framework unless separately requested.