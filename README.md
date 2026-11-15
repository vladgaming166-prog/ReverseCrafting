# ReverseCrafting

A tiny Paper plugin that reverse-crafts vanilla items back into their original materials using the vanilla crafting grid.

Place a normally crafted item in a crafting table (3x3) or the player inventory grid (2x2). If that item has a vanilla crafting recipe, the result slot shows its ingredients. Taking the result consumes the original item and returns every material used to craft it.

If a recipe uses more than one material type, the result slot shows the first ingredient stack and taking it also returns the rest. Example: a diamond axe gives 3 diamonds plus 2 sticks.

Enchanted, renamed, damaged, or otherwise modified items cannot be reverse-crafted. Vanilla crafting recipes are left untouched.

Works automatically after install. No commands, permissions, GUIs, or config.

## Requirements

- Paper 1.20.x through 26.2 / 26.3
- Java 17 or newer

## Download

A prebuilt plugin jar is in [`dist/ReverseCrafting.jar`](dist/ReverseCrafting.jar). Put that file in your server’s `plugins` folder.

## Build

```bash
mvn -q package
```

That writes `target/ReverseCrafting.jar`. The same file is also copied to `dist/ReverseCrafting.jar`.
