# Lumi Canonical Source

This repository is the permanent source-of-truth baseline for Lumi beginning with Code388 / R105.

## Baseline
- Android package: `com.distressedelk.lumi`
- Version code: `388`
- Version name: `4.4.8-native-self-update-r105`
- Baseline label: `lumi-code388-r105`

## Source-of-truth rule
Every future Lumi build must start from the latest promoted canonical source revision. A build is not considered canonical merely because an APK or Factory ZIP exists. After a successor is validated, its complete source tree, change record, source hash, and version metadata must be promoted as the next canonical revision.

## Signing separation
Private signing files are intentionally excluded from canonical source. `app/keystore.properties.example` documents the required shape only. The real JKS, passwords, tokens, and signing configuration remain private and are injected only by the trusted private build path.

## Guardian status
Code388 removes Guardian from Lumi's operational update architecture. Historical references may remain in migration logic, diagnostics compatibility, or old change records, but the project contains no Guardian module and Lumi owns normal update verification, checkpointing, Android installer handoff, and post-install validation.

## Recovery
Keep the most recent known-good APK/Factory package separately from this repository. Canonical source is the development truth; the signed APK is the recovery/install artifact.
