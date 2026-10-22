# Security policy

The assistant can read the owner's messages, email, calendar and contacts and act on the phone, so security reports
are treated as a priority.

## Reporting a vulnerability

Report privately through GitHub's private vulnerability reporting ("Report a vulnerability" in the repository's
Security tab). Do not open a public issue.

Include the affected version or commit, the steps to reproduce, and the impact you expect. You should receive an
acknowledgement within 7 days. Fixes are released before details are published; reporters are credited unless they
ask otherwise.

## Scope

In scope: the Android app in this repository, its policy engine and tool server, the configuration it writes for the
ZeroClaw daemon, and the build scripts.

Out of scope: vulnerabilities in ZeroClaw itself (report them upstream to the ZeroClaw project), in model or speech
providers, and issues that require an already compromised (rooted) device.

## Security model

The design is described in [docs/architecture/security.md](docs/architecture/security.md) and
[docs/specs/access-model.md](docs/specs/access-model.md): permissions enforced in code, separate tool endpoints per
principal on loopback, the owner's device credential for strong actions, an undo window and a kill switch, and app
files sealed with a key held in the Android Keystore.

Known limitation: model and service keys are compiled into the APK from `local.properties`. Do not distribute builds
that contain your keys.
