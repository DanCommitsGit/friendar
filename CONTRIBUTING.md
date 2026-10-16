# Contributing

Bug reports, fixes and ideas are welcome. For anything larger than a fix, open an issue first so the approach can be agreed before you write it.

Keep pull requests scoped to one change to stay organized.

## Reporting a bug

Friendar behaves differently across phones, so a report is most useful with:

- The phone model and Android version
- What you expected and what happened

## Building and testing

The [README](README.md#build) lists the build requirements.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

CI runs the unit tests and a release build on every pull request.

## Docs

[docs/decisions.md](docs/decisions.md) records why some things work the way they do and what was ruled out.

[docs/architecture.md](docs/architecture.md) explains how things fit together.

## Commits

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/), because releases are generated from them.

---

Contributions are released under the [MIT license](LICENSE).
