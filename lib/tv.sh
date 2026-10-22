# Sourced by every script: the TV's address, from TV_HOST or the git-ignored tv.conf at the repo root.
# Set it once:   echo 'TV_HOST=192.168.1.50' > tv.conf
# (Fire TV: Settings → My Fire TV → About → Network shows the address.)
_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -z "${TV_HOST:-}" ] && [ -f "$_root/tv.conf" ]; then
  # shellcheck disable=SC1091
  . "$_root/tv.conf"
fi
if [ -z "${TV_HOST:-}" ]; then
  echo "Which TV? Save its address once:  echo 'TV_HOST=192.168.1.50' > \"$_root/tv.conf\"" >&2
  echo "(Fire TV: Settings → My Fire TV → About → Network.)" >&2
  exit 1
fi
export TV_HOST
TV="$TV_HOST:5555"
# Ready-made APKs from the latest GitHub release (install scripts' --download).
RELEASES="https://github.com/ameyarya/fire-tv-home-hub/releases/latest/download"
# Builds the APK in the given app folder, or with --download fetches <name>.apk from the release. Prints its path.
apk() {
  local dir="$1" name="$2" mode="${3:-}"
  if [ "$mode" = "--download" ]; then
    local out
    out="$(mktemp -d)/$name.apk"
    curl -fsSL "$RELEASES/$name.apk" -o "$out" || { echo "Couldn't download $name.apk from the latest release." >&2; exit 1; }
    echo "$out"
    return
  fi
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
  export PATH="$JAVA_HOME/bin:$PATH"
  (cd "$_root/$dir" && { [ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties; } \
     && ./gradlew assembleRelease -q --console=plain >&2)
  echo "$_root/$dir/app/build/outputs/apk/release/app-release.apk"
}
