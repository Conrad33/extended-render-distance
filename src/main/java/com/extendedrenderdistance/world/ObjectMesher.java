package com.extendedrenderdistance.world;

import net.runelite.cache.definitions.ObjectDefinition;

/**
 * Builds one map square's scenery (every placement on every plane) into a single mesh, placed the way the client
 * places them (leaving out those outside the {@link Reach}): walls, wall decorations and ground decorations at the tile's centre, game objects and roofs at the
 * middle of their footprint (turned with the object), all standing on the average height of the corners under
 * them, and ground-hugging objects (fences, rugs) bent to follow the ground vertex by vertex. Vertices are relative
 * to the square's south-west corner, as for the terrain. Any thread.
 * <p>
 * Further away, a mesh gets simpler ({@link Detail}): decorations on walls and the ground go, then everything but
 * buildings (walls, roofs and whatever stands on their upper floors), big objects (2 x 2 tiles or more) and
 * objects reaching at least {@link #LANDMARK_HEIGHT} above the ground (trees, towers, windmills), and every corner is snapped to a grid, so small details collapse to nothing and the triangles they
 * leave behind (and exact repeats) are dropped.
 */
public final class ObjectMesher
{
	/** Objects up to this size across (local units, 2 tiles) get a level's whole grid when size-aware. */
	static final float SIZE_REFERENCE = 256;
	/** Grids finer than this (local units) aren't worth snapping to. */
	static final float MIN_CELL = 8;

	/** Local units (128 to a tile) a free-standing object must stand to be seen from afar. */
	static final int LANDMARK_HEIGHT = 250;

	/** How much of a square's scenery a mesh holds. */
	public static final class Detail
	{
		/** Everything, exactly. */
		public static final Detail FULL = new Detail(true, false, 0, false);

		final boolean decorations;
		final boolean landmarksOnly;
		/** Grid corners are snapped to (local units), 0 for none. */
		final int cell;
		/** Whether big objects get a finer grid, in proportion to their size (see {@link #cellFor}). */
		final boolean sizeAware;
		/** Whether the grid is chosen once for the whole square, for its biggest object (so pieces meet exactly). */
		final boolean sealed;

		public Detail(boolean decorations, boolean landmarksOnly, int cell, boolean sizeAware)
		{
			this(decorations, landmarksOnly, cell, sizeAware, false);
		}

		public Detail(boolean decorations, boolean landmarksOnly, int cell, boolean sizeAware, boolean sealed)
		{
			this.decorations = decorations;
			this.landmarksOnly = landmarksOnly;
			this.cell = cell;
			this.sizeAware = sizeAware;
			this.sealed = sealed;
		}

		/**
		 * The grid for a model {@code extent} units across: the level's own, or for something bigger than
		 * {@link #SIZE_REFERENCE}, finer in proportion (a 10-tile-tall piece of the Necropolis pyramid snaps to a
		 * fifth of the grid a market stall does); none once that's under {@link #MIN_CELL}.
		 */
		float cellFor(float extent)
		{
			if (cell <= 0 || !sizeAware || extent <= SIZE_REFERENCE)
				return cell;
			float c = cell * SIZE_REFERENCE / extent;
			return c < MIN_CELL ? 0 : c;
		}
	}

	private final WorldCache cache;
	private final ObjectModels models;
	private final int[] textureColours;

	public ObjectMesher(WorldCache cache, int[] textureColours)
	{
		this.cache = cache;
		this.models = new ObjectModels(cache, textureColours);
		this.textureColours = textureColours;
	}

	public TerrainMesher.Built build(WorldTerrain.Region r, Detail detail, Reach reach)
	{
		WorldTerrain world = cache.terrain;
		VertexSink sink = new VertexSink(1 << 14);
		boolean landmarks = detail.landmarksOnly;
		java.util.HashSet<Long> seen = detail.cell > 0 ? new java.util.HashSet<>() : null;
		// sealed: one grid for the whole square, fine enough for the biggest object in it
		float squareCell = detail.sealed ? detail.cellFor(biggest(r, detail, reach)) : -1;
		float[] tri = new float[9];
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
			if (!detail.decorations && (type >= 4 && type <= 8 || type == 22))
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
			int anchor = x << 6 | y;
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
				// stands out from afar: part of a building, big, or reaching high above the ground (counting the floor
				// it stands on: a flat canopy up on a building is still a roof)
				if (landmarks && !roof && !wall && plane == 0 && sx * sy < 4
					&& m.top + (world.height(wx, wy, 0, r) - cy) < LANDMARK_HEIGHT)
					continue;
				float sin = (float) Math.sin(yaw), cos = (float) Math.cos(yaw);
				float cell = squareCell >= 0 ? squareCell : detail.cellFor(m.extent);
				float[] c = m.corners;
				for (int f = 0; f < m.faces; f++)
				{
					for (int k = 0; k < 3; k++)
					{
						int v = f * 3 + k;
						float mx = c[v * 3], my = c[v * 3 + 1], mz = c[v * 3 + 2];
						if (yaw != 0)
						{
							float t = mx * cos + mz * sin;
							mz = mz * cos - mx * sin;
							mx = t;
						}
						float px = cx + mx, pz = cz + mz;
						float py = my + (contour ? groundAt(world, r, plane, px, pz) : cy);
						if (cell > 0)
						{
							px = Math.round(px / cell) * cell;
							py = Math.round(py / cell) * cell;
							pz = Math.round(pz / cell) * cell;
						}
						tri[k * 3] = px;
						tri[k * 3 + 1] = py;
						tri[k * 3 + 2] = pz;
					}
					if (cell > 0 && (degenerate(tri) || !seen.add(key(tri, cell))))
						continue;
					for (int k = 0; k < 3; k++)
					{
						sink.put(tri[k * 3], tri[k * 3 + 1], tri[k * 3 + 2], stamp(m.colours[f * 3 + k], anchor));
						min = Math.min(min, (int) tri[k * 3 + 1]);
						max = Math.max(max, (int) tri[k * 3 + 1]);
					}
				}
			}
		}
		// upper floors and flat roofs, at every level of detail (a building without them is hollow from above)
		for (int i = 0; i < r.floorCount(); i++)
		{
			int wx = r.baseX() + r.floorX(i), wy = r.baseY() + r.floorY(i);
			if (reach != null && !reach.has(wx, wy))
				continue;
			int top = floor(world, sink, r, i, wx, wy);
			min = Math.min(min, top);
			max = Math.max(max, top);
		}
		if (sink.count == 0)
			min = max = 0;
		return new TerrainMesher.Built(r, 0, sink.finish(), sink.count, min, max);
	}

	/**
	 * One upper floor tile: the overlay over all or part of it (as its shape says), the underlay under the rest,
	 * lit from the slope like the ground. Both sides are drawn (seen from above it's a roof, from below a ceiling).
	 * Returns its height.
	 */
	private int floor(WorldTerrain world, VertexSink sink, WorldTerrain.Region r, int i, int wx, int wy)
	{
		int plane = r.floorPlane(i);
		int under = r.floorUnderlay(i);
		int over = TerrainMesher.resolve(world.overlayColour(r.floorOverlay(i)), textureColours);
		boolean hole = world.overlayColour(r.floorOverlay(i)) == WorldTerrain.HOLE;
		int path = r.floorPath(i);
		float[] ch = new float[4];
		double[] cl = new double[4];
		int[][] corner = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
		for (int k = 0; k < 4; k++)
		{
			int cx = wx + corner[k][0], cy = wy + corner[k][1];
			ch[k] = world.height(cx, cy, plane, r);
			int dx = world.height(cx + 1, cy, plane, r) - world.height(cx - 1, cy, plane, r);
			int dz = world.height(cx, cy + 1, plane, r) - world.height(cx, cy - 1, plane, r);
			cl[k] = TerrainMesher.light(dx, dz, 1);
		}
		float x0 = (wx - r.baseX()) * 128f, z0 = (wy - r.baseY()) * 128f;
		boolean[] mask = over == WorldTerrain.VOID && !hole ? null
			: path == 0 ? null : TileShapes.mask(path, r.floorRotation(i));
		for (int cell = 0; cell < (mask == null ? 1 : 16); cell++)
		{
			int row = cell / 4, col = cell % 4;
			boolean overlay = mask == null ? over != WorldTerrain.VOID || hole : mask[cell];
			int colour = overlay ? (hole ? WorldTerrain.VOID : over) : under;
			if (colour == WorldTerrain.VOID)
				continue;
			double u0 = mask == null ? 0 : col / 4.0, u1 = mask == null ? 1 : (col + 1) / 4.0;
			double v0 = mask == null ? 0 : 1 - (row + 1) / 4.0, v1 = mask == null ? 1 : 1 - row / 4.0;
			double[] us = {u0, u1, u1, u0}, vs = {v0, v0, v1, v1};
			float[] x = new float[4], y = new float[4], z = new float[4];
			int[] c = new int[4];
			for (int k = 0; k < 4; k++)
			{
				x[k] = x0 + (float) (us[k] * 128);
				z[k] = z0 + (float) (vs[k] * 128);
				y[k] = (float) bilinear(ch, us[k], vs[k]);
				c[k] = stamp(TerrainMesher.shade(colour, bilinear(cl, us[k], vs[k])), r.floorX(i) << 6 | r.floorY(i));
			}
			sink.quad(x, y, z, c);
			// and the other side
			sink.quad(new float[]{x[0], x[3], x[2], x[1]}, new float[]{y[0], y[3], y[2], y[1]},
				new float[]{z[0], z[3], z[2], z[1]}, new int[]{c[0], c[3], c[2], c[1]});
		}
		return (int) ch[0];
	}

	private static double bilinear(float[] c, double u, double v)
	{
		double south = c[0] + (c[1] - c[0]) * u, north = c[3] + (c[2] - c[3]) * u;
		return south + (north - south) * v;
	}

	private static double bilinear(double[] c, double u, double v)
	{
		double south = c[0] + (c[1] - c[0]) * u, north = c[3] + (c[2] - c[3]) * u;
		return south + (north - south) * v;
	}

	/**
	 * A vertex colour as drawn: the packed HSL, the face's draw priority (to 7) and the tile within the square of
	 * the object (or floor) it belongs to, so the renderer can leave out whole objects the game draws itself.
	 */
	static int stamp(int colour, int anchor)
	{
		return colour & 0xffff | Math.min(7, colour >> 16 & 15) << 16 | (anchor + 1) << 19;
	}

	/** Whether two of a snapped triangle's corners landed on the same point. */
	private static boolean degenerate(float[] t)
	{
		return same(t, 0, 3) || same(t, 3, 6) || same(t, 0, 6);
	}

	private static boolean same(float[] t, int a, int b)
	{
		return t[a] == t[b] && t[a + 1] == t[b + 1] && t[a + 2] == t[b + 2];
	}

	/**
	 * The same snapped triangle facing the same way (any first corner) gives the same key; its back (the corners in
	 * the other order) doesn't, as back faces aren't drawn.
	 */
	private static long key(float[] t, float cell)
	{
		long[] c = new long[3];
		for (int i = 0; i < 3; i++)
		{
			long x = Math.round(t[i * 3] / cell) & 0xfff, y = Math.round(t[i * 3 + 1] / cell) & 0xfff;
			long z = Math.round(t[i * 3 + 2] / cell) & 0xfff;
			c[i] = x << 24 | y << 12 | z;
		}
		// start from the smallest corner, keeping the order round the triangle
		int s = c[0] <= c[1] && c[0] <= c[2] ? 0 : c[1] <= c[2] ? 1 : 2;
		long[] k = {c[s], c[(s + 1) % 3], c[(s + 2) % 3]};
		// the grid too: triangles snapped to different grids aren't the same
		return k[0] * 0x9E3779B97F4A7C15L ^ Long.rotateLeft(k[1] * 0xC2B2AE3D27D4EB4FL, 21) ^ Long.rotateLeft(k[2], 42)
			^ (long) Float.floatToIntBits(cell) * 0x165667B19E3779F9L;
	}

	/** The biggest model (local units across) in the square that this detail level draws. */
	private float biggest(WorldTerrain.Region r, Detail detail, Reach reach)
	{
		float biggest = 0;
		for (int i = 0; i < r.locCount(); i++)
		{
			int type = r.locType(i);
			if (!detail.decorations && (type >= 4 && type <= 8 || type == 22))
				continue;
			if (reach != null && !reach.has(r.baseX() + r.locX(i), r.baseY() + r.locY(i)))
				continue;
			ObjectDefinition d = resolve(cache.object(r.locId(i)));
			if (d == null)
				continue;
			int modelType = type == 11 ? 10 : type >= 4 && type <= 8 ? 4 : type == 2 ? 2 : type;
			ObjectModels.Lit m = models.get(d, modelType, r.locOrientation(i));
			if (m != null)
				biggest = Math.max(biggest, m.extent);
		}
		return biggest;
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
