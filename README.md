# Extended Render Distance

Render all of Gielinor at once. The game normally draws about 90 tiles around you, at most 184 with the GPU
plugin's settings turned up. This plugin draws the rest of the world beyond that, out to the horizon: the
terrain, buildings, trees and scenery of everywhere else, from the game's own cache.

Requires the **GPU** plugin. It doesn't work with 117 HD.

## What it does

- Everything near you is still drawn by the game as normal (NPCs, players, animations and clicking are untouched).
  The plugin only draws what lies beyond the GPU plugin's draw distance, and never draws anything twice.
- Distant scenery is simplified the further away it is, so it stays fast. Big structures (castles, pyramids)
  keep their shape.
- In a dungeon, only that dungeon is drawn; nothing far away is drawn in instances.
- Everything is read from the game cache through the client; the plugin makes no network requests of its own.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Terrain distance | 5000 | How far the terrain is rendered, in tiles. Nothing is drawn beyond it. |
| Structure distance | 5000 | How far structures (buildings, trees, etc) are rendered, in tiles. |
| Scenery distance | 184 | How far scenery (grass, rocks, etc) is rendered, in tiles. Performance heavy. |
| Performance Mode (Recommended) | On | Improves performance by simplifying distant geometry. Turn it off to see far-away shapes exactly (much slower). |

All three go up to 5000 tiles, which takes in the whole world. If your frame rate drops, lower the scenery
distance first, then the structure distance.

## Notes

- The GPU plugin's own fog at the edge of its draw distance is turned off while this plugin is on (it would show
  as a ring of sky between the normal view and the far world).
- The first few seconds after logging in, the world is read from the cache in the background; distant areas fill
  in as they're built.

## Credits

The cache decoders in `com.extendedrenderdistance.cache` are copied from
[RuneLite](https://github.com/runelite/runelite)'s cache module (BSD 2-Clause). See [LICENSE](LICENSE).
