#!/usr/bin/env bash
set -euo pipefail

# Builds a disposable, renderless 26.1.2 protocol peer from immutable source
# commits. Run this only in a secret-free preparation job. Runtime A/B jobs
# consume the resulting directory and never run npm or third-party install
# scripts.

NMP_REPOSITORY="https://github.com/mneuhaus/node-minecraft-protocol.git"
NMP_COMMIT="3fb78a8da17cbce774a6cf8d78dfd889f1fbb8bf"
WRAPPER_REPOSITORY="https://github.com/mneuhaus/node-minecraft-data.git"
WRAPPER_COMMIT="5261c21830dbf692a3c9655506aea07642afdd83"
DATA_REPOSITORY="https://github.com/PrismarineJS/minecraft-data.git"
DATA_COMMIT="bc0c5957ff925778660f253f9d1ea51c06d14087"

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <output-directory>" >&2
  exit 64
fi

output_directory="$(realpath -m "$1")"
if [[ -e "$output_directory" ]]; then
  echo "Output already exists: $output_directory" >&2
  exit 73
fi

work_directory="$(mktemp -d -t iv-phase2-client.XXXXXXXX)"
trap 'rm -rf -- "$work_directory"' EXIT

clone_exact() {
  local repository="$1"
  local commit="$2"
  local destination="$3"

  git init --quiet "$destination"
  git -C "$destination" remote add origin "$repository"
  git -C "$destination" -c protocol.version=2 fetch --quiet --depth=1 origin "$commit"
  git -C "$destination" checkout --quiet --detach FETCH_HEAD
  local actual
  actual="$(git -C "$destination" rev-parse HEAD)"
  if [[ "$actual" != "$commit" ]]; then
    echo "Expected $repository at $commit, found $actual" >&2
    exit 65
  fi
}

nmp_directory="$work_directory/node-minecraft-protocol"
wrapper_directory="$work_directory/node-minecraft-data"
clone_exact "$NMP_REPOSITORY" "$NMP_COMMIT" "$nmp_directory"
clone_exact "$WRAPPER_REPOSITORY" "$WRAPPER_COMMIT" "$wrapper_directory"

expected_gitlink="$(git -C "$wrapper_directory" ls-tree "$WRAPPER_COMMIT" minecraft-data | awk '{print $3}')"
if [[ "$expected_gitlink" != "$DATA_COMMIT" ]]; then
  echo "Wrapper gitlink drifted: expected $DATA_COMMIT, found $expected_gitlink" >&2
  exit 65
fi
rm -rf -- "$wrapper_directory/minecraft-data"
clone_exact "$DATA_REPOSITORY" "$DATA_COMMIT" "$wrapper_directory/minecraft-data"

# This pinned generator only creates data.js from the pinned JSON tree. It has
# no registry dependencies and is the sole third-party prepare code executed.
(
  cd "$wrapper_directory"
  node bin/generate_data.js
)
test -s "$wrapper_directory/data.js"

# Replace the PR's floating GitHub branch dependency with the already prepared
# sibling directory. Registry dependencies are installed without lifecycle
# scripts; package-lock.json captures every resolved URL and integrity value.
(
  cd "$nmp_directory"
  node <<'NODE'
const fs = require('fs')
const packageJson = JSON.parse(fs.readFileSync('package.json', 'utf8'))
packageJson.dependencies['minecraft-data'] = 'file:../node-minecraft-data'
fs.writeFileSync('package.json', `${JSON.stringify(packageJson, null, 2)}\n`, 'utf8')
NODE
  npm install --omit=dev --ignore-scripts --package-lock=true --no-audit --no-fund
  # npm ls recursively validates dependencies declared by the linked data
  # source package, including its generator/test toolchain. Those packages are
  # intentionally absent from this runtime-only install, so derive a stable
  # production inventory from the authoritative lockfile instead.
  node <<'NODE'
const fs = require('fs')
const lock = JSON.parse(fs.readFileSync('package-lock.json', 'utf8'))
if (lock.lockfileVersion !== 3 || lock.packages == null) {
  throw new Error(`Unsupported package-lock schema: ${lock.lockfileVersion}`)
}
const compareText = (a, b) => a < b ? -1 : a > b ? 1 : 0
const sortedObject = value => Object.fromEntries(Object.entries(value || {}).sort(([a], [b]) => compareText(a, b)))
const packages = Object.entries(lock.packages)
  .filter(([, metadata]) => metadata.dev !== true)
  .sort(([a], [b]) => compareText(a, b))
  .map(([location, metadata]) => ({
    location: location || '.',
    name: metadata.name || null,
    version: metadata.version || null,
    resolved: metadata.resolved || null,
    integrity: metadata.integrity || null,
    link: metadata.link === true,
    optional: metadata.optional === true,
    dependencies: sortedObject(metadata.dependencies),
    optionalDependencies: sortedObject(metadata.optionalDependencies),
    peerDependencies: sortedObject(metadata.peerDependencies)
  }))
const inventory = {
  schemaVersion: 1,
  source: 'package-lock.json production entries',
  lockfileVersion: lock.lockfileVersion,
  packageCount: packages.length,
  packages
}
fs.writeFileSync('production-lock-inventory.json', `${JSON.stringify(inventory, null, 2)}\n`, 'utf8')
NODE
)

mkdir -p "$output_directory"
mv "$nmp_directory" "$output_directory/node-minecraft-protocol"
mv "$wrapper_directory" "$output_directory/node-minecraft-data"

lock_path="$output_directory/node-minecraft-protocol/package-lock.json"
inventory_path="$output_directory/node-minecraft-protocol/production-lock-inventory.json"
test -s "$lock_path"
test -s "$inventory_path"

sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

cat > "$output_directory/client-build-manifest.json" <<EOF
{
  "schemaVersion": 1,
  "nodeVersion": "$(node --version)",
  "npmVersion": "$(npm --version)",
  "nodeMinecraftProtocol": {
    "repository": "$NMP_REPOSITORY",
    "commit": "$NMP_COMMIT"
  },
  "nodeMinecraftData": {
    "repository": "$WRAPPER_REPOSITORY",
    "commit": "$WRAPPER_COMMIT"
  },
  "minecraftData": {
    "repository": "$DATA_REPOSITORY",
    "commit": "$DATA_COMMIT"
  },
  "installScriptsEnabled": false,
  "packageLockSha256": "$(sha256_file "$lock_path")",
  "productionLockInventorySha256": "$(sha256_file "$inventory_path")"
}
EOF

PHASE2_MC_PROTOCOL_MODULE="$output_directory/node-minecraft-protocol" node <<'NODE'
const path = require('path')
const protocolRoot = process.env.PHASE2_MC_PROTOCOL_MODULE
const protocol = require(protocolRoot)
if (!protocol.supportedVersions.includes('26.1.2')) {
  throw new Error('node-minecraft-protocol does not advertise 26.1.2')
}
const dataEntry = require.resolve('minecraft-data', { paths: [protocolRoot] })
const data = require(dataEntry)('26.1.2')
if (data == null || data.version == null || data.version.minecraftVersion !== '26.1.2') {
  throw new Error(`minecraft-data did not load 26.1.2 from ${path.dirname(dataEntry)}`)
}
NODE

(
  cd "$output_directory"
  find . -type f ! -name client-files.sha256 ! -path '*/.git/*' -print0 \
    | sort -z \
    | xargs -0 sha256sum \
    > client-files.sha256
)

echo "Prepared protocol client at $output_directory"
