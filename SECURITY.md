# Security Policy

## Supported versions

Security fixes are applied on the latest `main` and on the most recent
`v*` release when practical. Older tags are not patched.

## Reporting a vulnerability

Please **do not** open a public issue for security-sensitive reports.

Prefer one of:

1. [GitHub private vulnerability reporting](https://github.com/Trugath/k8086/security/advisories/new)
   for this repository (if enabled), or
2. Email the maintainer via the address on their
   [GitHub profile](https://github.com/Trugath).

Include enough detail to reproduce (host OS, JDK version, release zip or commit,
guest image/ROMs, and steps). You should hear back within a reasonable time;
there is no bug bounty.

## Scope notes

k8086 is a hobby XT-class emulator. Reports about intentional period limitations
or guest software bugs (including bundled rmDOS assets) are generally not
emulator vulnerabilities unless the host process is compromised.
