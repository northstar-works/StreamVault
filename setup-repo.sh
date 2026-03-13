#!/bin/bash
# ============================================================
#  StreamVault — GitHub + F-Droid Repo Setup Script
# ============================================================
#
#  This script initializes the git repo, commits all files,
#  and pushes to GitHub. After push it creates the v1.0.0 tag
#  which triggers the CI workflows to build the APK and
#  publish the F-Droid repo via GitHub Pages.
#
#  PREREQUISITES:
#    1. git installed
#    2. GitHub CLI (gh) installed and authenticated
#       - Install: https://cli.github.com
#       - Auth:    gh auth login
#    3. (Optional) Android Studio or Gradle for local builds
#
#  USAGE:
#    chmod +x setup-repo.sh
#    ./setup-repo.sh
#
# ============================================================

set -e

REPO_NAME="StreamVault"
GITHUB_USER="sidscri"  # <-- Set your GitHub username here, or leave blank to auto-detect

echo "╔══════════════════════════════════════════╗"
echo "║   StreamVault — GitHub + F-Droid Setup   ║"
echo "╚══════════════════════════════════════════╝"
echo ""

# ── Detect GitHub user ──
if [ -z "$GITHUB_USER" ]; then
    GITHUB_USER=$(gh api user -q .login 2>/dev/null || echo "")
    if [ -z "$GITHUB_USER" ]; then
        echo "❌ Could not detect GitHub username."
        echo "   Either set GITHUB_USER in this script or run: gh auth login"
        exit 1
    fi
fi
echo "→ GitHub user: $GITHUB_USER"
echo "→ Repo: $GITHUB_USER/$REPO_NAME"
echo ""

# ── Step 1: Initialize git ──
echo "▸ Step 1: Initializing git repo..."
git init
git checkout -b main

# ── Step 2: Initial commit ──
echo "▸ Step 2: Committing all files..."
git add -A
git commit -m "Initial commit: StreamVault v1.0.0

- Media library with add/edit/delete and categories
- Integrated iframe-based streaming player
- Overlay controls (back, prev, play/pause, next)
- Local storage persistence
- Full Android WebView wrapper
- F-Droid metadata and fastlane structure
- GitHub Actions CI for APK builds
- GitHub Pages workflow for F-Droid repo hosting"

# ── Step 3: Create GitHub repo ──
echo "▸ Step 3: Creating GitHub repository..."
gh repo create "$REPO_NAME" \
    --public \
    --description "Personal media streaming organizer with integrated player — F-Droid ready" \
    --source . \
    --remote origin \
    --push 2>/dev/null || {
        echo "   Repo may already exist. Setting remote..."
        git remote add origin "https://github.com/$GITHUB_USER/$REPO_NAME.git" 2>/dev/null || true
        git push -u origin main
    }

echo "   ✓ Pushed to https://github.com/$GITHUB_USER/$REPO_NAME"

# ── Step 4: Enable GitHub Pages ──
echo "▸ Step 4: Enabling GitHub Pages..."
gh api -X PUT "repos/$GITHUB_USER/$REPO_NAME/pages" \
    --input - <<< '{"build_type":"workflow"}' 2>/dev/null || {
    echo "   ⚠ Could not auto-enable Pages. Enable manually:"
    echo "     Settings → Pages → Source: GitHub Actions"
}

# ── Step 5: Create release tag ──
echo "▸ Step 5: Creating v1.0.0 tag and release..."
git tag -a v1.0.0 -m "StreamVault v1.0.0 — Initial Release"
git push origin v1.0.0

gh release create v1.0.0 \
    --title "StreamVault v1.0.0" \
    --notes "### Initial Release

**Features:**
- Media library with custom titles, URLs, and categories
- Integrated streaming video player
- Overlay controls: back, previous, play/pause, next
- Category filters and search
- Persistent local storage
- Dark theme UI

**Install:**
- Download the APK from this release (attached after CI build completes)
- Or add the F-Droid repo: \`https://$GITHUB_USER.github.io/$REPO_NAME/fdroid/repo\`" \
    2>/dev/null || echo "   ⚠ Release creation skipped (may need manual creation)"

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║                    ✓ DONE!                       ║"
echo "╠══════════════════════════════════════════════════╣"
echo "║                                                  ║"
echo "║  Repo: github.com/$GITHUB_USER/$REPO_NAME"
echo "║                                                  ║"
echo "║  CI will now:                                    ║"
echo "║  1. Build debug + release APKs                   ║"
echo "║  2. Attach APKs to the v1.0.0 release            ║"
echo "║  3. Generate F-Droid repo on GitHub Pages         ║"
echo "║                                                  ║"
echo "║  F-Droid repo URL (after Pages deploys):          ║"
echo "║  https://$GITHUB_USER.github.io/$REPO_NAME/fdroid/repo"
echo "║                                                  ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""
echo "Next steps:"
echo "  1. Wait ~3 min for GitHub Actions to finish"
echo "  2. Check: github.com/$GITHUB_USER/$REPO_NAME/actions"
echo "  3. Add F-Droid repo URL to your F-Droid app"
echo "  4. (Optional) Submit to official F-Droid — see README"
