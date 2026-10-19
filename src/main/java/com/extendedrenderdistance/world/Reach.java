package com.extendedrenderdistance.world;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.BitSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.gpu.regions.Regions;

/**
 * The part of the map that belongs with where the player is: the 8 x 8 chunks in the same area as the player's
 * chunk (the GPU plugin's "hide unrelated maps" areas, from the client's own regions.txt; everything it doesn't
 * list is one area) and joined to it through chunks that have ground. Dungeons sharing an area, and the surface
 * from underground, are cut off by the void between them. Immutable; any thread.
 */
@Slf4j
public final class Reach
{
	/** Chunk coordinates run 0 to 2047 each way. */
	private static final int SIDE = 2048;
	private static Regions areas;

	private final BitSet chunks;
	/** Map squares with any chunk in reach (rx << 8 | ry). */
	private final BitSet squares = new BitSet(256 * 256);
	private final int area;

	private Reach(BitSet chunks, int area)
	{
		this.chunks = chunks;
		this.area = area;
		for (int c = chunks.nextSetBit(0); c >= 0; c = chunks.nextSetBit(c + 1))
			squares.set((c / SIDE >> 3) << 8 | (c % SIDE >> 3));
	}

	/** Whether the chunk holding world tile (wx, wy) is in reach. */
	public boolean has(int wx, int wy)
	{
		int cx = wx >> 3, cy = wy >> 3;
		return cx >= 0 && cy >= 0 && cx < SIDE && cy < SIDE && chunks.get(cx * SIDE + cy);
	}

	/** Whether any chunk of the map square is in reach. */
	public boolean touches(WorldTerrain.Region r)
	{
		return squares.get(r.rx << 8 | r.ry);
	}

	public int area()
	{
		return area;
	}

	/** The area (per the GPU plugin's map areas) of the chunk holding world tile (wx, wy). */
	public static synchronized int areaOf(int wx, int wy)
	{
		if (areas == null)
		{
			try (InputStream in = Regions.class.getResourceAsStream("regions.txt"))
			{
				areas = new Regions(in, "regions.txt");
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Extended Render Distance: couldn't read the client's map areas", e);
				return 0;
			}
		}
		return areas.getRegionId(wx >> 3, wy >> 3);
	}

	/** The reach from world tile (wx, wy). Takes a few tens of milliseconds over the surface: off the client thread. */
	public static Reach from(WorldTerrain world, int wx, int wy)
	{
		int area = areaOf(wx, wy);
		BitSet out = new BitSet(SIDE * SIDE);
		BitSet seen = new BitSet(SIDE * SIDE);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		int start = (wx >> 3) * SIDE + (wy >> 3);
		queue.add(start);
		seen.set(start);
		boolean any = false;
		while (!queue.isEmpty())
		{
			int c = queue.poll();
			int cx = c / SIDE, cy = c % SIDE;
			// the start chunk counts even without ground (standing on a bridge or an upper floor)
			if (c != start && (areaOf(cx << 3, cy << 3) != area || !hasGround(world, cx, cy)))
				continue;
			out.set(c);
			any |= c != start;
			int[][] next = {{cx + 1, cy}, {cx - 1, cy}, {cx, cy + 1}, {cx, cy - 1}};
			for (int[] n : next)
			{
				if (n[0] < 0 || n[1] < 0 || n[0] >= SIDE || n[1] >= SIDE)
					continue;
				int k = n[0] * SIDE + n[1];
				if (!seen.get(k))
				{
					seen.set(k);
					queue.add(k);
				}
			}
		}
		if (!any)
		{
			// nothing joined on (no ground found here): fall back to the whole area
			for (WorldTerrain.Region r : world.regions())
			{
				for (int i = 0; i < 8; i++)
				{
					for (int j = 0; j < 8; j++)
					{
						int cx = r.rx * 8 + i, cy = r.ry * 8 + j;
						if (areaOf(cx << 3, cy << 3) == area)
							out.set(cx * SIDE + cy);
					}
				}
			}
		}
		return new Reach(out, area);
	}

	private static boolean hasGround(WorldTerrain world, int cx, int cy)
	{
		if (world.region(cx >> 3, cy >> 3) == null)
			return false;
		for (int x = 0; x < 8; x++)
		{
			for (int y = 0; y < 8; y++)
			{
				if (world.colour(cx * 8 + x, cy * 8 + y) != WorldTerrain.VOID)
					return true;
			}
		}
		return false;
	}
}
