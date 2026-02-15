#!/bin/bash
# Manual release creation script for Phoebus
# Usage: ./create-release.sh v5.0.3-INFN

set -e

VERSION=${1}
if [ -z "$VERSION" ]; then
    echo "Usage: $0 <version-tag>"
    echo "Example: $0 v5.0.3-INFN"
    exit 1
fi

echo "========================================="
echo "Creating Phoebus Release: $VERSION"
echo "========================================="

# Step 1: Build
echo ""
echo "Step 1: Building Phoebus..."
mvn clean install -DskipTests

# Step 2: Find artifacts
echo ""
echo "Step 2: Locating build artifacts..."
ARTIFACTS=($(find phoebus-product/target -name "*.tar.gz" -o -name "*.zip" | grep -v "original"))

if [ ${#ARTIFACTS[@]} -eq 0 ]; then
    echo "Error: No build artifacts found!"
    exit 1
fi

echo "Found ${#ARTIFACTS[@]} artifacts:"
for artifact in "${ARTIFACTS[@]}"; do
    echo "  - $(basename $artifact)"
done

# Step 3: Check if gh CLI is installed
echo ""
echo "Step 3: Checking GitHub CLI..."
if ! command -v gh &> /dev/null; then
    echo "Error: GitHub CLI (gh) is not installed!"
    echo ""
    echo "Installation options:"
    echo "  macOS:   brew install gh"
    echo "  Ubuntu:  sudo apt install gh"
    echo "  Manual:  https://cli.github.com/"
    echo ""
    echo "After installation, authenticate with: gh auth login"
    exit 1
fi

# Step 4: Check authentication
echo ""
echo "Step 4: Checking GitHub authentication..."
if ! gh auth status &> /dev/null; then
    echo "Not authenticated. Running: gh auth login"
    gh auth login
fi

# Step 5: Create release
echo ""
echo "Step 5: Creating GitHub release..."
read -p "Enter release notes (or press Enter for auto-generated): " NOTES

if [ -z "$NOTES" ]; then
    NOTES="Release $VERSION

Automated build artifacts for Linux, Windows, and macOS.

## Installation
Download the appropriate archive for your platform and extract it.

### Linux:
\`\`\`bash
tar xzf phoebus-*-linux.tar.gz
cd phoebus-*
./phoebus.sh
\`\`\`

### Windows:
Extract the .zip file and run \`phoebus.bat\`

### macOS:
Extract the .zip file and run \`phoebus.sh\`
"
fi

# Create the release
gh release create "$VERSION" \
    "${ARTIFACTS[@]}" \
    --title "Phoebus $VERSION" \
    --notes "$NOTES"

# Step 6: Show result
echo ""
echo "========================================="
echo "✅ Release created successfully!"
echo "========================================="
echo ""
echo "View release at:"
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
echo "https://github.com/$REPO/releases/tag/$VERSION"
echo ""
echo "Download URLs:"
for artifact in "${ARTIFACTS[@]}"; do
    FILENAME=$(basename "$artifact")
    echo "  https://github.com/$REPO/releases/download/$VERSION/$FILENAME"
done
echo ""
echo "To use in Ansible, set:"
echo "  phoebus_version: $VERSION"
echo "  phoebus_url: https://github.com/$REPO/releases/download/$VERSION/phoebus-*-linux.tar.gz"
