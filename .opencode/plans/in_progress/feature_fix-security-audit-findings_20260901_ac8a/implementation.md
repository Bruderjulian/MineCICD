# Implementation Plan

Fix each of the six audit findings across GitService, resource packaging, secret filter scoping, ControlServer rate-limit cache, GitHub workflow pinning, and documentation. Add regression tests for each code fix.

## Git transport hardening

- [x] Reject git:// and allow only https://, ssh://, and scp-style SSH in GitService.ensureRemote()
- [x] Add a unit test that git:// and other disallowed transports are refused

## Resource packaging fix

- [x] Move the server-root .gitignore template out of the Java resource directory and track required defaults
- [x] Add a clean-checkout CI assertion that the JAR contains required resources

## Secret filter scoping

- [x] Scope the clean/smudge filter to the file being filtered with per-file mapping and path enforcement
- [x] Add tests for duplicate values and overlapping secret values across two files

## ControlServer resource exhaustion

- [x] Bound and periodically expire the failureCounts cache in ControlServer

## GitHub Actions pinning

- [x] Pin every GitHub Action in build.yml to an immutable commit SHA with a version comment

## Documentation remediation

- [x] Replace the legacy webhook and full-repo-token documentation with the HMAC action flow and loopback/TLS reverse proxy guidance

## Final verification

- [x] Run the full test suite and build to verify all changes compile and tests pass