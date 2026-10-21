# Releases

CI builds every push to `main` and every pull request with the project's own scripts
(`.github/actions/build`): the pinned images from `tools/Containerfile.*`,
`tools/fetch-deps.sh`, `tools/check.sh`, and `tools/android-build.sh --online`. The
`--online` run is verified against `gradle/verification-metadata.xml`; the rest of the
build has no network. The debug APK and the unsigned release APK are kept as workflow
artifacts for 14 days.

Pushing a tag `v<versionName>` runs `.github/workflows/release.yml`: the same build, then
`tools/sign-release.sh` signs `app-release-unsigned.apk` with the release key, checks it
(packaging invariants, not debuggable, signer), attaches a build-provenance attestation
(only once the repository is public: GitHub refuses them for private repositories on
this plan), and publishes a GitHub release with `HeadLink-<versionName>.apk` and
`SHA256SUMS`. A versionName with a hyphen (`0.5.0-beta1`) is published as a prerelease.
The release notes are `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## One-time setup

1. Create the release key. Keep the keystore and its password offline and backed up in
   two places: losing it means no update can ever install over an existing install, on
   F-Droid or anywhere else.

   ```sh
   keytool -genkeypair -keystore headlink-release.jks -alias headlink \
       -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=HeadLink"
   ```

   `keytool` is in the Android image (`podman run --rm -it --userns=keep-id
   --security-opt label=disable -v "$PWD:/k" -w /k localhost/openauto-android:p0.2
   keytool ...`). The `.gitignore` already refuses `*.jks`.

2. In the GitHub repository, Settings → Environments → New environment `release`
   (optionally restricted to `v*` tags, with a required reviewer), and add these secrets:

   | Secret | Value |
   | --- | --- |
   | `RELEASE_KEYSTORE_BASE64` | `base64 -w0 headlink-release.jks` |
   | `RELEASE_KEY_ALIAS` | `headlink` |
   | `RELEASE_KEYSTORE_PASSWORD` | the store password |
   | `RELEASE_KEY_PASSWORD` | the key password (the same, unless set differently) |

3. Record the signing certificate's SHA-256 (F-Droid's `AllowedAPKSigningKeys` needs it):

   ```sh
   keytool -list -v -keystore headlink-release.jks -alias headlink \
       | grep SHA256
   ```

## Cutting a release

1. Bump `versionCode` (by one) and `versionName` in `app/build.gradle.kts`.
2. Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (under 500
   characters; F-Droid shows it).
3. Commit, then `git tag v<versionName> && git push origin v<versionName>`.

Signing locally gives the same APK, from the same unsigned artifact:

```sh
./tools/android-build.sh
RELEASE_KEYSTORE=~/keys/headlink-release.jks RELEASE_KEY_ALIAS=headlink \
RELEASE_KEYSTORE_PASSWORD=... ./tools/sign-release.sh dist/HeadLink-0.5.0.apk
```

A release-signed APK will not install over a debug-key build on a phone (different
signer): uninstall first, which clears the app's settings and imported identity.

## F-Droid

F-Droid builds from source on its own build server; it does not take our APK. The
listing text and changelogs come from `fastlane/metadata/` in this repository. Getting
listed means a merge request to [fdroiddata](https://gitlab.com/fdroid/fdroiddata)
adding `metadata/com.jw6ventures.headlink.yml`. Not done yet:

- **A build recipe that runs without Podman.** F-Droid's build server is a Debian VM, so
  the recipe has to install Rust 1.85.1 with the `aarch64-linux-android` target (via the
  rustup srclib), fetch and checksum the OpenSSL 3.5.8 tarball, run
  `tools/openssl-build.sh` (which currently expects the tarball at `/cache/sources`),
  `cargo build -p aap-jni`, stage `target/android-jniLibs`, then let Gradle build
  `:app:assembleRelease`. Needs NDK 29.0.14206865.
- **Reproducible builds, if the APK should carry our signature.** With
  `Binaries:` and `AllowedAPKSigningKeys:` in the recipe, F-Droid builds the unsigned APK,
  compares it with our signed release, and ships ours if they match; otherwise it signs
  with its own key, and F-Droid installs and GitHub installs can't update each other.
  Matching needs a byte-identical `libaap_jni.so`, so Rust's embedded paths have to be
  remapped (`--remap-path-prefix`) to be the same in `/workspace` and on F-Droid's server.
  Not checked yet.
- **Anti-features.** Expect `NonFreeNet` (live traffic uses HERE, a proprietary service,
  opt-in with the operator's own key). Reviewers may also ask about the source identity:
  the app ships no credential, and does nothing until the operator imports one that
  their head unit trusts.
- **Trademarks.** The listing says "Android Auto head units" descriptively and disclaims
  any affiliation; the name HeadLink itself contains "Android", which F-Droid has
  accepted for other apps but Google's brand rules discourage.

## Google Play

Not set up, and not recommended without a decision on the risks:

- The app's purpose is to stand in for Google's Android Auto. That runs into Play's
  intellectual-property and impersonation policies, and a rejection or suspension is
  enforced against the developer account, not just the listing.
- Play requires an Android App Bundle for new apps (`:app:bundleRelease`, signed with an
  upload key), a privacy policy, the data-safety form (location, phone state, Bluetooth,
  the optional HERE traffic), a foreground-service declaration with a video for the
  connectedDevice service, and justification for `MANAGE_ONGOING_CALLS` and the
  companion-device permissions.
- Play App Signing re-signs with Google's key, so Play installs can't update from GitHub
  or F-Droid builds, or the other way round.

None of this is built; the release workflow produces APKs only.
