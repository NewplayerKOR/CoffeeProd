#!/bin/sh
set -eu
umask 022
xml_source=${1:?JUnit XML directory required}
html_source=${2:?HTML report directory required}
target=${3:?Evidence directory required}
if [ -e "$target/xml" ] || [ -e "$target/html" ]; then
  echo 'Report evidence already exists; preserve it and use a new sample' >&2
  exit 2
fi
mkdir -p "$target/xml" "$target/html"
code=0
count=0
# Gradle's binary/ is a private intermediate store, not a portable JUnit report.
for source in "$xml_source"/TEST-*.xml; do
  if [ -f "$source" ] && [ ! -L "$source" ]; then
    destination="$target/xml/${source##*/}"
    cp "$source" "$destination"
    chmod 0644 "$destination"
    count=$((count+1))
  fi
done
if [ "$count" -eq 0 ]; then echo 'JUnit XML report missing' >&2; code=2; fi
if [ -f "$html_source/index.html" ]; then
  if [ -n "$(find "$html_source" -type l -print -quit)" ]; then
    echo 'Symlinks in HTML evidence are forbidden' >&2; code=2
  else
    cp -R "$html_source/." "$target/html/"
  fi
else
  echo 'HTML report missing' >&2; code=2
fi
# Report generators may use 0600 even with umask 022. Normalize only exported reports.
find "$target/xml" "$target/html" -type d -exec chmod 0755 {} +
find "$target/xml" "$target/html" -type f -exec chmod 0644 {} +
exit "$code"
