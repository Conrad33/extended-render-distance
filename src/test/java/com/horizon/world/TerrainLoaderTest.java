package com.horizon.world;

import java.io.File;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import org.junit.Test;

/** Reads the real game cache, when this machine has one. */
public class TerrainLoaderTest
{
	private static final File CACHE = new File(System.getProperty("user.home"), ".runelite/jagexcache/oldschool/LIVE");

	@Test
	public void readsTheSurface() throws Exception
	{
		assumeTrue(new File(CACHE, "main_file_cache.dat2").exists());
		try (WorldCache cache = WorldCache.open(CACHE))
		{
			WorldTerrain w = cache.terrain;
			assertTrue("surface squares: " + w.regions().size(), w.regions().size() > 1000);

			// Lumbridge castle courtyard: ground, with a colour
			WorldTerrain.Region lumbridge = w.region(3222 >> 6, 3218 >> 6);
			assertNotNull(lumbridge);
			assertNotEquals(WorldTerrain.VOID, w.colour(3222, 3218));
			assertTrue("placements: " + lumbridge.locCount(), lumbridge.locCount() > 1000);

			for (int step : new int[]{1, 4, 16})
			{
				TerrainMesher.Built b = TerrainMesher.build(w, w.region(50, 50), step, new int[]{0x2b4a, 0x2b4a});
				assertTrue(b.count > 0);
				assertEquals(0, b.count % 3);
				assertEquals(b.count * TerrainMesher.STRIDE, b.vertices.remaining());
			}

			ObjectMesher objects = new ObjectMesher(cache, new int[]{0x2b4a, 0x2b4a});
			long t = System.nanoTime();
			TerrainMesher.Built all = objects.build(lumbridge, false);
			long ms = (System.nanoTime() - t) / 1_000_000;
			TerrainMesher.Built far = objects.build(lumbridge, true);
			System.out.println("lumbridge scenery: " + all.count / 3 + " triangles in " + ms + " ms, landmarks "
				+ far.count / 3 + ", heights " + all.minHeight + ".." + all.maxHeight);
			assertTrue(all.count > 10000);
			assertEquals(0, all.count % 3);
			assertTrue(far.count < all.count);
		}
	}
}
