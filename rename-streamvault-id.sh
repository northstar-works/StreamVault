#!/usr/bin/env bash
# Renames com.sidscri.streamvault → com.northstarworks.streamvault throughout the project
# Run from the root of the StreamVault repo

set -e

OLD_ID="com.sidscri.streamvault"
NEW_ID="com.northstarworks.streamvault"
OLD_PATH="com/sidscri/streamvault"
NEW_PATH="com/northstarworks/streamvault"

echo "=== StreamVault package ID rename ==="
echo "  $OLD_ID → $NEW_ID"
echo ""

# ── 1. Text replacement in all relevant file types ──────────────────────────
echo "[1/3] Replacing package references in source files..."

find . \
  -type f \
  \( -name "*.java" -o -name "*.kt" -o -name "*.xml" \
     -o -name "*.gradle" -o -name "*.gradle.kts" \
     -o -name "*.pro" -o -name "*.properties" \
     -o -name "*.json" -o -name "*.yml" -o -name "*.yaml" \) \
  -not -path "./.git/*" \
  -not -path "*/build/*" \
  | while read -r file; do
      if grep -qF "$OLD_ID" "$file"; then
        sed -i "s|${OLD_ID}|${NEW_ID}|g" "$file"
        echo "  patched: $file"
      fi
    done

# ── 2. Move the Java/Kotlin source directory ─────────────────────────────────
echo ""
echo "[2/3] Moving source directory..."

SRC_ROOT="app/src/main/java"
OLD_SRC="$SRC_ROOT/$OLD_PATH"
NEW_SRC="$SRC_ROOT/$NEW_PATH"

if [ -d "$OLD_SRC" ]; then
  mkdir -p "$(dirname "$NEW_SRC")"
  mv "$OLD_SRC" "$NEW_SRC"
  echo "  moved: $OLD_SRC → $NEW_SRC"

  # Clean up leftover empty parent dirs (com/sidscri)
  rmdir "$SRC_ROOT/com/sidscri" 2>/dev/null || true
  echo "  cleaned up: $SRC_ROOT/com/sidscri"
else
  echo "  WARNING: $OLD_SRC not found — directory may already be renamed or structured differently"
fi

# Also handle test directories if present
for TEST_ROOT in "app/src/test/java" "app/src/androidTest/java"; do
  if [ -d "$TEST_ROOT/$OLD_PATH" ]; then
    mkdir -p "$(dirname "$TEST_ROOT/$NEW_PATH")"
    mv "$TEST_ROOT/$OLD_PATH" "$TEST_ROOT/$NEW_PATH"
    rmdir "$TEST_ROOT/com/sidscri" 2>/dev/null || true
    echo "  moved: $TEST_ROOT/$OLD_PATH → $TEST_ROOT/$NEW_PATH"
  fi
done

# ── 3. Verify ────────────────────────────────────────────────────────────────
echo ""
echo "[3/3] Checking for any remaining references to old ID..."

REMAINING=$(grep -rF "$OLD_ID" . \
  --include="*.java" --include="*.kt" --include="*.xml" \
  --include="*.gradle" --include="*.gradle.kts" \
  --exclude-dir=".git" --exclude-dir="build" \
  2>/dev/null || true)

if [ -z "$REMAINING" ]; then
  echo "  ✓ No remaining references found."
else
  echo "  ⚠ Remaining references (review manually):"
  echo "$REMAINING"
fi

echo ""
echo "Done. Next steps:"
echo "  1. Do a clean build: ./gradlew clean assembleRelease"
echo "  2. Bump versionCode in app/build.gradle (package ID change = new install)"
echo "  3. Update metadata/com.northstarworks.streamvault.yml with new version"
echo "  4. Commit and push"
