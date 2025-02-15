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

Changed:

- Renamed in namespace `signaali.reactive`:
  - `observer-stack`       -> `run-observer-stack`
  - `with-observer`        -> `with-run-observer`
  - `get-current-observer` -> `get-current-run-observer`

## 0.1.0

First release! 🎉
