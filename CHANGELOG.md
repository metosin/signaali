# Signaali CHANGELOG

We use [Break Versioning][breakver]. The version numbers follow a `<major>.<minor>.<patch>` scheme with the following intent:

| Bump    | Intent                                                     |
| ------- | ---------------------------------------------------------- |
| `major` | Major breaking changes -- check the changelog for details. |
| `minor` | Minor breaking changes -- check the changelog for details. |
| `patch` | No breaking changes, ever!!                                |

`-SNAPSHOT` versions are preview versions for upcoming releases.

[breakver]: https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md

Signaali is currently [experimental](https://github.com/topics/metosin-experimental).

## Unreleased

Fixed:
- Reactivity bug: A maybe-stale memo which becomes stale no longer marks its watchers as stale for sure.
  The bug meant that a few nodes would re-run when not strictly needed, not honoring the memo's propagation filter.
- Reactivity bug: A maybe-stale node no longer misses a change propagated by its source when that source was run
  again before the node was run.

## 0.1.1

IMPORTANT:
  Do not use this release, it is deprecated.

Added:

- Small utility function `reactive-node?`.
- Internal function to get the current status of a ReactiveNode, useful for debugging.

Changed:

- Renamed in namespace `signaali.reactive`:
  - `observer-stack`       -> `run-observer-stack`
  - `with-observer`        -> `with-run-observer`
  - `get-current-observer` -> `get-current-run-observer`

## 0.1.0

First release! 🎉
