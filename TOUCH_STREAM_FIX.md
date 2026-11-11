# Dual-channel touch stream fix

Base: upstream `2ec5ba3efa42d0b365031ebea44ff8b92dd2538f`.

## Cause and fix

IMS filterInputEvent and the WMS pointer listener both forward events to the same
three gesture detectors. The old entry point only dropped adjacent MOVE events
with equal millisecond eventTime. Duplicate UP resets the triple-tap detector
from WAITING_TAP_2 to IDLE; duplicate DOWN can turn TAP_2_DOWN into TAP_1_DOWN.

The first channel to deliver ACTION_DOWN now owns that downTime/device stream
through UP or CANCEL. The finished downTime remains as a watermark, rejecting
late replay of an entire earlier tap. New streams may choose either channel,
including IMS if WMS registered successfully but does not actually deliver input.
No handover occurs inside a stream. A new DOWN after a lost UP/CANCEL cancels
all detectors before starting the new stream. Selection and dispatch share one
lock, so IMS/WMS threads cannot interleave the three detector updates.

This does not fingerprint coordinates (the channels may use different spaces)
or collapse MOVE samples by millisecond eventTime. Existing gesture thresholds,
rotation calculations, settings and system hook signatures are unchanged.
Only touchscreen sources are routed to the detectors.

## Validation boundary

`testDebugUnitTest` covers duplicate three-finger triple-tap event sequences in
both channel orders, delayed whole-stream replay, stale events from a previous
tap, two-finger double-tap pointer transitions, same-time MOVE samples, CANCEL,
missing UP, fallback on the next stream, and foreign/orphan events. These are JVM
tests of input routing, not physical gesture or Hook compatibility tests.

The code retains the upstream single-touchscreen/default-display design. It
does not add concurrent multi-display or multi-device gesture support. If an
owner stops delivering events mid-stream, that stream is abandoned on the next
DOWN rather than splicing two coordinate spaces together. Vendor modifications
to event timing/streams still require physical-device logs.

## Build

JDK 17, Android platform 35, build tools 34.0.0:

```sh
bash ./gradlew --no-daemon testDebugUnitTest assembleDebug
```

`.github/workflows/build-fixed-apk.yml` runs on pushes to `fix/**` branches or
manual dispatch. The APK artifact includes a SHA-256 checksum, signature report
and source commit. It is a debug-signed installable APK; the runner-generated
debug signing key is not stable across runners. It may require uninstalling an
existing differently signed APK (which removes app settings). Never uninstall
automatically. Preserve a copy of a working APK before testing.

## Physical-device acceptance checklist (not yet executed)

1. Enable the module for Android/system framework in LSPosed and reboot the
   phone. Confirm the displayed version ends in `-touch-stream-fix`.
2. Triple-tap with three fingers: logs should show `Tap 1 valid`, `Tap 2 valid`,
   then `Three-finger triple tap SUCCESS`, and the system rotation lock should
   change exactly once. Repeat to restore its previous state.
3. Check two-finger double-tap in all four orientations, and left/right
   three-finger horizontal swipes starting in the bottom quarter of the display.
4. Check ordinary single/two-finger interaction, slow taps, long presses,
   three-finger vertical screenshot gestures, cancelled and interrupted touches.
   None should toggle the rotation lock accidentally.
5. Repeat with each gesture switch disabled and with the phone folded/unfolded.
   Record the phone/OS/LSPosed versions and collect LSPosed/logcat output on failure.

Compilation, JVM test success and signature verification do not establish that
the module works on a physical phone. No physical-device validation is claimed.
