# Specifications

Implement fixes for all concrete findings from the security audit of the MineCICD working tree.

## Functional Requirements

- Reject git:// transport and allow only https://, ssh://, and scp-style SSH (git@) remote URLs in GitService.ensureRemote()
- Move the server-root .gitignore template out of the Java resource directory so required resources (config.yml, plugin.yml, messages.yml, example_script.sh, secrets.yml) are tracked and packaged in the JAR
- Scope the secret clean/smudge filter to the file being filtered so a secret from one file is never written into another file
- Bound and periodically expire the ControlServer failureCounts cache to prevent unbounded heap growth
- Pin every GitHub Action in build.yml to an immutable commit SHA with version comment
- Replace the legacy insecure webhook and full-repo-token documentation in Readme.md with the HMAC action flow, loopback + TLS reverse proxy, and minimal repo permissions

## Non-Functional Requirements

- All changes must compile with Java 21
- Existing tests must continue to pass
- New/updated unit tests must cover each security fix

## Acceptance Criteria

- GitService refuses git:// URLs during remote setup; unit test added and passing
- A clean checkout CI build packages config.yml, plugin.yml, messages.yml, example_script.sh, secrets.yml into the JAR; CI assertion added
- Secret filter applies only the mapping for the target file; tests for duplicate values across two files added and passing
- failureCounts cache is bounded and expired periodically; covered by test
- build.yml uses SHA-pinned actions only; no mutable tags remain
- Readme no longer recommends unauthenticated webhook or full-repo token

## Out of Scope

- Adding reverse-proxy timeout configuration (documented as ops responsibility)
- Adding Dependabot config (documented as recommended follow-up)