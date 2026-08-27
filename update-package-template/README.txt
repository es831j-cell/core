LUMI LOCAL ZIP UPDATE FORMAT — CODE388+

1. Keep lumi-update.json at the root of the ZIP.
2. Put optional content files under payload/ and list each file in the manifest with its SHA-256 hash and approved target.
3. A manifest signature (lumi-update.sig) is optional for a user-selected local ZIP.
4. Content ZIPs may only update whitelisted preferences and Lumi-private avatar/asset/config targets.
5. A core ZIP must contain a newer compiled Lumi APK under payload/, declare it in files[], and identify it in an "apk" object. The APK must have package com.distressedelk.lumi and the same Lumi signing certificate.
6. Android still asks the user to approve installation of a core APK update. Lumi does not bypass that security boundary.

IMPORTANT: Lumi cannot compile Android source code on the phone. A source-project ZIP is not a core update package. Core update ZIPs must contain a compiled APK.

CODE388 TRUSTED SOURCE UPDATE
7. Source-driven core updates use type "bridge-core" and carry the complete next canonical-source.zip, source-change-record.json, and source.patch with SHA-256 declarations.
8. Lumi verifies the package against her current canonical source and requires fresh administrator authorization.
9. Lumi load-tests the private GitHub build/sign relay, creates a protected local recovery checkpoint, builds/signs the exact source in the trusted relay, verifies the returned APK package/version/signing identity, and opens Android's installer directly.
10. After package replacement, Lumi runs native post-install validation and records PASS/FAIL in Black Box. Guardian is not part of this path.
11. The GitHub token stays encrypted on the device. APK Factory is bootstrap/catastrophic recovery only after Code388 is installed.
