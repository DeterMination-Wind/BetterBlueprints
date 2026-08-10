# BetterBlueprints / 更好的蓝图

Mindustry mod that turns the blueprint browser into a Windows-10-style tile grid: fixed "All",
"Uncategorized" and "+" tiles plus user tiles of configurable size with cover previews. Tiles can
be dragged to any grid cell to rearrange the layout; clicking a tile shows its schematics, with a
search box on top.

把蓝图浏览器改造成磁贴式浏览：打开先见可配置大小与封面的磁贴，可拖拽换位；点进去看蓝图，
顶部有搜索框。每个蓝图最多属于一个用户磁贴，固定的「全部」与「未分类」磁贴始终显示。

## Features

- Launcher-style tile grid: every tile (user or fixed) is an entity on one virtual grid with its
  own cell size and position; positions are persisted across sessions
- Drag and drop: drag any tile onto another cell; the two tiles swap cells, remaining overlaps
  are resolved by chain-pushing along the drag direction (fallback: push down, then append below
  everything), so no overlap ever remains; a white outline marks the target cell
- Fixed tiles, always visible: "All" (every schematic), "Uncategorized" (schematics not in any
  user tile) and "+" (create a new tile); their positions can be dragged like user tiles
- Configurable tile size: 1x1 to 4x4 grid cells; one cell = one vanilla blueprint card (200px)
- Cover mode per tile: auto (first member schematic), a chosen schematic, or an imported image
- Search box filters the schematics inside the current tile; right-click clears the query
- Manage tiles: create, rename, delete and reorder user tiles (gear button in the top bar)
- Each schematic belongs to at most one user tile; importing while inside a tile assigns the
  imported schematic to that tile automatically
- One-time migration converts vanilla schematic tags into tiles, with a toast showing the count
- Replaces the vanilla blueprint browser globally (extends `SchematicsDialog`)

## Requirements

- Mindustry v154+ (built against v159)
- Java mod support enabled

## Build

```powershell
./gradlew build
```

`build` produces the desktop mod archive `build/libs/BetterBlueprints.zip`.

For a single merged jar that runs on both desktop and Android (includes `classes.dex`), use:

```powershell
./gradlew deploy
```

This writes `build/libs/BetterBlueprints.jar` and a local dev jar into
`../构建/BetterBlueprints/` (next to this repository). `deploy` needs D8: set
`ANDROID_SDK_ROOT`/`ANDROID_HOME` or `D8_PATH`, or keep a `commandlinetools-win-*` folder in the
workspace root on Windows.

## Usage

The tile gallery replaces the vanilla blueprint browser wherever it opens:

- in-game: press the schematic menu key (default `T`) or use the Schematics button in the
  bottom-left menu / pause menu
- in the editor: the Schematics button

Install the mod jar through Mindustry's `Mods` menu (`Mods > Import Mod > From File`).

## License

The repository does not include a license file.
