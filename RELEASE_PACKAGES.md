# Release packages

A release package is a JSON file that describes a real OTA candidate, so TwinGuard can gate it the same way as the built-in demo releases. It names the feature the update changes, the settings that differ from approved v1, and any behaviour change the update declares.

You can submit a package in three ways:

- **Dashboard:** open **Import a release** in the full console. Paste the JSON, open a file, or start from a template.
- **API:** `POST /api/releases` with the JSON as the body.
- **CLI:** `java -jar backend\target\twinguard.jar gate --release-file=my-update.json` (collision braking) or `feature --release-file=my-update.json` (the other features). The CLI checks the package but doesn't save it.

Imported packages are saved, survive restarts, and appear beside the built-in releases. Importing a package with the same `id` replaces it.

## Format

```json
{
  "format": "twinguard-release/1",
  "id": "v2.5-faster-response",
  "title": "v2.5 Faster Brake Response",
  "summary": "Confirms radar targets in 2 samples instead of 3, and starts pre-braking a little earlier.",
  "component": "ADAS",
  "feature": "braking",
  "changes": { "debounceCycles": 2, "ttcPreBrakeS": 2.6 },
  "manifest": {
    "intent": "Pre-braking may begin up to 0.4 s earlier, and the stopping gap may shrink by up to 5 m",
    "allowedEarlyS": 0.4,
    "allowedLateS": 0,
    "allowedGapLossM": 5
  }
}
```

| Field | Required | Meaning |
|---|---|---|
| `format` | No | `twinguard-release/1` |
| `id` | Yes | Up to 64 letters, digits, `.`, `-` or `_`. It can't reuse a built-in release's id |
| `title` | Yes | Up to 120 characters |
| `summary` | No | Up to 500 characters |
| `component` | No | Defaults to `ADAS` for braking, or the feature's name |
| `feature` | Yes | `braking`, `battery`, `cruise`, `lane` or `pedestrian` |
| `changes` | Yes | The settings that differ from v1, with their new values. At least one must actually differ |
| `manifest` | No | What the update is allowed to do differently. See below |

Unknown fields are rejected, so a typo can't be silently ignored.

### Settings

`GET /api/releases/schema` lists every setting each feature accepts, with its v1 value and its allowed range. The import page shows the same table. `GET /api/releases/template?feature=battery` returns a starting package.

- **Collision braking:** any of the 28 firmware settings, such as `stopMargin`, `debounceCycles`, `infotainmentPriority` or `latchFullBrake`. Each has a fixed range, whole numbers where the setting is a count, and `true`/`false` for switches.
- **Other features:** any of the feature's settings, between a tenth and ten times the v1 value.

These ranges keep a package inside what the simulator can run. They don't make it safe: catching unsafe values is the gate's job. Before a package is accepted, TwinGuard runs one ordinary scenario with it, so a package the simulator can't run is rejected at import, not halfway through a test.

### Declaring a behaviour change

A declared change stops an intended difference from v1 counting against the release. **It never waives a fixed safety rule**, such as "no avoidable collision". A package that declares anything must explain it in `manifest.intent`.

- **Collision braking:** `allowedEarlyS` and `allowedLateS` (0 to 2 s) let braking start that much earlier or later than v1. `allowedGapLossM` (0 to 20 m) lets the stopping gap shrink by that much.
- **Other features:** list keys in `declaredChanges`:

| Feature | Key | Excuses |
|---|---|---|
| `battery` | `LOWER_CHARGE` | Less charge delivered than v1 |
| `cruise` | `SLOWER_RECOVERY` | Gentler acceleration back to speed |
| `lane` | `MORE_STEERING` | More steering activity |
| `pedestrian` | `EARLY_BRAKING` | Braking that begins earlier |

## Errors

The API answers `400` with every problem it found at once:

```json
{ "message": "The release package has 2 problems: ...", "problems": ["\"stopMargin\" must be between 0.1 and 5.", "\"title\" is required, up to 120 characters."] }
```

The CLI prints the same list and exits with code `3`.

## Examples

See [examples/releases](examples/releases).

## What a package can't do

A package changes settings of TwinGuard's own reference software. To test different code, package it as an FMU and put it in the FMU folder (see [fmus/README.md](fmus/README.md)). FMUs can't be uploaded through the dashboard, because loading one runs its native code on the server.
