# StreamVault

Personal media streaming organizer with an integrated video player. Save streaming URLs with custom titles, organize by category, and play them through a built-in player with overlay controls.

**F-Droid ready** — includes fastlane metadata, fdroiddata build recipe, and a GitHub Actions workflow that publishes a self-hosted F-Droid repo via GitHub Pages.

Latest update highlights: HDHomeRun repo-style channel loading and selection, optional XMLTV guide matching for HDHR, Tailscale/VPN-friendly remote mode for saved LAN tuner addresses, and a stricter HDHR MPEG-TS playback path with added diagnostics.

---

## Features

- **Media Library** — Add, edit, delete streams with custom titles
- **Categories** — Tag and filter your collection
- **Search** — Instant filtering by title or URL
- **Integrated Player** — Streams in a full-screen embedded player
- **Overlay Controls** — Tap screen → back / prev / play·pause / next
- **Skip Navigation** — Cycle through your entire library
- **Persistent Storage** — Library survives app restarts (localStorage)
- **Fullscreen Video** — Native Android fullscreen for embedded videos
- **No tracking, no ads, no accounts** — Fully local

Designed for URLs in this format:
```
http://webplayer.online/?url=https://example.com/path/@top
```
Works with any iframe-compatible streaming URL.

---

## Quick Start — Automated Setup

The fastest way to get this running on GitHub with F-Droid repo hosting:

```bash
# 1. Install GitHub CLI if you haven't
#    https://cli.github.com

# 2. Authenticate
gh auth login

# 3. Clone/copy the project, cd into it, run setup
cd StreamVault
chmod +x setup-repo.sh
./setup-repo.sh
```

The script will:
1. Initialize git and commit all files
2. Create a public GitHub repo called `StreamVault`
3. Enable GitHub Pages
4. Push and tag `v1.0.0`
5. CI builds the APK and publishes the F-Droid repo

Your F-Droid repo URL will be:
```
https://<your-username>.github.io/StreamVault/fdroid/repo
```

---

## Manual Setup — Step by Step

### 1. Create the GitHub Repo

```bash
cd StreamVault
git init
git checkout -b main
git add -A
git commit -m "Initial commit: StreamVault v1.0.0"

# Create repo on GitHub
gh repo create StreamVault --public --source . --remote origin --push
```

### 2. Enable GitHub Pages

Go to **Settings → Pages → Build and deployment → Source** and select **GitHub Actions**.

### 3. Tag a Release

```bash
git tag -a v1.0.0 -m "v1.0.0"
git push origin v1.0.0
```

This triggers two GitHub Actions workflows:
- **Build & Release APK** — Builds debug + release APKs and attaches them to the GitHub release
- **F-Droid Repo** — Generates the F-Droid repo index and deploys to GitHub Pages

### 4. Add Repo to F-Droid App

1. Open **F-Droid** on your phone
2. Go to **Settings → Repositories**
3. Tap **+** to add a new repository
4. Paste: `https://<your-username>.github.io/StreamVault/fdroid/repo`
5. Tap **Add**
6. Pull to refresh — StreamVault will appear in your app list

---

## Submitting to Official F-Droid

To get StreamVault listed in the main F-Droid repository:

### Prerequisites
- Repo must be public on GitHub/GitLab/Codeberg
- App must be fully open source (GPL-3.0 license is included)
- No proprietary dependencies
- Builds reproducibly from source

### Steps

1. **Fork** [fdroiddata](https://gitlab.com/fdroid/fdroiddata) on GitLab

2. **Copy the metadata file** into your fork:
   ```bash
   cp metadata/com.sidscri.streamvault.yml \
      /path/to/fdroiddata/metadata/com.sidscri.streamvault.yml
   ```

3. **Test the build locally** (optional but recommended):
   ```bash
   cd /path/to/fdroiddata
   fdroid build -v -l com.sidscri.streamvault
   ```

4. **Submit a Merge Request** to `fdroiddata` on GitLab

5. **Respond to review** — F-Droid maintainers will review the build recipe

The metadata file at `metadata/com.sidscri.streamvault.yml` is already formatted for fdroiddata. Fastlane metadata in `fastlane/metadata/android/` provides the app description, title, and changelogs.

---

## Project Structure

```
StreamVault/
├── .github/workflows/
│   ├── build.yml              # CI: build APK on push/tag
│   └── fdroid-repo.yml        # CI: publish F-Droid repo to Pages
├── app/
│   ├── build.gradle           # App-level Gradle config
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── index.html     # ★ Full app UI (HTML/CSS/JS)
│       ├── java/.../
│       │   └── MainActivity.java  # WebView wrapper
│       └── res/
│           ├── drawable/      # Vector icon assets
│           ├── mipmap-*/      # Launcher icons (all densities)
│           ├── values/        # Themes, strings
│           └── xml/           # Network security config
├── fastlane/metadata/android/en-US/
│   ├── full_description.txt   # F-Droid long description
│   ├── short_description.txt  # F-Droid short description
│   ├── title.txt              # App name
│   └── changelogs/1.txt       # Changelog for versionCode 1
├── metadata/
│   └── com.sidscri.streamvault.yml  # fdroiddata build recipe
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── build.gradle               # Project-level Gradle
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat      # Gradle wrapper scripts
├── setup-repo.sh              # One-command GitHub + F-Droid setup
├── LICENSE                    # GPL-3.0
└── README.md
```

---

## Building Locally

### Android Studio
1. Open the `StreamVault/` folder in Android Studio
2. Wait for Gradle sync
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. Output: `app/build/outputs/apk/debug/app-debug.apk`

### Command Line
```bash
# Generate wrapper jar if missing
gradle wrapper --gradle-version 8.4

# Build
chmod +x gradlew
./gradlew assembleDebug     # Debug APK
./gradlew assembleRelease   # Release APK (unsigned)
```

### Standalone HTML (no build needed)
The file `app/src/main/assets/index.html` works in any browser. On Android you can use Chrome → Menu → "Add to Home Screen" for an app-like experience.

---

## Releasing New Versions

1. Update `versionCode` and `versionName` in `app/build.gradle`
2. Add a changelog at `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
3. Commit, tag, push:
   ```bash
   git add -A
   git commit -m "Release v1.1.0"
   git tag -a v1.1.0 -m "v1.1.0"
   git push origin main --tags
   ```
4. CI will build, attach APKs to the release, and update the F-Droid repo

If you're listed on official F-Droid, also update `metadata/com.sidscri.streamvault.yml` with the new version info and submit a merge request to fdroiddata.

---

## Customization

### Theme Colors
Edit CSS variables at the top of `app/src/main/assets/index.html`:
```css
:root {
  --bg-deep: #0a0a0f;
  --accent: #6c5ce7;
  --text-primary: #e8e6f0;
}
```

### Package Name
1. Update `namespace` and `applicationId` in `app/build.gradle`
2. Update `package` in `AndroidManifest.xml`
3. Move `MainActivity.java` to match the new package path
4. Update `metadata/*.yml` filename and contents

---

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE)


## Recent UI fixes

- 5.6.25-b154: fixed EPG picker Back behavior on Fire TV so the search window can close cleanly without leaving the keyboard stuck onscreen.
- 5.6.25-b154: improved Home edit-mode D-pad navigation so moving right from the left item action buttons can continue into the right-side item in landscape grid mode.

- 5.7.0-b155: added HDHomeRun discovery in Settings with a dedicated HDHR Home tab, device scan, channel import, and optional remote playback base URL support.
- 5.7.0-b155: hardened manual Home refresh to prevent overlapping repo/EPG refresh runs that could destabilize large EPG updates on Fire TV devices.
