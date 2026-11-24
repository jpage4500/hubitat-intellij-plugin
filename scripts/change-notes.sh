#!/bin/bash
# Prints HTML change notes (the plugin's "What's New") built from the commit subjects since the previous
# release tag. Used by .github/workflows/release.yml; needs full history + tags (fetch-depth: 0).
#
# The range starts at the newest tag reachable from HEAD^, so re-running the workflow on an already-tagged
# commit still diffs against the release before it.
cd "$(dirname "$0")/.."

PREV=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null)
if [ -n "$PREV" ]; then RANGE="$PREV..HEAD"; else RANGE="-10"; fi

# skip merges and empty/placeholder messages, strip the "- " prefix used in this repo's commit subjects,
# escape HTML, drop duplicates (keeping order)
ITEMS=$(git log --no-merges --format='%s' $RANGE \
  | sed -E 's/^[[:space:]]*-[[:space:]]*//' \
  | grep -viE '^(no message|cleanup|wip)?[[:space:]]*$' \
  | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' \
  | awk '!seen[$0]++ { print "  <li>" $0 "</li>" }')

if [ -z "$ITEMS" ]; then
  ITEMS="  <li>Maintenance release</li>"
fi

echo "<ul>"
echo "$ITEMS"
echo "</ul>"
