# Contributing

## Setup

- JDK 21+
- `./gradlew test` for the default suite
- Optional SingleStepTests submodules: see README (large; not needed for normal work)

## Releases

Ship a workstation zip by tagging `vX.Y.Z` and pushing the tag; CI builds
`k8086-app:distZip`, verifies a headless boot from the unzipped package, and
publishes the zip to GitHub Releases.

## Style

Match existing Kotlin style in the module you touch. Prefer small, focused changes
with tests when behavior changes.

## License

By contributing, you agree your contributions are licensed under the MIT License
(see [LICENSE](LICENSE)).
