# Canonical Promotion Rules

A Lumi revision may be promoted only when all of the following are true:

1. The complete source tree is present.
2. `app/build.gradle` carries the intended `versionCode` and `versionName`.
3. Private signing keys, passwords, API tokens, and credentials are absent.
4. The source tree passes static validation available in the build environment.
5. A deterministic source hash manifest is generated.
6. The revision is committed and tagged in canonical history.
7. The corresponding Factory/update artifact records the canonical revision it was produced from.
8. Device-only acceptance evidence is never fabricated; device tests remain pending until actually run.

Future work starts from the newest promoted tag, never from reconstructed memory or an older ZIP unless explicitly performing recovery.
