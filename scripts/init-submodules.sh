#!/usr/bin/env bash
# Initialize llama.cpp + hoshidicts (and nested deps) for native builds.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .gitmodules ]]; then
  echo "error: no .gitmodules in $ROOT — is this the Yomidroid repo root?" >&2
  exit 1
fi

echo "==> Initializing top-level submodules (llama.cpp, hoshidicts)..."
git submodule sync --recursive || true
git submodule update --init app/src/main/cpp/llama.cpp app/src/main/cpp/hoshidicts

HOSHI="$ROOT/app/src/main/cpp/hoshidicts"
if [[ -d "$HOSHI/.git" || -f "$HOSHI/.git" ]]; then
  echo "==> Rewriting nested utf8proc SSH URL to HTTPS (avoids needing GitHub SSH keys)..."
  (
    cd "$HOSHI"
    git config submodule.external/utf8proc.url https://github.com/JuliaStrings/utf8proc.git || true
    if [[ -f .gitmodules ]] && grep -q 'git@github.com:JuliaStrings/utf8proc.git' .gitmodules 2>/dev/null; then
      # Portable in-place edit (GNU and BSD sed)
      if sed --version >/dev/null 2>&1; then
        sed -i 's|git@github.com:JuliaStrings/utf8proc.git|https://github.com/JuliaStrings/utf8proc.git|' .gitmodules
      else
        sed -i.bak 's|git@github.com:JuliaStrings/utf8proc.git|https://github.com/JuliaStrings/utf8proc.git|' .gitmodules
        rm -f .gitmodules.bak
      fi
      git submodule sync external/utf8proc || true
    fi
    echo "==> Initializing nested hoshidicts submodules..."
    git submodule update --init --recursive
  )
else
  echo "warning: hoshidicts submodule metadata missing after update" >&2
fi

echo "==> Verify:"
ok=1
if [[ -f app/src/main/cpp/llama.cpp/CMakeLists.txt ]]; then
  echo "  llama.cpp: OK"
else
  echo "  llama.cpp: MISSING CMakeLists.txt" >&2
  ok=0
fi
if [[ -f app/src/main/cpp/hoshidicts/CMakeLists.txt ]]; then
  echo "  hoshidicts: OK"
else
  echo "  hoshidicts: MISSING CMakeLists.txt" >&2
  ok=0
fi
if [[ "$ok" -ne 1 ]]; then
  echo "" >&2
  echo "Submodule init failed. Try manually:" >&2
  echo "  git submodule update --init --recursive" >&2
  echo "  # then if utf8proc fails:" >&2
  echo "  git config --global url.https://github.com/.insteadOf git@github.com:" >&2
  echo "  git submodule update --init --recursive" >&2
  exit 1
fi
echo "Submodules ready. You can run: ./gradlew assembleDebug"
