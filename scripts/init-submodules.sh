#!/usr/bin/env bash
# Initialize llama.cpp + hoshidicts (and nested deps) for native builds.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "Initializing top-level submodules..."
git submodule update --init app/src/main/cpp/llama.cpp app/src/main/cpp/hoshidicts

HOSHI="$ROOT/app/src/main/cpp/hoshidicts"
if [[ -d "$HOSHI/.git" || -f "$HOSHI/.git" ]]; then
  echo "Rewriting utf8proc SSH URL to HTTPS (if needed)..."
  (
    cd "$HOSHI"
    # Prefer HTTPS so clone works without GitHub SSH keys.
    git config submodule.external/utf8proc.url https://github.com/JuliaStrings/utf8proc.git || true
    if [[ -f .gitmodules ]] && grep -q 'git@github.com:JuliaStrings/utf8proc.git' .gitmodules; then
      sed -i.bak 's|git@github.com:JuliaStrings/utf8proc.git|https://github.com/JuliaStrings/utf8proc.git|' .gitmodules || true
      rm -f .gitmodules.bak
      git submodule sync external/utf8proc || true
    fi
    echo "Initializing nested hoshidicts submodules..."
    git submodule update --init --recursive
  )
fi

echo "Done. Verify:"
test -f app/src/main/cpp/llama.cpp/CMakeLists.txt && echo "  llama.cpp: OK" || echo "  llama.cpp: MISSING"
test -f app/src/main/cpp/hoshidicts/CMakeLists.txt && echo "  hoshidicts: OK" || echo "  hoshidicts: MISSING"
