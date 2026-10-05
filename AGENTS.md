
[Architecture](docs/architecture.md)

Best practices in [scala](docs/scala.md)

## Build Commands

- `sbt compile` -> compiles all code
- `sbt Test/compile` -> compiles all test code
- `sbt scalafmtAll` -> formats all code; just run this after editing, no need to check first
- `sbt test` -> run unit tests (incremental, see below)
- `sbt testFull` -> run every unit test, ignoring the incremental cache
- `sbt <project>/testOnly <fully qualified class name>` -> runs unit tests in that class from src/test in its owning project
- `sbt <project>/testOnly <fully qualified class name> -t "<name>"` -> runs unit tests in that class whose names contain <name>

### sbt Usage

- run one command per `sbt` call
- sbt 2 caches aggressively, if a command returns successfully everything is up to date
- `sbt test` is incremental: it runs only tests that failed before, never ran, or whose dependencies changed, so it can print `No tests to run` and still succeed. Use `sbt testFull`, or name classes with `testOnly`, when you need a real run
- format with `sbt scalafmtAll`, not `scalafmtCheck` followed by `scalafmtOnly`: `scalafmtOnly` resolves paths against the project base directory (e.g. `shared/`), not the repository root
- when piping `sbt` output to `tail`/`head`, prefix the command with `set -o pipefail`, otherwise a failing build can look like exit code 0
