#!/bin/sh
#
# Stamps the native fingerprint into the built Info.plist.
#
# Add as a Run Script build phase in Xcode, AFTER "Copy Bundle Resources":
#
#     "$SRCROOT/../node_modules/react-native-ota-sdk/scripts/ota-fingerprint-ios.sh"
#
# It writes into the *built* Info.plist under TARGET_BUILD_DIR, not the source
# file in your repo. That is deliberate: the fingerprint hashes the ios/
# directory, so writing the value into a hashed file would feed its own hash
# back in and never settle. Your source Info.plist stays untouched.
#
# The same `ota fingerprint` runs at publish time over the same source tree, so
# the binary and the bundle agree by construction.

set -e

PROJECT_ROOT="$SRCROOT/.."
PLIST="$TARGET_BUILD_DIR/$INFOPLIST_PATH"

if [ ! -f "$PLIST" ]; then
  echo "error: built Info.plist not found at $PLIST — this phase must run after Copy Bundle Resources"
  exit 1
fi

# Xcode's build environment does not inherit a login shell, so node installed
# via nvm/homebrew is often absent from PATH. Cover the usual locations.
export PATH="$PATH:/usr/local/bin:/opt/homebrew/bin:$HOME/.nvm/versions/node/$(ls "$HOME/.nvm/versions/node" 2>/dev/null | tail -1)/bin"

FINGERPRINT=$(cd "$PROJECT_ROOT" && npx ota fingerprint --project "$PROJECT_ROOT" 2>/dev/null | tr -d '[:space:]')

case "$FINGERPRINT" in
  [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
  *)
    # Failing the build is deliberate. Falling back to a default would produce a
    # binary declaring a runtime nothing publishes to, so the app would simply
    # never receive updates, with nothing anywhere to explain why.
    echo "error: ota fingerprint did not return a valid hash (got: '$FINGERPRINT')"
    echo "note: check that node and the ota CLI are runnable from $PROJECT_ROOT"
    exit 1
    ;;
esac

/usr/libexec/PlistBuddy -c "Delete :OTARuntimeVersion" "$PLIST" 2>/dev/null || true
/usr/libexec/PlistBuddy -c "Add :OTARuntimeVersion string $FINGERPRINT" "$PLIST"

echo "OTA runtime fingerprint: $FINGERPRINT"
