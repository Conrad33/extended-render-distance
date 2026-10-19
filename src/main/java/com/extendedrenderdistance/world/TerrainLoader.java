package com.extendedrenderdistance.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.ConfigType;
import net.runelite.cache.definitions.LocationsDefinition;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.definitions.loaders.LocationsLoader;
import net.runelite.cache.definitions.loaders.MapLoader;
import net.runelite.cache.definitions.loaders.OverlayLoader;
import net.runelite.cache.definitions.loaders.UnderlayLoader;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Region;

/**
 * Reads the whole map out of the game cache (the surface and every dungeon), through a {@link CacheSource}: every map square's plane 0 heights, underlays and
 * overlays, how high the planes above sit, and its object placements (the maps index has one archive per region,
 * file 0 the terrain and file 1 the objects, unencrypted). Underlay colours are blended over the surrounding
 * 11 x 11 tiles the way the client does it, then packed to the client's 16-bit HSL. Slow (a few seconds): run it
 * off the client thread.
 */
@Slf4j
public final class TerrainLoader
{
	/** Tiles each side that underlay colours are blended over. */
	private static final int BLEND = 5;

	/** One map square's raw terrain while loading. */
	private static final class Raw
	{
		final int rx, ry;
		final short[] height = new short[64 * 64];
		final short[] underlay = new short[64 * 64];
		final short[] overlay = new short[64 * 64];
		final byte[] shape = new byte[64 * 64];
		final byte[][] rise = new byte[3][64 * 64];
		long[] locs = new long[0];
		long[] floors = new long[0];

		Raw(int rx, int ry)
		{
			this.rx = rx;
			this.ry = ry;
		}
	}

	private TerrainLoader()
	{
	}

	/** Map squares read per batch (one batch runs on the client thread in a frame's slice). */
	private static final int BATCH = 24;
	/**
	 * When squares the client doesn't have yet are asked for again (seconds after the first pass): asking makes the
	 * client fetch them over its own update connection, so only a few times, and never again after.
	 */
	private static final int[] RETRY_AFTER_SECONDS = {10, 30, 60};

	/** Reads the terrain and placements. Blocks while the reads are served: off the client thread. */
	public static WorldTerrain load(CacheSource cache) throws InterruptedException
	{
		long start = System.nanoTime();
		UnderlayDefinition[] underlays = loadUnderlays(cache);
		OverlayDefinition[] overlays = loadOverlays(cache);

		// which map squares exist: one group per square, id rx << 8 | ry
		List<Integer> squares = new ArrayList<>();
		for (int rx0 = 0; rx0 < 256; rx0 += 16)
		{
			int from = rx0;
			squares.addAll(cache.read(files ->
			{
				List<Integer> found = new ArrayList<>();
				for (int rx = from; rx < from + 16; rx++)
				{
					for (int ry = 0; ry < 256; ry++)
					{
						if (files.fileIds(CacheSource.MAPS, rx << 8 | ry) != null)
							found.add(rx << 8 | ry);
					}
				}
				return found;
			}));
		}

		Map<Integer, Raw> raws = new HashMap<>();
		List<Integer> missing = squares;
		long firstPass = System.currentTimeMillis();
		int failed = 0;
		for (int attempt = 0; ; attempt++)
		{
			List<Integer> stillMissing = new ArrayList<>();
			for (int i = 0; i < missing.size(); i += BATCH)
			{
				List<Integer> batch = missing.subList(i, Math.min(missing.size(), i + BATCH));
				byte[][][] data = cache.read(files ->
				{
					byte[][][] out = new byte[batch.size()][][];
					for (int k = 0; k < batch.size(); k++)
					{
						out[k] = new byte[][]{files.file(CacheSource.MAPS, batch.get(k), 0),
							files.file(CacheSource.MAPS, batch.get(k), 1)};
					}
					return out;
				});
				for (int k = 0; k < batch.size(); k++)
				{
					int id = batch.get(k);
					if (data[k][0] == null)
					{
						stillMissing.add(id);
						continue;
					}
					try
					{
						raws.put(id, decode(id >> 8, id & 255, data[k][0], data[k][1], underlays));
					}
					catch (RuntimeException e)
					{
						// a square that won't decode is left out (it shows as void)
						failed++;
					}
				}
			}
			missing = stillMissing;
			if (missing.isEmpty() || attempt >= RETRY_AFTER_SECONDS.length)
				break;
			// asking for them made the client fetch them: look again in a while
			long wait = firstPass + RETRY_AFTER_SECONDS[attempt] * 1000L - System.currentTimeMillis();
			if (wait > 0)
				Thread.sleep(wait);
		}
		if (failed > 0)
			log.warn("Extended Render Distance: {} map squares couldn't be read", failed);
		if (!missing.isEmpty())
			log.warn("Extended Render Distance: {} map squares weren't available from the client", missing.size());

		List<WorldTerrain.Region> out = new ArrayList<>(raws.size());
		for (Raw raw : raws.values())
			out.add(new WorldTerrain.Region(raw.rx, raw.ry, raw.height, blend(raw, raws, underlays), raw.overlay,
				raw.shape, raw.rise, raw.locs, raw.floors));
		log.info("Extended Render Distance: read {} map squares of terrain and objects in {} ms", out.size(),
			(System.nanoTime() - start) / 1_000_000);
		return new WorldTerrain(out, overlayColours(overlays));
	}

	/** One map square from its terrain file and its placements file (which may be null). */
	private static Raw decode(int rx, int ry, byte[] terrain, byte[] objects, UnderlayDefinition[] underlays)
	{
		MapDefinition def = new MapLoader().load(rx, ry, terrain);
		Region region = new Region(rx << 8 | ry);
		region.loadTerrain(def);
		Raw raw = new Raw(rx, ry);
		for (int x = 0; x < 64; x++)
		{
			for (int y = 0; y < 64; y++)
			{
				int i = x << 6 | y;
				raw.height[i] = (short) region.getTileHeight(0, x, y);
				raw.underlay[i] = (short) region.getUnderlayId(0, x, y);
				raw.overlay[i] = (short) region.getOverlayId(0, x, y);
				raw.shape[i] = (byte) ((region.getOverlayPath(0, x, y) & 63) << 2
					| region.getOverlayRotation(0, x, y) & 3);
				for (int z = 1; z < 4; z++)
					raw.rise[z - 1][i] = (byte) ((region.getTileHeight(z - 1, x, y) - region.getTileHeight(z, x, y)) / 8);
			}
		}
		raw.floors = floors(region, underlays);
		if (objects != null)
		{
			LocationsDefinition locs = new LocationsLoader().load(rx, ry, objects);
			long[] packed = new long[locs.getLocations().size()];
			int k = 0;
			for (Location l : locs.getLocations())
			{
				// positions are within the square
				int lx = l.getPosition().getX(), ly = l.getPosition().getY();
				if (lx < 0 || ly < 0 || lx > 63 || ly > 63)
					continue;
				packed[k++] = WorldTerrain.placement(l.getId(), l.getType(), l.getOrientation(),
					l.getPosition().getZ(), lx, ly);
			}
			raw.locs = java.util.Arrays.copyOf(packed, k);
		}
		return raw;
	}

	/** Tiles flagged this way aren't drawn by the client. */
	private static final int HIDDEN = 16;

	/** The floor tiles on planes 1 to 3 (only buildings and bridges have any). */
	private static long[] floors(Region region, UnderlayDefinition[] underlays)
	{
		long[] out = new long[64];
		int n = 0;
		for (int z = 1; z < 4; z++)
		{
			for (int x = 0; x < 64; x++)
			{
				for (int y = 0; y < 64; y++)
				{
					int u = region.getUnderlayId(z, x, y), o = region.getOverlayId(z, x, y);
					if (u <= 0 && o <= 0 || (region.getTileSetting(z, x, y) & HIDDEN) != 0)
						continue;
					int under = WorldTerrain.VOID;
					if (u > 0 && u - 1 < underlays.length && underlays[u - 1] != null)
					{
						UnderlayDefinition d = underlays[u - 1];
						under = pack(d.getHue() * 256 / Math.max(1, d.getHueMultiplier()), d.getSaturation(), d.getLightness());
					}
					if (n == out.length)
						out = java.util.Arrays.copyOf(out, n * 2);
					out[n++] = WorldTerrain.floor(z, x, y, region.getOverlayPath(z, x, y), region.getOverlayRotation(z, x, y),
						Math.max(0, o), under);
				}
			}
		}
		return java.util.Arrays.copyOf(out, n);
	}

	/** Colour code per overlay id (ids in the map are one more than the definition's). */
	private static int[] overlayColours(OverlayDefinition[] overlays)
	{
		int[] out = new int[overlays.length + 1];
		out[0] = WorldTerrain.VOID;
		for (int i = 0; i < overlays.length; i++)
		{
			OverlayDefinition o = overlays[i];
			if (o == null)
				out[i + 1] = WorldTerrain.VOID;
			else if (o.getTexture() >= 0)
				out[i + 1] = WorldTerrain.textured(o.getTexture());
			else if (o.getRgbColor() == 0xFF00FF)
				out[i + 1] = WorldTerrain.HOLE;
			else
				out[i + 1] = pack(o.getHue(), o.getSaturation(), o.getLightness());
		}
		return out;
	}

	/** Each tile's underlay colour, blended over the surrounding tiles. */
	private static char[] blend(Raw raw, Map<Integer, Raw> raws, UnderlayDefinition[] underlays)
	{
		// box sums over the padded square of the underlays' hue, saturation, lightness, hue weight and count
		int n = 64 + 2 * BLEND;
		long[][] sums = new long[5][(n + 1) * (n + 1)];
		int baseX = raw.rx * 64 - BLEND, baseY = raw.ry * 64 - BLEND;
		for (int i = 0; i < n; i++)
		{
			for (int j = 0; j < n; j++)
			{
				UnderlayDefinition u = underlayAt(raws, underlays, baseX + i, baseY + j);
				int k = (i + 1) * (n + 1) + (j + 1);
				long[] v = u == null ? null : new long[]{u.getHue(), u.getSaturation(), u.getLightness(),
					u.getHueMultiplier(), 1};
				for (int c = 0; c < 5; c++)
				{
					long s = (v == null ? 0 : v[c]) + sums[c][k - (n + 1)] + sums[c][k - 1] - sums[c][k - (n + 1) - 1];
					sums[c][k] = s;
				}
			}
		}

		char[] colour = new char[64 * 64];
		for (int x = 0; x < 64; x++)
		{
			for (int y = 0; y < 64; y++)
			{
				int idx = x << 6 | y;
				int under = WorldTerrain.VOID;
				if ((raw.underlay[idx] & 0xffff) > 0)
				{
					// the window around (x, y) in the padded square: i from x to x + 2 * BLEND
					long[] t = new long[5];
					for (int c = 0; c < 5; c++)
						t[c] = box(sums[c], n, x, y, x + 2 * BLEND, y + 2 * BLEND);
					if (t[4] > 0 && t[3] > 0)
					{
						int hue = (int) (t[0] * 256 / t[3]);
						int sat = (int) (t[1] / t[4]);
						int light = (int) (t[2] / t[4]);
						under = pack(hue, sat, Math.max(0, Math.min(255, light)));
					}
				}
				colour[idx] = WorldTerrain.packUnderlay(under);
			}
		}
		return colour;
	}

	private static long box(long[] s, int n, int x0, int y0, int x1, int y1)
	{
		int w = n + 1;
		return s[(x1 + 1) * w + (y1 + 1)] - s[x0 * w + (y1 + 1)] - s[(x1 + 1) * w + y0] + s[x0 * w + y0];
	}

	private static UnderlayDefinition underlayAt(Map<Integer, Raw> raws, UnderlayDefinition[] underlays, int wx, int wy)
	{
		if (wx < 0 || wy < 0)
			return null;
		Raw r = raws.get((wx >> 6) << 8 | (wy >> 6));
		if (r == null)
			return null;
		int id = r.underlay[(wx & 63) << 6 | (wy & 63)] & 0xffff;
		return id > 0 && id - 1 < underlays.length ? underlays[id - 1] : null;
	}

	/** The client's HSL packing for floors: saturation fades as tiles get very light. */
	static int pack(int hue, int sat, int light)
	{
		if (light > 179)
			sat /= 2;
		if (light > 192)
			sat /= 2;
		if (light > 217)
			sat /= 2;
		if (light > 243)
			sat /= 2;
		return (hue / 4 & 63) << 10 | (sat / 32 & 7) << 7 | (light / 2 & 127);
	}

	private static UnderlayDefinition[] loadUnderlays(CacheSource cache) throws InterruptedException
	{
		Map<Integer, byte[]> files = configFiles(cache, ConfigType.UNDERLAY);
		UnderlayDefinition[] out = new UnderlayDefinition[maxKey(files) + 1];
		UnderlayLoader loader = new UnderlayLoader();
		for (Map.Entry<Integer, byte[]> e : files.entrySet())
		{
			UnderlayDefinition d = loader.load(e.getKey(), e.getValue());
			d.calculateHsl();
			out[e.getKey()] = d;
		}
		return out;
	}

	private static OverlayDefinition[] loadOverlays(CacheSource cache) throws InterruptedException
	{
		Map<Integer, byte[]> files = configFiles(cache, ConfigType.OVERLAY);
		OverlayDefinition[] out = new OverlayDefinition[maxKey(files) + 1];
		OverlayLoader loader = new OverlayLoader();
		for (Map.Entry<Integer, byte[]> e : files.entrySet())
		{
			OverlayDefinition d = loader.load(e.getKey(), e.getValue());
			d.calculateHsl();
			out[e.getKey()] = d;
		}
		return out;
	}

	/** Every file of a config group (underlays, overlays, objects), by id. */
	static Map<Integer, byte[]> configFiles(CacheSource cache, ConfigType type) throws InterruptedException
	{
		int[] ids = cache.read(files -> files.fileIds(CacheSource.CONFIGS, type.getId()));
		Map<Integer, byte[]> out = new HashMap<>();
		if (ids == null)
			return out;
		// in slices, so no one frame reads them all
		for (int i = 0; i < ids.length; i += 2000)
		{
			int from = i, to = Math.min(ids.length, i + 2000);
			out.putAll(cache.read(files ->
			{
				Map<Integer, byte[]> part = new HashMap<>();
				for (int k = from; k < to; k++)
				{
					byte[] b = files.file(CacheSource.CONFIGS, type.getId(), ids[k]);
					if (b != null)
						part.put(ids[k], b);
				}
				return part;
			}));
		}
		return out;
	}

	private static int maxKey(Map<Integer, byte[]> m)
	{
		int max = 0;
		for (int k : m.keySet())
			max = Math.max(max, k);
		return max;
	}
}
