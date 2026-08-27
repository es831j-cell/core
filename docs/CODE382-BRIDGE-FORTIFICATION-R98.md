# Lumi Code382 Bridge Fortification R98

Code382 preserves the full Code381 Black Box remediation and changes the normal executable-core update path from APK-first to source-ZIP-first.

## Normal update road

1. Owner selects a `bridge-core` Lumi update ZIP.
2. Lumi validates package structure, every payload SHA-256, installed/base version, current canonical-source hash, target canonical-source hash, source change record, target version/name, source archive safety, and required bridge workflow.
3. Lumi stores the verified package without executing it.
4. Fresh device-backed administrator authentication is required.
5. Trusted Build Relay preflight proves: private repository, source push permission, build workflow readable/active, preflight workflow readable/active, harmless GitHub Actions dispatch succeeds, Guardian same-phone round trip succeeds, and local staging space is sufficient.
6. Guardian accepts one exact core-update transaction and creates a recovery checkpoint before the build begins.
7. Lumi expands the exact verified canonical source into a transaction-scoped staging tree and records durable stage/hash metadata.
8. Lumi uploads source through the private Git data API. Binary blobs/tree/commit creation receive bounded retry; workflow dispatch is reconciliation-safe so a lost HTTP response does not blindly create duplicate builds.
9. GitHub Actions builds/signs with private repository secrets. Signing material never enters Lumi, diagnostics, or the update ZIP.
10. Lumi finds the workflow run by immutable commit SHA, downloads exactly one APK, checks package/signing/version/canonical-source identity locally, then hands it to Guardian.
11. Guardian independently verifies again, checkpoints, invokes Android PackageInstaller, retains any required user-approval intent, and runs post-install certification.
12. The transaction is not certified until Guardian reports the installed target and certification PASS. Failures remain explicit and recoverable.

## Failure containment

- 401/403 authentication failures fail closed and are not treated as transient transport errors.
- Socket/connect/timeout and selected 408/409/425/429/5xx failures receive bounded exponential retry.
- A workflow-dispatch response loss enters `RELAY_DISPATCH_PENDING`; Lumi queries existing runs before redispatching.
- Update transactions persist across process restarts using request ID, stage, target, commit SHA, run ID, artifact hash and Guardian state.
- Source ZIPs cannot contain signing keys, keystores, build outputs, `.git`, `.gradle`, or unsafe paths.
- Fresh administrator authentication is required to put a verified source ZIP onto the bridge.

APK Factory remains only the bootstrap/recovery mechanism for installing Code382 itself.

### R98.1 factory compile repair
GitHub Actions exposed one Java scope defect in `beginSelfOptimizationAnalysis()`: `bridgeStaged` was referenced without a local declaration. R98.1 declares it from `BridgeUpdatePackage.hasPending(this,prefs)` before use. This is a compile-only repair with no intended behavior change.
