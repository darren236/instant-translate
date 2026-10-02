#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
if [[ -z "${ANDROID_HOME:-}" && -d /opt/homebrew/share/android-commandlinetools ]]; then
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$project_dir/work/gradle-home}"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
output_dir="$project_dir/outputs"
version_name="$(sed -n 's/^APP_VERSION_NAME=//p' "$project_dir/android/gradle.properties")"
if [[ -z "$version_name" ]]; then
  echo "APP_VERSION_NAME is missing from android/gradle.properties" >&2
  exit 1
fi
output_apk="$output_dir/InstantTranslate-v${version_name}-debug.apk"

mkdir -p "$output_dir" "$GRADLE_USER_HOME"
"$project_dir/android/gradlew" --no-daemon -p "$project_dir/android" testDebugUnitTest lintDebug assembleDebug
cp "$project_dir/android/build/outputs/apk/debug/InstantTranslate-debug.apk" \
  "$output_apk"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify \
  "$output_apk"

PROJECT_DIR="$project_dir" VERSION_NAME="$version_name" python3 - <<'PY'
import os
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(os.environ['PROJECT_DIR'])
version = os.environ['VERSION_NAME']
archive_path = root / 'outputs' / f'InstantTranslate-v{version}-source.zip'
files = [root / name for name in (
    'README.md', 'CODE_REVIEW.md', '.gitignore', '.gitattributes', 'build-android.sh', 'android/build.gradle',
    'android/settings.gradle', 'android/gradle.properties', 'android/gradlew', 'android/gradlew.bat',
)]
if (root / 'LICENSE').is_file():
    files.append(root / 'LICENSE')
for folder in ('android/src', 'android/gradle/wrapper', '.github', 'docs'):
    files.extend(path for path in (root / folder).rglob('*') if path.is_file() and path.name != '.DS_Store')
with ZipFile(archive_path, 'w', ZIP_DEFLATED) as archive:
    for path in sorted(files):
        archive.write(path, path.relative_to(root))
with ZipFile(archive_path) as archive:
    damaged = archive.testzip()
    if damaged:
        raise RuntimeError(f'Damaged source archive entry: {damaged}')
print(f'Built {archive_path}')
PY

echo "Built $output_apk"
