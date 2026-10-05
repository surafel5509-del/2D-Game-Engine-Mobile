# APK Verification System

Comprehensive APK build and verification system for S Engine 2D.

## Overview

This system provides automated APK validation through GitHub Actions workflows and a standalone verification script. Every APK built by the CI/CD pipeline is automatically verified for integrity, structure, signature, size, and content.

## Components

### 1. Verification Script (`scripts/verify-apk.sh`)

A robust bash script that performs 7 categories of APK validation:

#### Section 1: Basic File Validation
- ✅ File existence and size checks
- ✅ ZIP magic bytes validation (PK header: `504b0304`)
- ✅ Archive integrity verification (`unzip -t`)
- ✅ Extension validation

#### Section 2: APK Structure Validation
- ✅ Required files check (AndroidManifest.xml, classes.dex, resources.arsc)
- ✅ DEX file counting
- ✅ Native library detection (.so files)
- ✅ ABI support detection (arm64-v8a, armeabi-v7a, x86, x86_64)
- ✅ Resource and asset counting

#### Section 3: Android Manifest Validation
- ✅ Package name extraction
- ✅ Version name and code parsing
- ✅ Min/target SDK validation
- ✅ Application label verification
- ✅ Launchable activities detection
- ✅ Permissions and features enumeration
- ✅ Screen density support check

#### Section 4: Signature Verification
- ✅ Signing certificate detection (META-INF/CERT.RSA)
- ✅ Certificate analysis (owner, issuer, validity)
- ✅ Debug vs release certificate detection
- ✅ Support for `apksigner` and `jarsigner` tools

#### Section 5: Size Analysis
- ✅ Total APK size calculation with warnings
- ✅ Size breakdown by content type (DEX, native libs, resources, assets)
- ✅ Top 10 largest files identification
- ✅ Size limit checks (warns >50MB, fails >100MB)

#### Section 6: Content Validation
- ✅ Large image detection (>1MB)
- ✅ Temporary/log file detection
- ✅ resources.arsc size analysis
- ✅ Debug artifact detection

#### Section 7: Verification Report
- ✅ Markdown report generation
- ✅ Summary table with pass/fail status
- ✅ Error and warning counts
- ✅ Final verdict (PASS/FAIL/WARNINGS)

### 2. GitHub Actions Workflows

#### Build & Verify Debug APK (`build-debug-apk.yml`)
Complete CI/CD workflow that builds and verifies debug APKs.

**Triggers:**
- Push to `main` or `develop` branches
- Pull requests to `main`
- Manual workflow dispatch

**Steps:**
1. Checkout code
2. Setup JDK 17
3. Run unit tests
4. Build debug APK
5. Install Android SDK build-tools
6. Verify APK signature
7. Analyze APK manifest
8. Analyze APK structure
9. Check APK size
10. Validate APK installability
11. Generate build report
12. Upload debug APK artifact
13. Upload analysis reports
14. Comment on PR with download links

#### Verify APK (`verify-apk.yml`)
On-demand verification workflow for existing APKs.

**Triggers:**
- Manual workflow dispatch
- Callable from other workflows

**Input Options:**
- `apk_artifact`: Name of artifact to verify (optional, builds if not provided)
- `strict_mode`: Fail on warnings too (boolean, default: false)
- `verbose`: Show detailed output (boolean, default: true)

#### Android Build (`android.yml`)
Enhanced main build workflow with integrated verification.

## Usage

### Local Verification

#### Basic Usage
```bash
# Verify an APK
./scripts/verify-apk.sh path/to/app.apk

# Verbose output
./scripts/verify-apk.sh app.apk --verbose

# Strict mode (fail on warnings)
./scripts/verify-apk.sh app.apk --strict

# Combined options
./scripts/verify-apk.sh app.apk --verbose --strict
```

#### Exit Codes
- `0` - All checks passed
- `1` - Critical errors found (missing files, invalid signature, etc.)
- `2` - Warnings found (only with `--strict` mode)

#### Example Output
```
╔════════════════════════════════════════════════════════════╗
║           S Engine 2D — APK Verification                  ║
╚════════════════════════════════════════════════════════════╝

[INFO]  APK file: /path/to/app.apk
[INFO]  APK size: 15.2M

━━━ 1. Basic File Validation ━━━
[PASS]  APK file size is reasonable (15933440 bytes)
[PASS]  File has valid ZIP magic bytes (PK header)
[PASS]  File has .apk extension
[PASS]  ZIP archive integrity check passed

━━━ 2. APK Structure Validation ━━━
[PASS]  Required file present: AndroidManifest.xml
[PASS]  Required file present: classes.dex
[PASS]  Required file present: resources.arsc
[PASS]  Found 1 DEX file(s)
[PASS]  Native libraries present (4 files)

━━━ 3. Android Manifest Validation ━━━
[PASS]  Package name: com.sengine.app
[PASS]  Version: 2.0.0 (code: 2)
[PASS]  Min SDK: 26 (Android 8.0)
[PASS]  Target SDK: 34
[PASS]  Application label: S Engine 2D
[PASS]  Launchable activities found
[PASS]  Permissions declared: 2

━━━ 4. Signature Verification ━━━
[PASS]  APK contains signing certificate (META-INF/CERT.RSA)
[PASS]  APK signature is valid

━━━ 5. Size Analysis ━━━
  Total APK size: 15.20 MB (15933440 bytes)
[PASS]  APK size is excellent (< 20MB)
[INFO]  Size breakdown by content type:
  DEX (code)              2.45 MB  (15.4%)
  Native libs             8.12 MB  (51.0%)
  Resources               3.21 MB  (20.2%)
  Assets                  1.42 MB  (8.9%)

━━━ 6. Content Validation ━━━
[PASS]  No large image files found
[PASS]  No temporary files found
[PASS]  resources.arsc present (3.21 MB)

━━━ 7. Verification Report ━━━

╔════════════════════════════════════════════════════════════╗
║                     VERIFICATION COMPLETE                  ║
╚════════════════════════════════════════════════════════════╝

  Errors:   0
  Warnings: 0

  ✅ ALL CHECKS PASSED — APK is valid and ready for installation

  Report saved to: app-verification-report.md
```

### GitHub Actions

#### Automatic Verification
Every pull request automatically triggers the verification workflow. The workflow will:
1. Build the debug APK
2. Run all verification checks
3. Upload the APK and reports as artifacts
4. Comment on the PR with a summary

#### Manual Verification
To manually verify an APK:

1. Go to **Actions** tab
2. Select **Verify APK** workflow
3. Click **Run workflow**
4. Optionally specify:
   - APK artifact name (if already built)
   - Strict mode (fail on warnings)
   - Verbose output

#### Downloading Results
After a workflow run completes:
1. Go to the workflow run page
2. Scroll to **Artifacts** section
3. Download:
   - `debug-apk` - The built APK
   - `apk-verification-report` - Markdown report
   - `build-report` - Build summary

## Verification Checks

### Critical Checks (Fail on Error)
- ❌ Missing AndroidManifest.xml
- ❌ Missing classes.dex
- ❌ Invalid ZIP structure
- ❌ No DEX files
- ❌ APK size > 100MB
- ❌ No launchable activities
- ❌ Invalid signature (when tools available)

### Warning Checks (Warn Only)
- ⚠️ Missing optional files (resources.arsc, res/, META-INF/)
- ⚠️ No native libraries
- ⚠️ APK size > 50MB
- ⚠️ Large image files (>1MB)
- ⚠️ Temporary/log files in APK
- ⚠️ Large resources.arsc (>5MB)
- ⚠️ Verification tools not available

### Informational
- ℹ️ Package name, version, SDK levels
- ℹ️ Permissions and features
- ℹ️ Size breakdown by content type
- ℹ️ Top largest files

## Size Limits

| Size | Status | Action |
|------|--------|--------|
| < 20MB | ✅ Excellent | None |
| 20-50MB | ✅ Acceptable | None |
| 50-100MB | ⚠️ Warning | Consider optimizing |
| > 100MB | ❌ Fail | Must optimize before release |

## Requirements

### For Local Verification
- Bash 4.0+
- Standard Unix tools: `unzip`, `stat`, `od`, `grep`, `wc`, `find`
- Optional: Android SDK build-tools (`aapt`, `apksigner`)
- Optional: JDK (`jarsigner`, `keytool`)

### For GitHub Actions
- No additional requirements (all tools installed by workflow)

## Troubleshooting

### "Android build tools not found"
This is a warning, not an error. The script will skip manifest analysis but still validate structure, signature, and size.

**Solution:** Install Android SDK build-tools:
```bash
sdkmanager --install "build-tools;34.0.0"
```

### "No signature verification tool found"
The script will still check for the presence of signing certificates but cannot verify their validity.

**Solution:** Install JDK or Android SDK build-tools.

### "bc: command not found"
The script no longer requires `bc`. If you see this error, you're using an old version. Update to the latest version.

### Script exits with code 1
Check the output for `[FAIL]` messages. Common causes:
- Missing required files (AndroidManifest.xml, classes.dex)
- Invalid ZIP structure
- APK too large (>100MB)
- No DEX files

### Script exits with code 2 (strict mode)
Review `[WARN]` messages and fix the issues. Common warnings:
- APK size > 50MB
- Large image files
- Missing optional files

## Integration with CI/CD

### Pre-commit Hook (Optional)
Add to `.git/hooks/pre-commit`:
```bash
#!/bin/bash
if [[ -f "app/build/outputs/apk/debug/app-debug.apk" ]]; then
  ./scripts/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk || exit 1
fi
```

### Makefile Integration
```makefile
verify:
	./scripts/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk

verify-strict:
	./scripts/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk --strict
```

## Reports

### Markdown Report Format
The script generates a Markdown report (`*-verification-report.md`) with:
- File information
- Results summary table
- Package details
- Version information
- SDK levels
- Overall verdict

### Example Report
```markdown
# APK Verification Report

**File:** app-debug.apk
**Path:** /path/to/app-debug.apk
**Size:** 15.20 MB (15933440 bytes)
**Date:** 2026-10-05 07:30:00 UTC

## Results Summary

| Category | Status |
|----------|--------|
| File Validation | ✅ PASS |
| Structure | ✅ PASS |
| Manifest | ✅ PASS |
| Signature | ✅ PASS |
| Size | ✅ PASS |
| Content | ✅ PASS |

## Overall

- **Errors:** 0
- **Warnings:** 0
- **Verdict:** ✅ ALL CHECKS PASSED

## Details

- Package: com.sengine.app
- Version: 2.0.0 (2)
- Min SDK: 26
- Target SDK: 34
- DEX files: 1
- Native libraries: 4
```

## Performance

The verification script is optimized for speed:
- Typical verification time: 2-5 seconds
- No external dependencies (pure bash)
- Efficient file scanning
- Minimal I/O operations

## Security

The script:
- ✅ Does not modify the APK
- ✅ Uses read-only operations
- ✅ Cleans up temporary files
- ✅ Validates file integrity
- ✅ Checks signature validity (when tools available)

## Contributing

When adding new verification checks:
1. Add to the appropriate section (1-7)
2. Use `log_pass`, `log_warn`, or `log_fail` functions
3. Update this documentation
4. Test with both valid and invalid APKs
5. Ensure proper exit codes

## License

Part of S Engine 2D project. See main LICENSE file.

## Support

For issues or questions:
1. Check the troubleshooting section above
2. Run with `--verbose` for detailed output
3. Check the generated report
4. Open an issue on GitHub

---

**Version:** 2.0.0  
**Last Updated:** 2026-10-05  
**Maintained by:** S Engine 2D Team
