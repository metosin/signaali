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

## 0.1.1

Added:

- Small utility function `reactive-node?`.
- Internal function to get the current status of a ReactiveNode, useful for debugging.

Fixed:

- When an effect changed one of its sources, the effect would not re-run on the next update. Now it does.

Changed:

- Renamed in namespace `signaali.reactive`:
  - `observer-stack`       -> `run-observer-stack`
  - `with-observer`        -> `with-run-observer`
  - `get-current-observer` -> `get-current-run-observer`

## 0.1.0

First release! 🎉
