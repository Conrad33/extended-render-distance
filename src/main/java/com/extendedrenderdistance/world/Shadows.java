package com.extendedrenderdistance.world;

import com.extendedrenderdistance.cache.definitions.ModelDefinition;
import com.extendedrenderdistance.cache.definitions.ObjectDefinition;
import java.util.HashSet;
import java.util.Set;

/**
 * The shadows the client darkens the ground with, round a map square: objects that cast one give the tiles they
 * stand on a strength (walls 50 along their side; other objects their model's height over 4, at most 30, or 15
 * when their model isn't a plain lit one, over their footprint and a tile beyond it to the north and east), and a
 * tile's light is lowered by half its own strength, a quarter of its west and south neighbours' and an eighth of its
 * east and north neighbours' (measured against the client). Only the ground (plane 0) is shadowed. Any thread.
 */
public final class Shadows
{
	/** The strengths cover the square and a tile round it. */
	private static final int SIDE = WorldTerrain.REGION + 3;

	private final int x0, y0;
	private final byte[] strength;

	private Shadows(int x0, int y0, byte[] strength)
	{
		this.x0 = x0;
		this.y0 = y0;
		this.strength = strength;
	}

	/** How much a corner (world tile) of the square, or a tile beside it, is darkened: 0 for none. */
	public int at(int wx, int wy)
	{
		return (s(wx, wy - 1) >> 2) + (s(wx + 1, wy) >> 3) + (s(wx - 1, wy) >> 2) + (s(wx, wy + 1) >> 3)
			+ (s(wx, wy) >> 1);
	}

	private int s(int wx, int wy)
	{
		int i = wx - x0, j = wy - y0;
		return i < 0 || j < 0 || i >= SIDE || j >= SIDE ? 0 : strength[i * SIDE + j];
	}

	/** The shadows round square r, from the objects of it and its neighbours. Blocks reading models: off the client thread. */
	public static Shadows of(WorldCache cache, WorldTerrain.Region r) throws InterruptedException
	{
		int x0 = r.baseX() - 1, y0 = r.baseY() - 1;
		byte[] strength = new byte[SIDE * SIDE];
		WorldTerrain world = cache.terrain;

		// the heights of the models that set their strength, read ahead together
		Set<Integer> needed = new HashSet<>();
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				WorldTerrain.Region g = dx == 0 && dy == 0 ? r : world.region(r.rx + dx, r.ry + dy);
				for (int i = 0; g != null && i < g.locCount(); i++)
				{
					ObjectDefinition d = cache.object(g.locId(i));
					int type = g.locType(i);
					if (g.locPlane(i) == 0 && d != null && d.isShadow() && (type == 10 || type == 11) && plainModel(d)
						&& near(g, i, d, x0, y0) && !cache.hasModelHeight(d.getId()))
						ObjectModels.modelsOf(d, 10, needed);
				}
			}
		}
		cache.prefetchModels(needed);
		try
		{
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					WorldTerrain.Region g = dx == 0 && dy == 0 ? r : world.region(r.rx + dx, r.ry + dy);
					for (int i = 0; g != null && i < g.locCount(); i++)
						cast(cache, g, i, x0, y0, strength);
				}
			}
		}
		finally
		{
			cache.releaseModels(needed);
		}
		return new Shadows(x0, y0, strength);
	}

	/** Whether a placement's shadow can reach the area from (x0, y0). */
	private static boolean near(WorldTerrain.Region g, int i, ObjectDefinition d, int x0, int y0)
	{
		int wx = g.baseX() + g.locX(i), wy = g.baseY() + g.locY(i);
		int reach = Math.max(d.getSizeX(), d.getSizeY()) + 1;
		return wx + reach >= x0 && wx < x0 + SIDE && wy + reach >= y0 && wy < y0 + SIDE;
	}

	/** A model the client places lit as it is (not animated, changing form, or lit with its neighbours'). */
	static boolean plainModel(ObjectDefinition d)
	{
		return d.getAnimationID() < 0 && d.getConfigChangeDest() == null && !d.isMergeNormals();
	}

	private static void cast(WorldCache cache, WorldTerrain.Region g, int i, int x0, int y0, byte[] strength)
	{
		if (g.locPlane(i) != 0)
			return;
		ObjectDefinition d = cache.object(g.locId(i));
		if (d == null || !d.isShadow())
			return;
		int type = g.locType(i), rot = g.locOrientation(i) & 3;
		int x = g.baseX() + g.locX(i), y = g.baseY() + g.locY(i);
		if (type == 0)
		{
			int[][] along = {{0, 0, 0, 1}, {0, 1, 1, 1}, {1, 0, 1, 1}, {0, 0, 1, 0}};
			set(strength, x0, y0, x + along[rot][0], y + along[rot][1], 50);
			set(strength, x0, y0, x + along[rot][2], y + along[rot][3], 50);
		}
		else if (type == 1 || type == 3)
		{
			int[][] corner = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
			set(strength, x0, y0, x + corner[rot][0], y + corner[rot][1], 50);
		}
		else if (type == 2)
		{
			int[][] corners = {{0, 0, 0, 1, 1, 1}, {0, 1, 1, 1, 1, 0}, {1, 1, 1, 0, 0, 0}, {1, 0, 0, 0, 0, 1}};
			for (int k = 0; k < 6; k += 2)
				set(strength, x0, y0, x + corners[rot][k], y + corners[rot][k + 1], 50);
		}
		else if (type == 10 || type == 11)
		{
			int sx = Math.max(1, d.getSizeX()), sy = Math.max(1, d.getSizeY());
			if ((rot & 1) == 1)
			{
				int t = sx;
				sx = sy;
				sy = t;
			}
			int s = 15;
			if (plainModel(d))
				s = Math.min(30, cache.modelHeight(d) / 4);
			for (int dx = 0; dx <= sx; dx++)
			{
				for (int dy = 0; dy <= sy; dy++)
					set(strength, x0, y0, x + dx, y + dy, s);
			}
		}
	}

	/** Raises a tile's strength to {@code s}. */
	private static void set(byte[] strength, int x0, int y0, int wx, int wy, int s)
	{
		int i = wx - x0, j = wy - y0;
		if (i < 0 || j < 0 || i >= SIDE || j >= SIDE)
			return;
		if (s > strength[i * SIDE + j])
			strength[i * SIDE + j] = (byte) s;
	}

	/** How far a model reaches above its origin, shaped as an object places it (scaled and offset). */
	static int height(ModelDefinition m, ObjectDefinition d)
	{
		int top = 0;
		int sh = d.getModelSizeHeight(), oh = (short) d.getOffsetHeight();
		for (int v = 0; v < m.vertexCount; v++)
			top = Math.max(top, -(m.vertexY[v] * sh / 128 + oh));
		return top;
	}
}
