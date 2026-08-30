#!/bin/sh
# Write a Maven settings.xml that mirrors central to $1. Used by the Docker build,
# both for an explicitly requested mirror and for the fallback after Central 429s.
set -eu
mkdir -p "${HOME:-/root}/.m2"
cat > "${HOME:-/root}/.m2/settings.xml" <<EOF
<settings>
  <mirrors>
    <mirror>
      <id>mend-mirror</id>
      <mirrorOf>central</mirrorOf>
      <url>$1</url>
    </mirror>
  </mirrors>
</settings>
EOF
