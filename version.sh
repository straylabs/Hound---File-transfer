#!/bin/bash

# Play Store Version Update Script
# Usage: ./version.sh [major|minor|patch|build]

set -e

VERSION_FILE="version.properties"
GRADLE_FILE="app/build.gradle.kts"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}=== Play Store Version Update ===${NC}"

# Check if version.properties exists, create if not
if [ ! -f "$VERSION_FILE" ]; then
    echo "versionCode=1" > "$VERSION_FILE"
    echo "versionName=1.0.0" >> "$VERSION_FILE"
    echo -e "${YELLOW}Created new version file${NC}"
fi

# Read current version
source "$VERSION_FILE"

# Determine version type
VERSION_TYPE=${1:-patch}

case $VERSION_TYPE in
    major)
        # Increment major, reset minor and patch
        IFS='.' read -ra VERSION_PARTS <<< "$versionName"
        MAJOR=$((VERSION_PARTS[0] + 1))
        MINOR=0
        PATCH=0
        NEW_VERSION_NAME="${MAJOR}.${MINOR}.${PATCH}"
        ;;
    minor)
        # Increment minor, reset patch
        IFS='.' read -ra VERSION_PARTS <<< "$versionName"
        MAJOR=${VERSION_PARTS[0]}
        MINOR=$((VERSION_PARTS[1] + 1))
        PATCH=0
        NEW_VERSION_NAME="${MAJOR}.${MINOR}.${PATCH}"
        ;;
    patch)
        # Increment patch
        IFS='.' read -ra VERSION_PARTS <<< "$versionName"
        MAJOR=${VERSION_PARTS[0]}
        MINOR=${VERSION_PARTS[1]}
        PATCH=$((VERSION_PARTS[2] + 1))
        NEW_VERSION_NAME="${MAJOR}.${MINOR}.${PATCH}"
        ;;
    build)
        # Just increment version code for CI builds
        NEW_VERSION_NAME="$versionName"
        ;;
    *)
        echo -e "${RED}Invalid version type. Use: major, minor, patch, or build${NC}"
        exit 1
        ;;
esac

# Increment version code
NEW_VERSION_CODE=$((versionCode + 1))

# Update version.properties
cat > "$VERSION_FILE" << EOF
versionCode=$NEW_VERSION_CODE
versionName=$NEW_VERSION_NAME
EOF

echo -e "${GREEN}Version updated:${NC}"
echo -e "  Version Code: ${versionCode} -> ${NEW_VERSION_CODE}"
echo -e "  Version Name: ${versionName} -> ${NEW_VERSION_NAME}"

# Update build.gradle.kts
sed -i '' "s/versionCode = .*/versionCode = $NEW_VERSION_CODE/" "$GRADLE_FILE"
sed -i '' "s/versionName = \".*\"/versionName = \"$NEW_VERSION_NAME\"/" "$GRADLE_FILE"

echo -e "${GREEN}Updated $GRADLE_FILE${NC}"

# Ask for commit
echo ""
read -p "Create git commit and tag? (y/n) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    git add -A
    git commit -m "Bump version to $NEW_VERSION_NAME ($NEW_VERSION_CODE)"
    git tag -a "v$NEW_VERSION_NAME" -m "Version $NEW_VERSION_NAME"
    echo -e "${GREEN}Created commit and tag v$NEW_VERSION_NAME${NC}"
fi

# Ask to build
echo ""
read -p "Build release APK? (y/n) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    ./gradlew assembleRelease
    echo -e "${GREEN}Release APK built successfully!${NC}"
    echo "APK location: app/build/outputs/apk/release/"
fi

echo -e "${GREEN}Done!${NC}"
