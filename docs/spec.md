# OpenLink: the control channel between OpenVitals and OpenWatch

Version 1, draft. Health data travels through Health Connect. OpenLink carries what Health
Connect cannot: a body profile, the state of paired devices, a request to start or stop a
recording on a wearable, and the live samples a recording needs while it runs. It is an
Android IPC contract: a bound service with an AIDL interface, a trust store on the host, and
a consent screen. No network, no account, no server.

## Roles

- **Host**: the app that owns a capability and exports the service. In version 1 OpenWatch is
  the only host.
- **Client**: the app that binds and calls. In version 1 OpenVitals is the only client.

A host may also be a client of another host; the roles are per capability, not per app.

## Transport

The host exports one `Service` whose binder implements `IOpenLinkHost`. A client finds it by
the action `tech.mmarca.openlink.action.HOST` and the host's package name, and binds with
`Context.bindService`. Every call carries the caller's identity in the binder: the host reads
`Binder.getCallingUid()`, resolves the package and its signing certificate, and decides from
its trust store. Nothing in a request is trusted as identity.

Events flow back through `IOpenLinkListener`, registered over the same binding. The host
calls back only a client that is currently bound and trusted for the capability the event
belongs to. A client that unbinds, crashes or is revoked stops receiving at once.

Requests are oneway where they are notifications (`setBodyProfile`, `requestSync`,
`startRecording`, `stopRecording`), and synchronous where they return state (`hello`,
`getDevices`, `getBodyProfile`). Synchronous calls return within a few milliseconds from the
host's in-memory state; the host never blocks a call on Bluetooth or Health Connect.

### Why a bound service

A broadcast cannot answer and cannot prove who sent it. A `ContentProvider` can prove the
caller but has no event path. A bound service gives both, plus a death notification when
either side goes away.

## Identity and permission

The host exports the service with no `android:permission`. A client lists the host in
`<queries>` (`<package android:name="tech.mmarca.openwatch" />`), or the service is invisible
to `bindService` on Android 11 and newer. The host needs no `<queries>` entry for its clients:
an app that binds to a service is visible to it.

An earlier draft guarded the service with a `normal` permission the host defined. It protected
nothing (any app may ask for a normal permission and gets it at install), and it broke the
link in the common case: Android grants a custom permission only if its definer is installed
first, so a client installed or updated before the host was refused the binding until it was
reinstalled. A client may keep the `<uses-permission>` for an older host; a host ignores it.

Trust is the host's decision, made per call from the caller's identity: the host resolves
`Binder.getCallingUid()` to its one package (a uid shared by several packages is refused) and
the SHA-256 of its current signing certificate, and looks that pair up in its trust store.
From API 28 the host also reads the certificates the key was rotated from
(`signingCertificateHistory`, APK signature scheme v3). The platform proves that lineage, so
trust given to a past certificate carries over to the current one, and the record moves to
the current one. An app signed by several certificates has no rotation; its identity is all
of them, as their sorted digests joined by `+`. A
`signature|knownSigner` permission would do part of this in the platform, but only from
Android 12, and the apps run from Android 8; the trust store works the same on every version.

The host carries a list of its first-party clients: each package name with the SHA-256 of
the certificates that package is signed with (for OpenWatch, `tech.mmarca.openvitals` with
OpenVitals' release upload certificate and its Play app-signing certificate). A caller with
one of these names under one of its own certificates is trusted without a prompt. The name
and the key count together: the same key under another package name asks like any other app.

Any app may be a client. One that is not on the list asks the user once, and is then trusted
under the key it asked with. Nothing in the protocol favours a first-party app beyond that
skipped prompt.

### The client checks its host too

A client sends its host the body profile and takes readings from it, so it must know who the
host is; a package name alone proves nothing, since any app may be installed under a free
name. Before it binds, and again when the binding arrives, `OpenLinkClient` reads the host
package's signing certificate. It binds only a host signed by one of the certificates the
client app ships as its first-party host's, or by the certificate the user approved. For any
other host it reports `HostUntrusted` with the certificate's digest, binds nothing and sends
nothing (a host whose certificate cannot be read is such a host, with an empty digest); the app asks the user and calls `trustHost(digest)`. The approval is for that
certificate, so the same package under another one asks again, and a key rotated from a known
or approved one carries its trust. A third-party host is therefore one question away, as a
third-party client is.

## Trust store

The host keeps, per client package, a record:

| Field | Meaning |
| --- | --- |
| `packageName` | the client |
| `signerSha256` | the signing certificate the trust was given to |
| `state` | `PENDING`, `TRUSTED`, `BLOCKED` |
| `capabilities` | the granted subset of `profile`, `devices`, `recording`, `live` |
| `updatedAt` | when the trust last changed |
| `lastSeenAt` | when the client last said hello or went away |
| `profileReceivedAt` | when the host last took a body profile from it |
| `revoked` | the capabilities the user took back or did not grant |
| `knownSigner` | whether its last hello came as a first-party client |

Rules:

1. A hello from an unknown package creates a `PENDING` record and returns a state with trust
   `PENDING` and no capabilities. The host lists it on its Connected apps screen with its
   icon and the capabilities it asked for, with Allow and Block. It raises no card or
   notification on its own, because any app can say hello. The client launches the host's
   consent activity through the `PendingIntent` returned in `hello()`, from a tap.
2. A client signed by a known signer is recorded `TRUSTED` with every capability it asked for,
   on its first `hello()`, without a card. One still `PENDING` from before its signer was known
   (a host update added it) becomes `TRUSTED` on its next `hello()`; a `BLOCKED` one stays. It still appears under Settings, Connected apps, and can be revoked there. A later `hello()`
   gives it what a newer build newly asks for, never what the user took back.
3. A changed signing certificate on a known package drops the record to `PENDING` and clears
   the capabilities, as the Wear OS link does for a reinstalled phone. The old trust never
   carries over to a new signer, except to a key rotated from the trusted one (see Identity
   and permission).
4. `BLOCKED` is final until the user clears it in Settings, whatever signing certificate the
   package comes back under. A blocked client's calls return `OpenLinkState(BLOCKED)` and
   nothing else: it registers no listener, so it hears of an unblock at its next `hello()`.
5. Revoking a capability takes effect on the next call and closes any live subscription
   under it immediately. It stays revoked until the user grants it again, for a first-party
   client too.
6. A capability a client did not ask for in `hello()` is never granted to it.
7. The host keeps at most 10 `PENDING` records and drops the oldest first. A `PENDING` record
   not seen for 30 days is dropped. Both are checked on every `hello()`, whoever says it. Any
   app can say hello, so it must not fill the host's screens; its version label is cut to 64
   characters.
8. A client that is bound says no `hello()` until it rebinds, so its record is never dropped
   under it: it is not pruned, and forgetting it starts its record over as its first `hello()`
   left it (`PENDING`, or `TRUSTED` for a first-party client).
9. A first-party client that comes back under another of its listed certificates (the same
   app from another channel) is the same client: what the user revoked stays revoked.

The consent screen names each capability in plain words:

| Capability | Shown as |
| --- | --- |
| `profile` | "Send its body profile (birth year, sex, height, weight) to OpenWatch" |
| `devices` | "See your paired watches and scale, their battery and last sync, and start a sync" |
| `recording` | "Start and stop a recording on your watch when it records an activity" |
| `live` | "Receive heart rate, cadence and power from your watch while it records" |

### What the host shows

The host's Connected apps screen tells, per client, whether it is in use now (bound with a
listener) or when it was last connected, which sessions it holds ("Recording: streaming heart
rate from Venu 3") and when its profile last arrived. While a live subscription runs the host
shows a notification naming the client, so a watch's radio is never kept busy unseen. A
client that stops clears its listener before it unbinds; one that unbinds without doing so
leaves when the last binding to the service goes.

## Protocol

```
interface IOpenLinkHost {
    OpenLinkState hello(in OpenLinkHello hello);
    void setListener(IOpenLinkListener listener);                 // one per client app; null clears
    // profile
    oneway void setProfile(in OpenLinkProfile profile);
    OpenLinkProfile getProfile();
    // devices
    List<OpenLinkDevice> getDevices();
    oneway void requestSync(String deviceId);
    // recording
    oneway void startRecording(in OpenLinkRecordingRequest request);
    oneway void stopRecording(String sessionId);
    // live
    oneway void subscribeLive(in OpenLinkLiveSubscription subscription);
    oneway void unsubscribeLive(String sessionId);
    // recording, version 2 (appended last: the transaction codes of version 1 stay)
    oneway void pauseRecording(String sessionId, long atMillis);
    oneway void resumeRecording(String sessionId, long atMillis);
}

oneway interface IOpenLinkListener {
    void onStateChanged(in OpenLinkState state);                  // trust or capabilities changed
    void onProfileChanged(in OpenLinkProfile profile);
    void onDevicesChanged(in List<OpenLinkDevice> devices);
    void onRecordingStateChanged(in OpenLinkRecordingState state);
    void onLiveSamples(in OpenLinkLiveBatch batch);
    // version 2 (appended last), sent only to a client that negotiated version 2
    void onDataWritten(in OpenLinkDataWritten written);                // capability devices
}
```

`OpenLinkHello` carries `protocolVersion`, `appVersion`, and `requestedCapabilities`.
`OpenLinkState` carries `protocolVersion`, `trust` (`PENDING`, `TRUSTED`, `BLOCKED`),
`grantedCapabilities`, and `consentIntent` (a `PendingIntent` to the host's consent screen,
present while pending). A client sends it from a tap in its visible activity, with
`ActivityOptions` that lend its foreground to the launch (`OpenLinkClient.openConsent`): from
Android 14 the system blocks it otherwise, since the host that made it is in the background.

A recording session and a live subscription belong to the client that opened them: their
events go to it alone, another client cannot stop, pause or resume them, and they end when their
owner loses the capability, unbinds or dies. The one exception is a session in `RECORD` mode
(below): the wearable records it, so it goes on when its owner unbinds or dies, and ends when the
owner stops it, when the owner loses the capability, or when the host reports it `STOPPED` or
`FAILED`. The owner takes such a session back by sending the same request again; the client
library holds its requests in memory only, so an app that wants its session back after its own
process died keeps the request itself. A start for a session the host has already ended is not
a new workout: the host answers it with the session's final state (it remembers the last 16), and
the client library drops a `RECORD` session once it hears `STOPPED` or `FAILED`.

A session has no time limit and a start no rate limit: only a client the user trusts with
`recording` can open one, an activity may last a day, and a start that follows a stop at once
is an ordinary correction. What bounds a session is its owner's binding, and the notification
the host shows while one runs.

Every call under a capability the caller lacks is a no-op that logs a count; synchronous ones
return an empty result. The host never throws across the binder for a permission question,
so an old client never crashes against a stricter host.

### Versioning

`protocolVersion` is an integer, 2 in this version. Version 2 adds `RECORD` mode, the `PAUSED`
phase, `pauseRecording` and `resumeRecording`, the workout summary, `onDataWritten`, and the
device fields `recordingFeatures`, `exerciseTypes`, `measurements`, `lastSyncResult` and
`lastSyncAttemptAtMillis`. A client sends `RECORD` only when the host answered version 2 or higher (a host
on version 1 would ignore `mode` and stream instead); the client library fails such a request
itself with the reason `unsupported`. A host never reports `PAUSED` to a client that negotiated
version 1, which reads an unknown phase as `FAILED`: it reports `RECORDING` instead. Both sides send theirs in `hello()`; the
host answers with `min(client, host)`, and both speak that version. Every parcelable is written as one `Bundle`:
a reader ignores keys it does not know and defaults the ones it does not find, so either side
may add a field. A method is never removed; a retired one becomes a
no-op. A new capability is a new name in `requestedCapabilities`, ignored by an older host.

### Data types

- `OpenLinkProfile`: `birthYear`, `sex` (`FEMALE`, `MALE`, or null), `heightCm`, `weightKg`,
  `restingHeartRateBpm`, `maxHeartRateBpm`, all nullable, and `updatedAtMillis`. The last
  writer wins; each side keeps the time of its last change and ignores an older copy.
- `OpenLinkDevice`: `id`, `displayName`, `kind` (`WATCH`, `BIKE_COMPUTER`, `SCALE`),
  `integration` (`GARMIN`, `WEAROS`, `XIAOMI`), `connection` (`CONNECTED`, `DISCONNECTED`,
  `UNKNOWN`), `batteryPercent`, `batteryUpdatedAtMillis`, `lastSyncedAtMillis`, `syncing`,
  `supportsSync`, `supportsRecording`, `liveSampleTypes`, and from version 2 `recordingFeatures`
  (subset of `GPS`, `HEART_RATE`, `PAUSE`, `AUTO_PAUSE`: what the device does when it records a
  workout of its own), `exerciseTypes` (the Health Connect exercise types it can record itself),
  `measurements` (subset of `HEART_RATE`, `HRV`, `SPO2`, `SKIN_TEMPERATURE`: what it measures),
  `lastSyncResult` (`SUCCEEDED`, `INTERRUPTED`, `PERMISSION_MISSING`, `DISCONNECTED`, `FAILED`)
  and `lastSyncAttemptAtMillis`; `lastSyncedAtMillis` stays the last sync that succeeded.
- `OpenLinkRecordingRequest`: `sessionId` (chosen by the client, unique), `exerciseType`
  (the Health Connect exercise type), `startedAtMillis`, `deviceId` (null: every device that
  supports recording), and from version 2 `mode` (`STREAM`, the default, or `RECORD`; `deviceId`
  is required in `RECORD`).
- `OpenLinkRecordingState`: `sessionId`, `deviceId`, `phase` (`STARTING`, `RECORDING`, `PAUSED`
  from version 2, `STOPPED`, `FAILED`), `reason` (a code: `no_device`, `not_connected`,
  `unsupported`, `stopped_by_client`, `host_stopped`, and from version 2 `stopped_on_device`,
  `busy`, `no_association`, `no_gps`), `atMillis`, and from version 2 `summary`.
- `OpenLinkWorkoutSummary` (version 2), with `STOPPED` of a recorded workout: `zoneSeconds` and
  `zoneLowerBoundsBpm` (per heart-rate zone, zone 1 first), `trimp`, `heartRateRecovery1MinBpm`,
  `heartRateRecovery2MinBpm`, all optional. Only what Health Connect has no type for: pauses go
  to Health Connect as pause segments and splits as laps. The host may report `STOPPED` again
  with a fuller summary once the recovery is known; it reaches the owner like the first.
- `OpenLinkDataWritten` (version 2): `recordTypes` (Health Connect record names without
  "Record", such as `HeartRate`), `fromMillis`, `toMillis`, `deviceId`. The host wrote new data
  to Health Connect; no values. A client reads Health Connect again instead of polling it.
- `OpenLinkLiveSubscription`: `sessionId`, `types` (subset of `HEART_RATE`, `STEPS`,
  `CADENCE`, `POWER`), `deviceId`.
- `OpenLinkLiveBatch`: `sessionId`, `deviceId`, and samples (`type`, `atMillis`, `value`),
  batched by the host at most once a second. The host stamps each sample; the client takes
  `atMillis` as the sample's time.

### Delegated recording (version 2)

In `RECORD` mode the wearable records the whole workout and the host saves it to Health Connect;
the client only controls it. Whoever records, writes: one recorder, one session in Health Connect,
nothing merged.

The host library (`OpenLinkSessions`) enforces every rule that needs no device, so each host app
does not build them again, and hands the host app only what changes a workout:

- A request in `RECORD` mode needs a `deviceId`; without one it gets `FAILED`, reason
  `no_device`. A session id is at most 128 characters, and one app holds at most 4 recorded
  workouts at once (more get `FAILED`, reason `busy`, as does a `RECORD` start under the id of
  the app's own stream session).
- A repeated start, pause, resume or stop changes nothing and is not handed on. A repeated start
  is answered with the workout's last reported state, so a client that reconnects learns where
  the workout stands (after a restart of the host that state is unknown until the host app
  reports again). A start that got `FAILED` before the workout ran, from the library or from the
  host app (`busy`, `not_connected`), uses up nothing: it may be tried again with the same
  session id. A workout ran once the host app reported it `RECORDING` or `PAUSED`; its end, a
  failure included, is remembered like any other.
- The host holds the state (recording or paused) with the time of its last change, and drops a
  request older than that. Every time is the phone's clock. A pause or resume the host app
  reports from the wearable counts as a change, so a client's older wish that it replays after a
  reconnect does not undo a press on the watch. A report older than the last change, or one that
  repeats the state, is no change.
- The host takes a pause or resume it hands on as done. The host app reports the state the
  workout is in after each, also when the wearable did not take it, so a request that failed may
  be made again.
- Pause and resume are for `RECORD` only; a stream session has no pause.
- A stop from the owner does not end the session at once: it stays the owner's until the host
  app reports `STOPPED` or `FAILED`, so that end, with the workout's summary, reaches the owner.
  While it is stopping, no pause or resume is handed on. The host app must report an end for every
  stop, also for a device that is gone (`FAILED`, `not_connected`); without one the library takes
  the workout as stopped by the host (`host_stopped`) after 30 minutes and tells the owner, and
  an end reported later still reaches the owner. A workout the owner stopped is not handed to the
  host app as stopped again when its owner loses the capability.
- A recorded workout that ends because its owner lost the capability is remembered as
  `STOPPED`, `host_stopped`, so a start the client replays once it has the capability again does
  not begin a new workout. The client library, too, drops a recorded workout a host had once it
  hears the capability is gone, and reports it `STOPPED`, `host_stopped`; so too one it stopped
  whose end it has not heard.
- An ended workout is remembered for its owner: another app may use the same session id. A
  report older than that app's start is about the ended workout, so its fuller end still reaches
  its owner and does not end the other app's.
- A stop for a session the caller does not hold is answered with its end: the remembered one if
  the workout ended, else `STOPPED`, `stopped_by_client` (the host never began it).
- Only the owner may pause, resume or stop it, and it outlives its owner's binding (above).

What the host app enforces, because it knows its devices: one workout per device (a request for
a device that records already gets `FAILED`, reason `busy`), and that the wearable controls too.
A pause, resume or stop on the wearable reaches the owner through `onRecordingStateChanged` (a
stop with the reason `stopped_on_device`), so the owner holds no state of its own that could
disagree with the host's.

The client library delivers what it was asked while it was not connected, since such a session
outlives the binding: a stop of a `RECORD` session is kept until the host has it (one no host
was sent yet ends at once, `STOPPED`, `stopped_by_client`), and the last
pause or resume is sent again, with the time it was asked, after the start it replays, unless the
host has since reported a newer state. It takes in the end of a workout it stopped, and a fuller
end of one that ended, for the last 16 such workouts; in memory only, so a later summary for a
workout whose client process died meanwhile comes back through the replayed start.

### What a recording session means in `STREAM` mode (version 1)

Neither a Garmin watch (over the protocol OpenWatch speaks) nor the OpenWatch Wear OS app
can be told to start a workout of its own. A session therefore means: the host keeps the
wearable's live readings flowing for the session's length, reports `RECORDING` once the first
reading arrives (or `FAILED` with a reason), and serves them to a live subscription under the
same session id. The recording itself, the route and the exercise session are the client's,
as before; the wearable adds its heart rate. When a watch gains a workout command, the same
request starts it, and `exerciseType` tells it what to record.

Version 1 hosts produce `HEART_RATE` (Garmin and Wear OS) and `STEPS` (Garmin). `CADENCE`
and `POWER` are in the contract for a host that streams a bike sensor or a watch that
broadcasts them.

What the channel never carries: a Health Connect record, a route, a file, a name of a
notification, or anything that identifies a third person. Log lines on both sides count
calls and name capabilities; they never log a value.

## Library

This repository holds:

- `docs/spec.md`, this document.
- `contract/`: the AIDL files, the parcelables, the constants, and `OpenLinkSigners`, which
  reads a package's signing certificates for both sides. Nothing else.
- `host/`: `OpenLinkHost` (the binder, identity resolution, capability gating, session
  ownership and listener bookkeeping, with the connected clients and held sessions as
  flows), `OpenLinkHostCore` (the trust rules, no Android),
  `OpenLinkTrustStore`, `OpenLinkSessions` (who owns which session, no Android), and
  `OpenLinkHostService`, the exported service the host app subclasses. The app implements
  `OpenLinkHostCapabilities`. A host that offers `RECORD` passes `OpenLinkHost` a
  `sessionStorage` (the trust store's storage will do; the keys do not overlap): running
  `RECORD` sessions and the last 16 ended ones are kept there, so a host that restarts
  mid-workout still knows whose it is and still answers a replayed start with its end. The
  host app keeps its own state of the workout (recording or paused, since when) the same way.
- `client/`: `OpenLinkClient`, which binds, retries with backoff when the host is missing or
  dies, says hello and registers its listener on every connection, replays what the app asked
  for (the profile to push, the sessions and subscriptions it holds), and exposes the state as
  flows: `HostMissing`, `HostUntrusted` (installed under a key neither known nor approved),
  `Unreachable` (installed, but the binding was refused), `Connecting`,
  `Connected` with the host's decision, and the time a profile last crossed. The state machine is `OpenLinkClientCore`, tested without Android.

No Hilt, no Compose, no Health Connect, no network. The apps take it as a git submodule at
`openlink/` and a Gradle composite build (`includeBuild("openlink")`), depending on
`tech.mmarca.openlink:host` or `tech.mmarca.openlink:client`. The modules carry the `ci` and
`nightly` build types the apps build. The first third-party client is the trigger for
publishing the modules to Maven Central.
