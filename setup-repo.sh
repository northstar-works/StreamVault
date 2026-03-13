#!/bin/bash
# ============================================================
#  StreamVault — GitHub + F-Droid Repo Setup
# ============================================================
#  Prerequisites: git, gh (GitHub CLI, authenticated)
#  Usage:  chmod +x setup-repo.sh && ./setup-repo.sh
# ============================================================
set -e

REPO_NAME="StreamVault"
GITHUB_USER=""  # Leave blank to auto-detect

echo ""
echo "╔══════════════════════════════════════════╗"
echo "║   StreamVault — GitHub + F-Droid Setup   ║"
echo "╚══════════════════════════════════════════╝"
echo ""

# ── Detect GitHub user ──
if [ -z "$GITHUB_USER" ]; then
    GITHUB_USER=$(gh api user -q .login 2>/dev/null || echo "")
    if [ -z "$GITHUB_USER" ]; then
        echo "❌ Cannot detect GitHub username."
        echo "   Set GITHUB_USER in this script or run: gh auth login"
        exit 1
    fi
fi
echo "→ User: $GITHUB_USER"
echo "→ Repo: $GITHUB_USER/$REPO_NAME"
echo ""

# ── Step 1: Init git ──
echo "▸ [1/5] Initializing git..."
git init
git checkout -b main 2>/dev/null || git branch -M main

# ── Step 2: Commit everything ──
echo "▸ [2/5] Committing files..."
git add -A
git commit -m "Initial commit — StreamVault v1.0.0

Features:
- Media library with add/edit/delete and categories
- Integrated iframe-based streaming player
- Overlay controls (back, prev, play/pause, next)
- Local storage persistence
- Android WebView wrapper
- F-Droid metadata + fastlane structure
- GitHub Actions CI → APK build + F-Droid repo on Pages"

# ── Step 3: Create GitHub repo + push ──
echo "▸ [3/5] Creating GitHub repo and pushing..."
gh repo create "$REPO_NAME" \
    --public \
    --description "Personal media streaming organizer with integrated player" \
    --source . \
    --remote origin \
    --push 2>/dev/null || {
        echo "   Repo may exist already, setting remote..."
        git remote remove origin 2>/dev/null || true
        git remote add origin "https://github.com/$GITHUB_USER/$REPO_NAME.git"
        git push -u origin main --force
    }

echo "   ✓ Pushed to github.com/$GITHUB_USER/$REPO_NAME"

# ── Step 4: Enable GitHub Pages (source: Actions) ──
echo "▸ [4/5] Enabling GitHub Pages..."
# This API call sets Pages to deploy from GitHub Actions
gh api -X POST "repos/$GITHUB_USER/$REPO_NAME/pages" \
    --input - <<< '{"build_type":"workflow","source":{"branch":"main","path":"/"}}' 2>/dev/null || \
gh api -X PUT "repos/$GITHUB_USER/$REPO_NAME/pages" \
    --input - <<< '{"build_type":"workflow","source":{"branch":"main","path":"/"}}' 2>/dev/null || {
    echo "   ⚠  Auto-enable failed. Enable manually:"
    echo "      Settings → Pages → Source → GitHub Actions"
}

# ── Step 5: Tag and release ──
echo "▸ [5/5] Creating v1.0.0 tag..."
git tag -a v1.0.0 -m "StreamVault v1.0.0"
git push origin v1.0.0

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║                      ✓ DONE                         ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║                                                      ║"
echo "║  GitHub:  github.com/$GITHUB_USER/$REPO_NAME"
echo "║                                                      ║"
echo "║  Wait ~3 min, then:                                  ║"
echo "║  • APK build:  Actions tab (should be running now)   ║"
echo "║  • Release:    Releases tab (auto-created on tag)    ║"
echo "║  • F-Droid:    Pages deployed automatically          ║"
echo "║                                                      ║"
echo "║  F-Droid repo URL:                                   ║"
echo "║  https://$GITHUB_USER.github.io/$REPO_NAME/fdroid/repo"
echo "║                                                      ║"
echo "║  Pages landing:                                      ║"
echo "║  https://$GITHUB_USER.github.io/$REPO_NAME/          ║"
echo "║                                                      ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""
echo "If Pages shows 404, check:"
echo "  1. github.com/$GITHUB_USER/$REPO_NAME/actions → wait for green"
echo "  2. Settings → Pages → Source must be 'GitHub Actions'"
echo "  3. Re-run the workflow from Actions tab if needed"
