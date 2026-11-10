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
nothing; the app asks the user and calls `trustHost(digest)`. The approval is for that
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
4. `BLOCKED` is final until the user clears it in Settings. A blocked client's calls return
   `OpenLinkState(BLOCKED)` and nothing else.
5. Revoking a capability takes effect on the next call and closes any live subscription
   under it immediately. It stays revoked until the user grants it again, for a first-party
   client too.
6. A capability a client did not ask for in `hello()` is never granted to it.
7. The host keeps at most 10 `PENDING` records and drops the oldest first. A `PENDING` record
   not seen for 30 days is dropped. Any app can say hello, so it must not fill the host's
   screens.

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
}

oneway interface IOpenLinkListener {
    void onStateChanged(in OpenLinkState state);                  // trust or capabilities changed
    void onProfileChanged(in OpenLinkProfile profile);
    void onDevicesChanged(in List<OpenLinkDevice> devices);
    void onRecordingStateChanged(in OpenLinkRecordingState state);
    void onLiveSamples(in OpenLinkLiveBatch batch);
}
```

`OpenLinkHello` carries `protocolVersion`, `appVersion`, and `requestedCapabilities`.
`OpenLinkState` carries `protocolVersion`, `trust` (`PENDING`, `TRUSTED`, `BLOCKED`),
`grantedCapabilities`, and `consentIntent` (a `PendingIntent` to the host's consent screen,
present while pending). A client sends it from a tap in its visible activity, with
`ActivityOptions` that lend its foreground to the launch (`OpenLinkClient.openConsent`): from
Android 14 the system blocks it otherwise, since the host that made it is in the background.

A recording session and a live subscription belong to the client that opened them: their
events go to it alone, another client cannot stop them, and they end when their owner loses
the capability, unbinds or dies.

A session has no time limit and a start no rate limit: only a client the user trusts with
`recording` can open one, an activity may last a day, and a start that follows a stop at once
is an ordinary correction. What bounds a session is its owner's binding, and the notification
the host shows while one runs.

Every call under a capability the caller lacks is a no-op that logs a count; synchronous ones
return an empty result. The host never throws across the binder for a permission question,
so an old client never crashes against a stricter host.

### Versioning

`protocolVersion` is an integer, 1 in this version. Both sides send theirs in `hello()`; the
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
  `supportsSync`, `supportsRecording`, `liveSampleTypes`.
- `OpenLinkRecordingRequest`: `sessionId` (chosen by the client, unique), `exerciseType`
  (the Health Connect exercise type), `startedAtMillis`, `deviceId` (null: every device that
  supports recording).
- `OpenLinkRecordingState`: `sessionId`, `deviceId`, `phase` (`STARTING`, `RECORDING`,
  `STOPPED`, `FAILED`), `reason` (a code: `no_device`, `not_connected`, `unsupported`,
  `stopped_by_client`, `host_stopped`), `atMillis`.
- `OpenLinkLiveSubscription`: `sessionId`, `types` (subset of `HEART_RATE`, `STEPS`,
  `CADENCE`, `POWER`), `deviceId`.
- `OpenLinkLiveBatch`: `sessionId`, `deviceId`, and samples (`type`, `atMillis`, `value`),
  batched by the host at most once a second. The host stamps each sample; the client takes
  `atMillis` as the sample's time.

### What a recording session means in version 1

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
  `OpenLinkTrustStore`, and `OpenLinkHostService`, the exported service the host app
  subclasses. The app implements `OpenLinkHostCapabilities`.
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
