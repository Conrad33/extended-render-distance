package com.horizon.world;

import net.runelite.cache.definitions.ObjectDefinition;

/**
 * Builds one map square's scenery (every placement on every plane) into a single mesh, placed the way the client
 * places them (leaving out those outside the {@link Reach}): walls, wall decorations and ground decorations at the tile's centre, game objects and roofs at the
 * middle of their footprint (turned with the object), all standing on the average height of the corners under
 * them, and ground-hugging objects (fences, rugs) bent to follow the ground vertex by vertex. Vertices are relative
 * to the square's south-west corner, as for the terrain. Any thread.
 * <p>
 * The {@code landmarks} mesh is for far away: buildings (walls and roofs) and objects standing at least
 * {@link #LANDMARK_HEIGHT} high (trees, towers, windmills), without the small things (decorations on walls and the
 * ground, low clutter).
 */
public final class ObjectMesher
{
	/** Local units (128 to a tile) a free-standing object must stand to be seen from afar. */
	static final int LANDMARK_HEIGHT = 250;

	private final WorldCache cache;
	private final ObjectModels models;

	public ObjectMesher(WorldCache cache, int[] textureColours)
	{
		this.cache = cache;
		this.models = new ObjectModels(cache, textureColours);
	}

	public TerrainMesher.Built build(WorldTerrain.Region r, boolean landmarks, Reach reach)
	{
		WorldTerrain world = cache.terrain;
		VertexSink sink = new VertexSink(1 << 14);
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		for (int i = 0; i < r.locCount(); i++)
		{
			ObjectDefinition d = resolve(cache.object(r.locId(i)));
			if (d == null)
				continue;
			int type = r.locType(i), rot = r.locOrientation(i), plane = r.locPlane(i);
			int x = r.locX(i), y = r.locY(i);
			if (reach != null && !reach.has(r.baseX() + x, r.baseY() + y))
				continue;
			boolean roof = type >= 12 && type <= 21;
			boolean wall = type <= 3 || type == 9;
			if (landmarks && (type >= 4 && type <= 8 || type == 22))
				continue;

			// the model type(s) and orientation(s) the client asks for, and how the object is placed
			int[][] parts;
			boolean footprint = false;
			double yaw = 0;
			if (type == 2)
				parts = new int[][]{{2, 4 + rot}, {2, rot + 1 & 3}};
			else if (type >= 4 && type <= 8)
				parts = new int[][]{{4, rot}};
			else if (type == 11)
			{
				parts = new int[][]{{10, rot}};
				footprint = true;
				yaw = Math.PI / 4;
			}
			else
			{
				parts = new int[][]{{type, rot}};
				footprint = type == 10 || roof;
			}

			int sx = 1, sy = 1;
			if (footprint)
			{
				sx = Math.max(1, d.getSizeX());
				sy = Math.max(1, d.getSizeY());
				if ((rot & 1) == 1)
				{
					int t = sx;
					sx = sy;
					sy = t;
				}
			}
			int wx = r.baseX() + x, wy = r.baseY() + y;
			float cx = (x + sx / 2f) * 128, cz = (y + sy / 2f) * 128;
			float cy = (world.height(wx, wy, plane, r) + world.height(wx + sx, wy, plane, r)
				+ world.height(wx, wy + sy, plane, r) + world.height(wx + sx, wy + sy, plane, r)) / 4f;
			boolean contour = d.getContouredGround() >= 0;

			for (int[] part : parts)
			{
				ObjectModels.Lit m = models.get(d, part[0], part[1]);
				if (m == null && type == 9)
					m = models.get(d, 10, part[1]);
				if (m == null)
					continue;
				if (landmarks && !roof && !wall && m.top < LANDMARK_HEIGHT)
					continue;
				float sin = (float) Math.sin(yaw), cos = (float) Math.cos(yaw);
				float[] c = m.corners;
				for (int f = 0; f < m.faces * 3; f++)
				{
					float mx = c[f * 3], my = c[f * 3 + 1], mz = c[f * 3 + 2];
					if (yaw != 0)
					{
						float t = mx * cos + mz * sin;
						mz = mz * cos - mx * sin;
						mx = t;
					}
					float px = cx + mx, pz = cz + mz;
					float py = my + (contour ? groundAt(world, r, plane, px, pz) : cy);
					sink.put(px, py, pz, m.colours[f]);
					min = Math.min(min, (int) py);
					max = Math.max(max, (int) py);
				}
			}
		}
		if (sink.count == 0)
			min = max = 0;
		return new TerrainMesher.Built(r, landmarks ? 1 : 0, sink.finish(), sink.count, min, max);
	}

	/** An object that changes with the player's state shows its default form. */
	private ObjectDefinition resolve(ObjectDefinition d)
	{
		if (d == null || d.getObjectModels() != null)
			return d;
		int[] dest = d.getConfigChangeDest();
		if (dest == null)
			return null;
		for (int i = dest.length - 1; i >= 0; i--)
		{
			if (dest[i] != -1)
			{
				ObjectDefinition o = cache.object(dest[i]);
				return o != null && o.getObjectModels() != null ? o : null;
			}
		}
		return null;
	}

	/** The plane's height at a point given relative to the square's south-west corner (bilinear). */
	private static float groundAt(WorldTerrain world, WorldTerrain.Region r, int plane, float px, float pz)
	{
		float tx = px / 128f, tz = pz / 128f;
		int ix = (int) Math.floor(tx), iz = (int) Math.floor(tz);
		float fx = tx - ix, fz = tz - iz;
		int wx = r.baseX() + ix, wy = r.baseY() + iz;
		float south = world.height(wx, wy, plane, r) * (1 - fx) + world.height(wx + 1, wy, plane, r) * fx;
		float north = world.height(wx, wy + 1, plane, r) * (1 - fx) + world.height(wx + 1, wy + 1, plane, r) * fx;
		return south * (1 - fz) + north * fz;
	}
}
