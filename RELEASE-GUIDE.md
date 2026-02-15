# Guide: Creating Phoebus Releases

## Option 1: Automatic Release via GitHub Actions (Recommended)

### Setup
1. The workflow `.github/workflows/build-release.yml` is configured
2. It triggers on git tags matching `v*.*.*` pattern

### Usage - Create a release:
```bash
# Tag your commit
git tag v5.0.3-INFN
git push origin v5.0.3-INFN

# GitHub Actions will automatically:
# - Build for Linux, Windows, macOS
# - Create a GitHub Release
# - Upload all artifacts to the release
```

### Download URL format:
```
https://github.com/infn-epics/phoebus/releases/download/v5.0.3-INFN/phoebus-5.0.3-SNAPSHOT-linux.tar.gz
```

---

## Option 2: Manual Release Creation

### Method A: Using GitHub CLI (`gh`)

```bash
# 1. Build locally
mvn clean install -DskipTests

# 2. Create a release with artifacts
VERSION="v5.0.3-INFN"
gh release create "$VERSION" \
  --title "Phoebus $VERSION" \
  --notes "Release notes here" \
  phoebus-product/target/*.tar.gz \
  phoebus-product/target/*.zip
```

### Method B: Using GitHub Web Interface

1. Go to: https://github.com/infn-epics/phoebus/releases/new
2. Click "Choose a tag" → Create new tag (e.g., `v5.0.3-INFN`)
3. Fill in:
   - Release title: `Phoebus v5.0.3-INFN`
   - Description: Your release notes
4. Drag and drop build artifacts:
   - `phoebus-product/target/*.tar.gz` (Linux)
   - `phoebus-product/target/*.zip` (Windows/Mac)
5. Click "Publish release"

### Method C: Manual Download from GitHub Actions

If you just want to get artifacts from a recent build without creating a release:

```bash
# Install GitHub CLI if needed
# brew install gh  # macOS
# apt install gh   # Ubuntu

# List recent workflow runs
gh run list --workflow=build_latest.yml --limit 5

# Download artifacts from a specific run
gh run download <RUN_ID> --name "Phoebus product ubuntu-latest"
```

---

## Option 3: Ansible Download from GitHub Releases

### Update your Ansible role variables:

```yaml
# In configure-consoles.yml
vars:
  phoebus_version: v5.0.3-INFN
  # Use GitHub releases instead of opensource.lnf.infn.it
  phoebus_url: "https://github.com/infn-epics/phoebus/releases/download/{{ phoebus_version }}/phoebus-5.0.3-SNAPSHOT-linux.tar.gz"
```

### Or modify ansible-epics-console-role/tasks/main.yml:

```yaml
- name: Set Phoebus download URL
  set_fact:
    phoebus_download_url: >-
      {{ phoebus_url if phoebus_url | default('') | length > 0
         else 'https://github.com/infn-epics/phoebus/releases/download/'
              + phoebus_version 
              + '/phoebus-' 
              + phoebus_version.replace('v', '') 
              + '-linux.tar.gz' }}
```

---

## Comparison

| Method | Pros | Cons |
|--------|------|------|
| **Auto Release** (tags) | ✅ Automatic, versioned, permanent | Requires git tag |
| **Manual Release** (web/CLI) | ✅ Full control, permanent | Manual steps |
| **Artifacts** (Actions) | ✅ Automatic on every push | ❌ 90-day expiry, needs auth |
| **Binary Server** | ✅ Your infrastructure | Needs SSH setup |

---

## Recommended Workflow

### For Production Releases:
```bash
# 1. Update version in pom.xml if needed
# 2. Commit and tag
git add .
git commit -m "Release v5.0.3-INFN"
git tag v5.0.3-INFN
git push origin master --tags

# 3. Wait for GitHub Actions to build and create release
# 4. Update Ansible to use the new release
```

### For Development/Testing:
- Use the continuous build artifacts (90-day retention)
- Or manually upload to your binary server
- Or use the latest build from `master` branch

---

## Example: Quick Manual Release

```bash
#!/bin/bash
# quick-release.sh

VERSION=${1:-v5.0.3-INFN}

echo "Building Phoebus..."
mvn clean install -DskipTests

echo "Creating GitHub release $VERSION..."
gh release create "$VERSION" \
  --title "Phoebus $VERSION" \
  --notes "Automated release from build" \
  phoebus-product/target/*.tar.gz \
  phoebus-product/target/*.zip

echo "Done! Release available at:"
echo "https://github.com/infn-epics/phoebus/releases/tag/$VERSION"
```

Usage:
```bash
chmod +x quick-release.sh
./quick-release.sh v5.0.3-INFN
```
