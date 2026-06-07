# Release & CI

Orbital Frontier uses two GitHub Actions workflows.

## Continuous integration — `.github/workflows/android-ci.yml`

Runs on every pull request and every push to `main` that touches `core/`, `android/`,
the Gradle build, or the workflow itself:

- `:core:test` — JVM unit tests for the game logic (movement model, settings persistence)
- `:core:ktlintCheck` + `:android:ktlintCheck` — style
- `:android:lint` — Android lint (report uploaded as an artifact)
- `:android:assembleDebug` + `:android:bundleDebug` — APK/AAB packaging
- native-libs regression guard — asserts `libgdx.so` + `libgdx-box2d.so` are packaged in the APK
- the debug APK is uploaded as a build artifact

Instrumented/device tests are intentionally not run in CI (no emulator on GitHub runners);
the device-only acceptance criteria (rendering, Box2D-on-device, 60 FPS) are verified
manually with the `android-emulator-setup` skill.

## Release — `.github/workflows/android-release.yml`

Triggered by pushing a semver tag **`vX.Y.Z`** (e.g. `v0.1.0`, `v0.1.0-rc1`):

1. Derives `versionName` from the tag and `versionCode` from the run number.
2. Decodes the signing keystore onto tmpfs (RAM-backed, never on disk).
3. Builds a **signed** release APK + AAB (`:android:assembleRelease :android:bundleRelease`).
4. Verifies the APK is actually signed (`apksigner verify`).
5. Publishes a GitHub Release with both artifacts and an auto-generated changelog.

### Required repository secrets

Set these under **Settings → Secrets and variables → Actions**:

| Secret | Contents |
|---|---|
| `RELEASE_ANDROID_SIGNATURE_KEYSTORE_FILE` | base64 of your `keystore.jks` (`base64 -w0 keystore.jks`) |
| `RELEASE_ANDROID_SIGNATURE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_ANDROID_SIGNATURE_KEY_ALIAS` | signing-key alias |
| `RELEASE_ANDROID_SIGNATURE_KEY_PASSWORD` | signing-key password |

Generate a keystore once with:

```
keytool -genkeypair -v -keystore keystore.jks -alias orbital-frontier \
    -keyalg RSA -keysize 2048 -validity 10000
```

Keep `keystore.jks` out of git (it is covered by `.gitignore`). If a release runs without
the secrets set, the build fails fast rather than shipping an unsigned APK.

### Curated release notes

The auto-generated changelog is only a baseline. After a release is published, run the
**`generate-release`** skill to write curated **New features** / **Bugfixes** notes derived
from the actual diff since the previous `v*` tag, each linked to its use case(s). Do not
hand-copy the previous release's body — that is how stale notes accumulate.

### Cutting a release

```
git tag v0.1.0
git push origin v0.1.0
```

The local default version (`0.1.0`, `versionCode 1`) in `android/build.gradle.kts` is only a
fallback for local/debug builds; CI overrides both from the tag and run number.
