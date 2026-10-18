# Wayline (Fabric, Minecraft 26.2)

Per-world coordinate bookmarks (client-only).

| Command | What it does |
|---|---|
| `/wp add <name> [x y z]` | Save current position (or given coords) in the current dimension |
| `/wp remove <name>` | Delete |
| `/wp list` | List this world's waypoints |
| `/wp show / hide / toggle <name>` | Show or hide one on the HUD |
| `/wp showall` / `/wp hideall` | Show or hide all |
| `/wp hud` or key **J** | Turn the whole HUD on/off |

When you die, a `death-N` waypoint is saved automatically at that spot.

Waypoints are stored per world (singleplayer save name or server address) in `config/wayline.json`.
The HUD lists visible waypoints of your current dimension, nearest first, with distance and a direction arrow.
Nothing is sent to the server; it only displays coordinates you saved yourself.

Build: JDK 25, then `./gradlew build` -> `build/libs/wayline-1.0.0.jar`.
