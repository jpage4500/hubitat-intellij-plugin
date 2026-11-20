#!/bin/bash
# Builds the distributable plugin zip (what gets uploaded to JetBrains Marketplace / installed from disk)
# and moves it to ~/Downloads.
set -e

cd "$(dirname "$0")/.."

# remove previous builds so only the new zip is picked up
echo "removing existing plugin zips"
rm -f build/distributions/*.zip

./gradlew buildPlugin

ZIP=$(ls build/distributions/hubitat-intellij-plugin-*.zip)
mv "$ZIP" ~/Downloads/
echo ""
echo "plugin zip: $HOME/Downloads/$(basename "$ZIP")"
