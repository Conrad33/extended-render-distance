package com.extendedrenderdistance.world;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import org.junit.Test;

/** Reads the real game cache, when this machine has one, with some squares held back as if not fetched yet. */
public class TerrainLoaderRetryTest
{
	private static final File CACHE = new File(System.getProperty("user.home"), ".runelite/jagexcache/oldschool/LIVE");

	/** Lumbridge and a square north-west of it, and the square between Lumbridge and Draynor. */
	private static final int[] HIDDEN = {50 << 8 | 50, 46 << 8 | 52, 49 << 8 | 50};

	@Test
	public void squaresArrivingLater() throws Exception
	{
		assumeTrue(new File(CACHE, "main_file_cache.dat2").exists());
		try (DiskCache disk = new DiskCache(CACHE))
		{
			Set<Integer> hidden = new HashSet<>();
			for (int id : HIDDEN)
				hidden.add(id);
			CacheSource partial = new CacheSource()
			{
				@Override
				public <T> T read(Reader<T> work) throws InterruptedException
				{
					return disk.read(files -> work.read(new Files()
					{
						@Override
						public byte[] file(int index, int group, int file)
						{
							return index == MAPS && hidden.contains(group) ? null : files.file(index, group, file);
						}

						@Override
						public int[] fileIds(int index, int group)
						{
							return files.fileIds(index, group);
						}
					}));
				}
			};

			TerrainLoader.Progress first = TerrainLoader.load(partial);
			assertEquals(hidden, new HashSet<>(first.missing));
			for (int id : HIDDEN)
				assertTrue(first.terrain.region(id >> 8, id & 255) == null);

			// nothing new: the same terrain
			TerrainLoader.Progress none = TerrainLoader.retry(partial, first);
			assertSame(first.terrain, none.terrain);
			assertEquals(first.missing, none.missing);

			hidden.clear();
			TerrainLoader.Progress second = TerrainLoader.retry(partial, none);
			assertTrue(second.missing.isEmpty());
			WorldTerrain full = TerrainLoader.load(disk).terrain;
			assertEquals(full.regions().size(), second.terrain.regions().size());

			Set<Integer> touched = new HashSet<>();
			for (int id : HIDDEN)
			{
				for (int dx = -1; dx <= 1; dx++)
				{
					for (int dy = -1; dy <= 1; dy++)
						touched.add(((id >> 8) + dx) << 8 | (id & 255) + dy);
				}
			}
			int kept = 0;
			for (WorldTerrain.Region r : second.terrain.regions())
			{
				WorldTerrain.Region before = first.terrain.region(r.rx, r.ry);
				if (touched.contains(r.rx << 8 | r.ry))
				{
					assertNotSame(before, r);
					// blended into the squares that arrived, as if read all at once
					for (int x = r.baseX(); x < r.baseX() + 64; x++)
					{
						for (int y = r.baseY(); y < r.baseY() + 64; y++)
						{
							assertEquals(full.underlay(x, y), second.terrain.underlay(x, y));
							assertEquals(full.colour(x, y), second.terrain.colour(x, y));
						}
					}
				}
				else
				{
					assertNotNull(before);
					assertSame(before, r);
					kept++;
				}
			}
			System.out.println("squares kept: " + kept + " of " + second.terrain.regions().size());
			assertTrue(kept > 1000);
			assertTrue(Arrays.stream(HIDDEN).allMatch(id -> second.terrain.region(id >> 8, id & 255) != null));
		}
	}
}
