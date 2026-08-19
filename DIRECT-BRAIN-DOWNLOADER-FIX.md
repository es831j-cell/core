# Lumi v2.0 Direct Brain Downloader Fix

This build replaces Android DownloadManager for the required 0.6B Fast Brain with an in-app HTTPS downloader.

- follows HTTP redirects explicitly
- uses app-private external model storage
- resumes `.part` files with HTTP Range when supported
- reports real progress and HTTP/network errors
- checksum-verifies before activation
- leaves Administrator Enrollment deferred while latency is being tuned
- keeps the optional 4B deep-brain asset path unchanged for now
