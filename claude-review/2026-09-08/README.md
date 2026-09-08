# Lumi source for Claude review

This repository is public so external reviewers such as Claude can inspect Lumi without access to the private development repository.

## Best public source available

The repository's existing `app/` tree is the full public Lumi Code388 baseline (`versionCode 388`, `4.4.8-native-self-update-r105`). Start there for architecture and implementation review.

Code388 explicitly removed Guardian from the active build, so there is no current `guardian/` project in this public baseline.

## Current release warning

The phone is currently on Code614 (`4.50.14-remote-conversation-probe-r332`). The exact Code614 canonical source is carried inside the private release package's nested `payload/canonical-source.zip`. That binary package was deliberately not retrieved or republished because the authenticated connector in the export session could not return binary content.

This folder therefore adds the current plaintext Code614 patch and runtime/release evidence that could be recovered safely. Do not represent the Code388 `app/` tree as byte-for-byte Code614 source.

## Review target

The fixed target is Lumi Release 1.0. Do not weaken or redefine acceptance gates to manufacture progress. Trace each major capability end to end from trigger through downstream processes to a user-visible result, looking specifically for disconnected or dead-end plumbing.

Suggested classifications: `PROVEN`, `CONNECTED-UNPROVEN`, `PARTIAL`, `DEAD-END`, `STUB/PLACEHOLDER`, `FAILED`, `STALE-EVIDENCE`.

## Security

The plaintext export was scanned for common API-key, token, password, private-key, email, and phone-number patterns before publication. No actual credentials or private keys were found. No keystores, APKs, update ZIPs, signing material, images, models, audio files, or other binaries were published by this handoff.
