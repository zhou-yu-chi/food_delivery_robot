
## License
This project is based on [OrionStarGIT/RobotSample] and is licensed under the Apache-2.0 License.
See `LICENSE` for details. Copyright notices from the original
project are retained.

## Modifications
- 2025-10-28: Added network API–driven buzzer control (light/sound split, mute logic).
- Refactored navigation state handling and added `LanPinger` utility.
- Removed proprietary binaries and secrets from VCS; see below for SDK setup.

## SDK Setup
Per vendor docs, place the required SDK AARs under `app/libs/`
(or configure Gradle dependencies). Do **not** commit SDK binaries
with restrictive licenses.
