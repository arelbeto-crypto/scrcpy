# V8.9 open masks

Beto approved the free, open-source route: MediaPipe facial tracking, freely
licensed masks, existing native POCO render path, and no paid AR service.

The Redmi remains the analysis coprocessor. Replace both proprietary ML Kit
detectors with bundled MediaPipe Tasks models. Keep the PVR1 JPEG/result protocol
unchanged, including source timestamps and canonical vertex indices. Preserve all
camera shortcuts, the four Windows startup modes, software decoding and V8.8's
subtle backgrounds, bokeh and bounded zoom. Face tracking uses a single tracked
face with MediaPipe smoothing and bounded work queues. Stale sessions must never
publish results, stopped services must release models, and model failures must
be reported without crashing the app.

Replace the old bounding-box ornaments with masks textured onto the measured
canonical face mesh. Bundle editable, freely licensed assets and their notices.
Eight mask slots and existing keyboard selection remain. Eyes and mouth must
remain open where appropriate; absent mesh means no floating fallback mask.

Completion requires JVM/protocol tests, actual GPU pixel checks, a compiled APK
with bundled models, native Android model inference/restart checks, and the matching
Windows package. Hardware latency and Android 17 compatibility are reported only
to the extent actually tested; Android 16 emulator success is not Android 17 proof.
