#!/usr/bin/env bash
# =============================================================================
# APK Verification Script
# Performs comprehensive validation of Android APK files
# =============================================================================
#
# Usage:
#   ./verify-apk.sh <path-to-apk> [--verbose] [--strict]
#
# Exit codes:
#   0 = All checks passed
#   1 = Critical errors found
#   2 = Warnings found (only with --strict)
#
# =============================================================================

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# State
VERBOSE=false
STRICT=false
ERRORS=0
WARNINGS=0
APK_FILE=""
REPORT_FILE=""
TEMP_DIR=""

# ============================================================================
# Helpers
# ============================================================================

log_info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_pass()  { echo -e "${GREEN}[PASS]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; WARNINGS=$((WARNINGS + 1)); }
log_fail()  { echo -e "${RED}[FAIL]${NC}  $*"; ERRORS=$((ERRORS + 1)); }
log_debug() { [[ "$VERBOSE" == "true" ]] && echo -e "${CYAN}[DEBUG]${NC} $*"; }
log_section() { echo ""; echo -e "${CYAN}━━━ $* ━━━${NC}"; }

# Helper function to format bytes as MB with 2 decimal places (pure bash)
bytes_to_mb() {
  local bytes=$1
  local whole=$((bytes / 1048576))
  local frac=$(( (bytes % 1048576) * 100 / 1048576 ))
  printf "%d.%02d" "$whole" "$frac"
}

# Helper function to calculate percentage with 1 decimal place (pure bash)
calc_pct() {
  local part=$1
  local total=$2
  local whole=$((part * 100 / total))
  local frac=$(( (part * 1000 / total) % 10 ))
  printf "%d.%d" "$whole" "$frac"
}

cleanup() {
  if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
    rm -rf "$TEMP_DIR"
  fi
}
trap cleanup EXIT

usage() {
  echo "Usage: $0 <path-to-apk> [--verbose] [--strict]"
  echo ""
  echo "Options:"
  echo "  --verbose    Show detailed output for all checks"
  echo "  --strict     Fail on warnings too (default: warn only)"
  echo ""
  echo "Examples:"
  echo "  $0 app-debug.apk"
  echo "  $0 app-release.apk --verbose --strict"
  exit 1
}

# ============================================================================
# Argument Parsing
# =============================================================================

[[ $# -lt 1 ]] && usage

for arg in "$@"; do
  case $arg in
    --verbose) VERBOSE=true ;;
    --strict)  STRICT=true ;;
    --help|-h) usage ;;
    -*)        echo "Unknown option: $arg"; usage ;;
    *)
      if [[ -z "$APK_FILE" ]]; then
        APK_FILE="$arg"
      else
        echo "Multiple APK files specified"
        exit 1
      fi
      ;;
  esac
done

[[ -z "$APK_FILE" ]] && { echo "No APK file specified"; usage; }
[[ -f "$APK_FILE" ]] || { echo "APK file not found: $APK_FILE"; exit 1; }

TEMP_DIR=$(mktemp -d)
REPORT_FILE="${APK_FILE%.apk}-verification-report.md"

echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║           S Engine 2D — APK Verification                  ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
log_info "APK file: $(realpath "$APK_FILE")"
log_info "APK size: $(du -h "$APK_FILE" | cut -f1)"

# ============================================================================
# 1. Basic File Checks
# ============================================================================

log_section "1. Basic File Validation"

# Check file exists and is non-empty
FILE_SIZE=$(stat -c%s "$APK_FILE" 2>/dev/null || stat -f%z "$APK_FILE")
if [[ "$FILE_SIZE" -lt 1000 ]]; then
  log_fail "APK file is suspiciously small ($FILE_SIZE bytes)"
else
  log_pass "APK file size is reasonable ($FILE_SIZE bytes)"
fi

# Check it's actually a ZIP file
FILE_MAGIC=$(od -A n -t x1 -N 4 "$APK_FILE" 2>/dev/null | tr -d ' \n')
if [[ "$FILE_MAGIC" == "504b0304" ]]; then
  log_pass "File has valid ZIP magic bytes (PK header)"
else
  log_fail "File is not a valid ZIP archive (magic: $FILE_MAGIC)"
fi

# Check file extension
if [[ "$APK_FILE" == *.apk ]]; then
  log_pass "File has .apk extension"
else
  log_warn "File does not have .apk extension"
fi

# Verify ZIP integrity
if unzip -t "$APK_FILE" > /dev/null 2>&1; then
  log_pass "ZIP archive integrity check passed"
else
  log_fail "ZIP archive is corrupted"
fi

# ============================================================================
# 2. APK Structure Checks
# ============================================================================

log_section "2. APK Structure Validation"

# Extract contents listing
unzip -l "$APK_FILE" > "$TEMP_DIR/apk-contents.txt" 2>/dev/null

# Check required files
check_required_file() {
  local file="$1"
  local required="$2"
  if grep -q "$file" "$TEMP_DIR/apk-contents.txt"; then
    log_pass "Required file present: $file"
  elif [[ "$required" == "required" ]]; then
    log_fail "Required file missing: $file"
  else
    log_warn "Optional file missing: $file"
  fi
}

check_required_file "AndroidManifest.xml" "required"
check_required_file "classes.dex" "required"
check_required_file "resources.arsc" "optional"
check_required_file "res/" "optional"
check_required_file "META-INF/CERT.RSA" "optional"
check_required_file "META-INF/MANIFEST.MF" "optional"

# Count DEX files
set +e  # Temporarily disable exit on error
DEX_COUNT=$(grep "\.dex$" "$TEMP_DIR/apk-contents.txt" 2>/dev/null | wc -l || echo "0")
set -e  # Re-enable exit on error
DEX_COUNT=$(echo "$DEX_COUNT" | tr -d '[:space:]' || echo "0")
DEX_COUNT=${DEX_COUNT:-0}
if [[ "$DEX_COUNT" -gt 0 ]]; then
  log_pass "Found $DEX_COUNT DEX file(s)"
else
  log_fail "No DEX files found"
fi

# Count native libraries
set +e  # Temporarily disable exit on error
SO_COUNT=$(grep "\.so$" "$TEMP_DIR/apk-contents.txt" 2>/dev/null | wc -l || echo "0")
set -e  # Re-enable exit on error
SO_COUNT=$(echo "$SO_COUNT" | tr -d '[:space:]' || echo "0")
SO_COUNT=${SO_COUNT:-0}

if [[ "$VERBOSE" == "true" ]]; then
  echo -e "${CYAN}[DEBUG]${NC} Found $SO_COUNT native library file(s)"
fi

if [[ "$SO_COUNT" -gt 0 ]]; then
  log_pass "Native libraries present ($SO_COUNT files)"
  # Check for ABI splits
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    if grep -q "lib/$abi/" "$TEMP_DIR/apk-contents.txt" 2>/dev/null; then
      if [[ "$VERBOSE" == "true" ]]; then
        echo -e "${CYAN}[DEBUG]${NC}   Supports ABI: $abi"
      fi
    fi
  done
else
  log_info "No native libraries found"
fi

# Count resources
set +e  # Temporarily disable exit on error
RES_COUNT=$(grep "^.*res/" "$TEMP_DIR/apk-contents.txt" 2>/dev/null | wc -l || echo "0")
set -e  # Re-enable exit on error
RES_COUNT=$(echo "$RES_COUNT" | tr -d '[:space:]' || echo "0")
RES_COUNT=${RES_COUNT:-0}
if [[ "$VERBOSE" == "true" ]]; then
  echo -e "${CYAN}[DEBUG]${NC} Found $RES_COUNT resource entries"
fi

# Count assets
set +e  # Temporarily disable exit on error
ASSET_COUNT=$(grep "^.*assets/" "$TEMP_DIR/apk-contents.txt" 2>/dev/null | wc -l || echo "0")
set -e  # Re-enable exit on error
ASSET_COUNT=$(echo "$ASSET_COUNT" | tr -d '[:space:]' || echo "0")
ASSET_COUNT=${ASSET_COUNT:-0}
if [[ "$VERBOSE" == "true" ]]; then
  echo -e "${CYAN}[DEBUG]${NC} Found $ASSET_COUNT asset entries"
fi

# ============================================================================
# 3. Manifest Validation
# ============================================================================

log_section "3. Android Manifest Validation"

# Extract manifest
cd "$TEMP_DIR"
unzip -q "$OLDPWD/$APK_FILE" AndroidManifest.xml 2>/dev/null || true

# Try to use aapt/apkanalyzer if available
MANIFEST_PARSED=false

# Find Android SDK tools
find_aapt() {
  local ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  
  if [[ -z "$ANDROID_HOME" ]]; then
    # Try common locations
    for dir in "$HOME/Android/Sdk" "/opt/android-sdk" "/usr/local/android-sdk" "$HOME/Library/Android/sdk"; do
      if [[ -d "${dir:-}" ]]; then
        ANDROID_HOME="$dir"
        break
      fi
    done
  fi
  
  # Find aapt or aapt2
  if [[ -n "$ANDROID_HOME" ]]; then
    for tool in \
      "$(find "$ANDROID_HOME/build-tools" -name "aapt" -type f 2>/dev/null | sort -V | tail -1)" \
      "$(find "$ANDROID_HOME/build-tools" -name "aapt2" -type f 2>/dev/null | sort -V | tail -1)"; do
      if [[ -n "$tool" && -x "$tool" ]]; then
        echo "$tool"
        return
      fi
    done
  fi
  
  # Fallback to system aapt
  command -v aapt 2>/dev/null && return
  command -v aapt2 2>/dev/null && return
  
  echo ""
}

AAPT=$(find_aapt)

if [[ -n "$AAPT" ]]; then
  log_info "Using Android tool: $(basename "$AAPT")"
  
  case "$(basename "$AAPT")" in
    aapt)
      "$AAPT" dump badging "$OLDPWD/$APK_FILE" > "$TEMP_DIR/badging.txt" 2>/dev/null || true
      MANIFEST_PARSED=true
      ;;
    aapt2)
      "$AAPT" dump badging "$OLDPWD/$APK_FILE" > "$TEMP_DIR/badging.txt" 2>/dev/null || true
      MANIFEST_PARSED=true
      ;;
  esac
  
  if [[ -f "$TEMP_DIR/badging.txt" ]]; then
    # Package name
    PKG_NAME=$(grep "^package:" "$TEMP_DIR/badging.txt" | sed "s/.*name='\([^']*\)'.*/\1/" || echo "")
    if [[ -n "$PKG_NAME" ]]; then
      log_pass "Package name: $PKG_NAME"
      if [[ "$PKG_NAME" == *"sengine"* ]] || [[ "$PKG_NAME" == *"SEngine"* ]] || [[ "$PKG_NAME" == *"S Engine"* ]]; then
        log_debug "Package name matches S Engine"
      fi
    else
      log_fail "Could not extract package name"
    fi
    
    # Version
    VERSION_NAME=$(grep "versionName" "$TEMP_DIR/badging.txt" | head -1 | sed "s/.*versionName='\([^']*\)'.*/\1/" || echo "unknown")
    VERSION_CODE=$(grep "versionCode" "$TEMP_DIR/badging.txt" | head -1 | sed "s/.*versionCode='\([^']*\)'.*/\1/" || echo "unknown")
    log_pass "Version: $VERSION_NAME (code: $VERSION_CODE)"
    
    # SDK levels
    MIN_SDK=$(grep "sdkVersion" "$TEMP_DIR/badging.txt" | head -1 | sed "s/sdkVersion:'\([^']*\)'.*/\1/" || echo "unknown")
    TARGET_SDK=$(grep "targetSdkVersion" "$TEMP_DIR/badging.txt" | head -1 | sed "s/targetSdkVersion:'\([^']*\)'.*/\1/" || echo "unknown")
    log_pass "Min SDK: $MIN_SDK (Android $(sdk_to_version "$MIN_SDK" 2>/dev/null || echo "?"))"
    log_pass "Target SDK: $TARGET_SDK"
    
    # Verify minSdk >= 26 (Android 8.0)
    if [[ "$MIN_SDK" =~ ^[0-9]+$ ]] && [[ "$MIN_SDK" -lt 26 ]]; then
      log_warn "Min SDK $MIN_SDK is below 26 (Android 8.0). Engine requires API 26+."
    fi
    
    # Application label
    APP_LABEL=$(grep "application-label:" "$TEMP_DIR/badging.txt" | head -1 | sed "s/application-label:'\([^']*\)'.*/\1/" || echo "")
    if [[ -n "$APP_LABEL" ]]; then
      log_pass "Application label: $APP_LABEL"
    else
      log_warn "No application label found"
    fi
    
    # Launchable activities
    LAUNCHABLE=$(grep "launchable-activity" "$TEMP_DIR/badging.txt" | sed "s/.*name='\([^']*\)'.*/\1/" || echo "")
    if [[ -n "$LAUNCHABLE" ]]; then
      log_pass "Launchable activities found:"
      echo "$LAUNCHABLE" | while read -r activity; do
        log_debug "  → $activity"
      done
    else
      log_fail "No launchable activity found"
    fi
    
    # Permissions
    PERMS=$(grep "uses-permission" "$TEMP_DIR/badging.txt" | sed "s/.*name='\([^']*\)'.*/\1/" || echo "")
    PERM_COUNT=$(echo "$PERMS" | grep -c "." || echo "0")
    if [[ "$PERM_COUNT" -gt 0 ]]; then
      log_pass "Permissions declared: $PERM_COUNT"
      echo "$PERMS" | while read -r perm; do
        log_debug "  → $perm"
      done
    else
      log_debug "No permissions declared"
    fi
    
    # Features
    FEATURES=$(grep "uses-feature" "$TEMP_DIR/badging.txt" | sed "s/.*name='\([^']*\)'.*/\1/" || echo "")
    FEATURE_COUNT=$(echo "$FEATURES" | grep -c "." || echo "0")
    log_debug "Features declared: $FEATURE_COUNT"
    
    # Supported screens
    SCREEN_DENSITIES=$(grep "densities" "$TEMP_DIR/badging.txt" | sed "s/densities: '\([^']*\)'.*/\1/" || echo "")
    if [[ -n "$SCREEN_DENSITIES" ]]; then
      log_debug "Screen densities: $SCREEN_DENSITIES"
    fi
    
    # Supported ABIs
    NATIVE_CODE=$(grep "native-code" "$TEMP_DIR/badging.txt" | sed "s/native-code: '\([^']*\)'.*/\1/" || echo "")
    if [[ -n "$NATIVE_CODE" ]]; then
      log_pass "Native code ABIs: $NATIVE_CODE"
    fi
    
  else
    log_warn "Could not parse badging information"
  fi
else
  log_warn "Android build tools not found — skipping manifest analysis"
  log_info "To enable full verification, install Android SDK build-tools"
fi

# ============================================================================
# 4. Signature Verification
# ============================================================================

log_section "4. Signature Verification"

# Check for signing metadata
if grep -q "META-INF/CERT.RSA" "$TEMP_DIR/apk-contents.txt"; then
  log_pass "APK contains signing certificate (META-INF/CERT.RSA)"
  
  # Extract and examine certificate
  cd "$TEMP_DIR"
  unzip -q "$OLDPWD/$APK_FILE" META-INF/CERT.RSA META-INF/MANIFEST.MF 2>/dev/null || true
  
  if [[ -f "META-INF/CERT.RSA" ]]; then
    # Use keytool if available
    if command -v keytool &>/dev/null; then
      keytool -printcert -file META-INF/CERT.RSA > cert-info.txt 2>/dev/null || true
      if [[ -f cert-info.txt ]]; then
        CERT_OWNER=$(grep "Owner:" cert-info.txt | head -1 || echo "Unknown")
        CERT_ISSUER=$(grep "Issuer:" cert-info.txt | head -1 || echo "Unknown")
        CERT_VALID=$(grep "Valid from" cert-info.txt | head -1 || echo "Unknown")
        log_debug "Certificate Owner: $CERT_OWNER"
        log_debug "Certificate Issuer: $CERT_ISSUER"
        log_debug "Certificate Valid: $CERT_VALID"
        
        # Check if it's a debug certificate
        if echo "$CERT_OWNER" | grep -qi "Android Debug\|debug\|CN=Android Debug"; then
          log_info "APK is signed with a DEBUG certificate"
        else
          log_pass "APK appears to be signed with a release/custom certificate"
        fi
      fi
    else
      log_debug "keytool not available — skipping certificate details"
    fi
  fi
else
  log_warn "No signing certificate found (META-INF/CERT.RSA missing)"
fi

# Try apksigner verification
if command -v apksigner &>/dev/null; then
  log_info "Verifying with apksigner..."
  if apksigner verify --verbose "$APK_FILE" 2>&1; then
    log_pass "apksigner: APK signature is valid"
  else
    log_fail "apksigner: APK signature verification failed"
  fi
elif command -v jarsigner &>/dev/null; then
  log_info "Verifying with jarsigner..."
  if jarsigner -verify -verbose "$APK_FILE" 2>&1 | head -5; then
    log_pass "jarsigner: APK signature appears valid"
  else
    log_warn "jarsigner: Could not verify signature"
  fi
else
  log_warn "No signature verification tool found (apksigner/jarsigner)"
  log_info "For full verification, install Android SDK build-tools or JDK"
fi

# ============================================================================
# 5. Size Analysis
# ============================================================================

log_section "5. Size Analysis"

SIZE_BYTES=$(stat -c%s "$APK_FILE" 2>/dev/null || stat -f%z "$APK_FILE")
SIZE_KB=$(( SIZE_BYTES / 1024 ))
SIZE_MB=$(bytes_to_mb "$SIZE_BYTES")

echo "  Total APK size: ${SIZE_MB} MB (${SIZE_BYTES} bytes)"
echo ""

# Size warnings
if [[ $SIZE_BYTES -gt 104857600 ]]; then
  log_fail "APK exceeds 100MB — too large for most distribution channels"
elif [[ $SIZE_BYTES -gt 52428800 ]]; then
  log_warn "APK exceeds 50MB — consider optimizing"
elif [[ $SIZE_BYTES -gt 20971520 ]]; then
  log_info "APK size is acceptable (< 20MB)"
else
  log_pass "APK size is excellent (< 20MB)"
fi

# Analyze size breakdown
log_info "Size breakdown by content type:"
cd "$TEMP_DIR"

extract_and_summarize() {
  local dir="$1"
  local label="$2"
  local total=0
  if [[ -d "$dir" ]]; then
    total=$(find "$dir" -type f -exec du -cb {} + 2>/dev/null | tail -1 | cut -f1 || echo "0")
  fi
  if [[ "$total" -gt 0 ]]; then
    local mb=$(bytes_to_mb "$total")
    local pct=$(calc_pct "$total" "$SIZE_BYTES")
    printf "  %-20s %8s MB  (%s%%)\n" "$label" "$mb" "$pct"
  fi
}

# DEX files
DEX_SIZE=$(find . -name "*.dex" -exec du -cb {} + 2>/dev/null | tail -1 | cut -f1 || echo "0")
if [[ "$DEX_SIZE" -gt 0 ]]; then
  DEX_MB=$(bytes_to_mb "$DEX_SIZE")
  DEX_PCT=$(calc_pct "$DEX_SIZE" "$SIZE_BYTES")
  printf "  %-20s %8s MB  (%s%%)\n" "DEX (code)" "$DEX_MB" "$DEX_PCT"
fi

# Native libraries
SO_SIZE=$(find . -name "*.so" -exec du -cb {} + 2>/dev/null | tail -1 | cut -f1 || echo "0")
if [[ "$SO_SIZE" -gt 0 ]]; then
  SO_MB=$(bytes_to_mb "$SO_SIZE")
  SO_PCT=$(calc_pct "$SO_SIZE" "$SIZE_BYTES")
  printf "  %-20s %8s MB  (%s%%)\n" "Native libs" "$SO_MB" "$SO_PCT"
fi

# Resources
RES_SIZE=$(find res/ -type f -exec du -cb {} + 2>/dev/null | tail -1 | cut -f1 || echo "0")
if [[ "$RES_SIZE" -gt 0 ]]; then
  RES_MB=$(bytes_to_mb "$RES_SIZE")
  RES_PCT=$(calc_pct "$RES_SIZE" "$SIZE_BYTES")
  printf "  %-20s %8s MB  (%s%%)\n" "Resources" "$RES_MB" "$RES_PCT"
fi

# Assets
ASSET_SIZE=$(find assets/ -type f -exec du -cb {} + 2>/dev/null | tail -1 | cut -f1 || echo "0")
if [[ "$ASSET_SIZE" -gt 0 ]]; then
  ASSET_MB=$(bytes_to_mb "$ASSET_SIZE")
  ASSET_PCT=$(calc_pct "$ASSET_SIZE" "$SIZE_BYTES")
  printf "  %-20s %8s MB  (%s%%)\n" "Assets" "$ASSET_MB" "$ASSET_PCT"
fi

cd "$OLDPWD"

# ============================================================================
# 6. Content Validation
# ============================================================================

log_section "6. Content Validation"

# Check for common issues
cd "$TEMP_DIR"
unzip -q "$OLDPWD/$APK_FILE" 2>/dev/null || true

# Check for uncompressed resources that should be compressed
for ext in jpg jpeg png gif webp; do
  LARGE_IMG=$(find . -name "*.$ext" -size +1M 2>/dev/null | head -1)
  if [[ -n "$LARGE_IMG" ]]; then
    log_warn "Large image file (>1MB): $(basename "$LARGE_IMG") — consider optimizing"
  fi
done

# Check for potential debug artifacts
if find . -name "*.log" -o -name "*.tmp" 2>/dev/null | grep -q "."; then
  log_warn "Found temporary/log files in APK — should be excluded"
fi

# Check resources.arsc
if [[ -f "resources.arsc" ]]; then
  RESC_SIZE=$(stat -c%s "resources.arsc" 2>/dev/null || stat -f%z "resources.arsc")
  RESC_MB=$(bytes_to_mb "$RESC_SIZE")
  log_pass "resources.arsc present ($RESC_MB MB)"
  
  if [[ $RESC_SIZE -gt 5242880 ]]; then
    log_warn "resources.arsc is large (>5MB) — may impact install time"
  fi
fi

cd "$OLDPWD"

# ============================================================================
# 7. Generate Report
# ============================================================================

log_section "7. Verification Report"

cat > "$REPORT_FILE" << EOF
# APK Verification Report

**File:** $(basename "$APK_FILE")
**Path:** $(realpath "$APK_FILE")
**Size:** ${SIZE_MB} MB (${SIZE_BYTES} bytes)
**Date:** $(date -u '+%Y-%m-%d %H:%M:%S UTC')

## Results Summary

| Category | Status |
|----------|--------|
| File Validation | $([ $ERRORS -eq 0 ] && echo "✅ PASS" || echo "❌ FAIL") |
| Structure | $([ $ERRORS -eq 0 ] && echo "✅ PASS" || echo "❌ FAIL") |
| Manifest | $([ $ERRORS -eq 0 ] && echo "✅ PASS" || echo "❌ FAIL") |
| Signature | $([ $WARNINGS -eq 0 ] && echo "✅ PASS" || echo "⚠️ WARN") |
| Size | $([ $SIZE_BYTES -lt 52428800 ] && echo "✅ PASS" || echo "⚠️ WARN") |
| Content | $([ $WARNINGS -eq 0 ] && echo "✅ PASS" || echo "⚠️ WARN") |

## Overall

- **Errors:** $ERRORS
- **Warnings:** $WARNINGS
- **Verdict:** $([ $ERRORS -eq 0 ] && ([ $WARNINGS -eq 0 ] && echo "✅ ALL CHECKS PASSED" || echo "⚠️ PASSED WITH WARNINGS") || echo "❌ FAILED")

## Details

- Package: ${PKG_NAME:-unknown}
- Version: ${VERSION_NAME:-unknown} (${VERSION_CODE:-unknown})
- Min SDK: ${MIN_SDK:-unknown}
- Target SDK: ${TARGET_SDK:-unknown}
- DEX files: $DEX_COUNT
- Native libraries: $SO_COUNT
EOF

echo ""
echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║                     VERIFICATION COMPLETE                  ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo "  Errors:   $ERRORS"
echo "  Warnings: $WARNINGS"
echo ""

if [[ $ERRORS -eq 0 ]]; then
  if [[ $WARNINGS -eq 0 ]]; then
    echo -e "  ${GREEN}✅ ALL CHECKS PASSED — APK is valid and ready for installation${NC}"
  else
    echo -e "  ${YELLOW}⚠️  PASSED WITH WARNINGS — APK is usable but review warnings${NC}"
  fi
else
  echo -e "  ${RED}❌ FAILED — APK has critical issues that must be resolved${NC}"
fi

echo ""
echo "  Report saved to: $REPORT_FILE"
echo ""

# Final exit code
if [[ $ERRORS -gt 0 ]]; then
  exit 1
elif [[ $STRICT == "true" && $WARNINGS -gt 0 ]]; then
  exit 2
else
  exit 0
fi
