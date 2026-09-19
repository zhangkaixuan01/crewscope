#!/usr/bin/env sh

# Resolve existing ancestors without creating/chmodding anything. Preserve the configured spelling
# in Compose (Git stores absolute paths), but use physical paths for every safety comparison.
physical_path() (
  case "$1" in /*) ;; *) fail "Path must be absolute: $1" ;; esac
  case "$1" in /) printf '/\n'; exit 0 ;; esac
  case "$1/" in */../*|*/./*|*//*) fail "Use an absolute path without dot components or repeated slashes: $1" ;; esac
  if [ -d "$1" ]; then
    CDPATH= cd -P -- "$1" && pwd -P
  elif [ -e "$1" ] || [ -L "$1" ]; then
    fail "Directory path is not a directory: $1"
  else
    parent=$(physical_path "$(dirname -- "$1")") || exit 2
    printf '%s/%s\n' "${parent%/}" "$(basename -- "$1")"
  fi
)

assert_dedicated_directory() (
  candidate=$(physical_path "$1") || exit 2
  case "$candidate" in
    /|/Users|/home|/root|/var|/private|/private/var|/tmp|/private/tmp|/srv|/opt|/etc|/usr|/mnt|/Volumes)
      fail "Use a dedicated directory, not a system root: $1" ;;
  esac
  shift
  for protected in "$@"; do
    [ -n "$protected" ] || continue
    protected=$(physical_path "$protected") || exit 2
    case "$protected/" in
      "$candidate/"*) fail "Directory must not contain a protected home, repository or runtime: $candidate" ;;
    esac
  done
)
