# OpenLink

The control channel between [OpenVitals](https://github.com/OpenVitals-MTU/android-app) and
[OpenWatch](https://github.com/OpenVitals-MTU/OpenWatch). Health data travels through Health
Connect; OpenLink carries what Health Connect cannot: the body profile, the state of the
paired watches and scale with a "sync now", a request to keep a wearable streaming while the
phone records an activity, and the live heart rate it streams.

It is an Android IPC contract: a bound service with an AIDL interface, a trust store on the
host side and a consent screen. No network, no account, no server. The protocol, the trust
model and the versioning rules are in [docs/spec.md](docs/spec.md).

| Module | What |
| --- | --- |
| `contract` | AIDL interfaces, parcelables, constants |
| `host` | for the app that owns a capability: the binder, the trust rules, the exported service |
| `client` | for the app that uses it: binding, reconnects, replay, flows |

## Use

Add this repository as a git submodule at `openlink/`, then in `settings.gradle.kts`:

```kotlin
includeBuild("openlink")
```

and depend on `tech.mmarca.openlink:host` or `tech.mmarca.openlink:client`.

## Build

JDK 17 or 21 and the Android SDK (platform 37).

```bash
./gradlew verifyCi
```

runs the unit tests (the trust rules, session ownership and the client's replay run on the
JVM) and lint of the three modules.

## License

OpenLink is free software under the GNU Affero General Public License, version 3 or (at your
option) any later version (AGPL-3.0-or-later), the same license as OpenVitals and OpenWatch.
See [LICENSE](LICENSE). An app that includes the library is a work based on it.

## Wiki

The [wiki](https://github.com/Hale-MTU/OpenLink/wiki) mirrors this repository's Markdown on every push to `main` (`.github/workflows/wiki.yml`, `scripts/sync-wiki.py`, the same script as in OpenVitals, OpenWatch and OpenBridge). Edit the files here; the wiki is overwritten. `python3 scripts/sync-wiki.py --repo Hale-MTU/OpenLink --check` fails on a relative link to a file that does not exist, and runs in the verify workflow.
