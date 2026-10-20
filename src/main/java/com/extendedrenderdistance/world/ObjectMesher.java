package com.extendedrenderdistance.world;

import com.extendedrenderdistance.cache.definitions.ModelDefinition;
import com.extendedrenderdistance.cache.definitions.ObjectDefinition;
import com.extendedrenderdistance.cache.models.VertexNormal;

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
	/** Squares whose objects are all up to this size across (local units, 2 tiles) get a level's whole grid. */
	static final float SIZE_REFERENCE = 256;
	/** Grids finer than this (local units) aren't worth snapping to. */
	static final float MIN_CELL = 8;

	/** Local units (128 to a tile) a free-standing object must stand to be seen from afar. */
	static final int LANDMARK_HEIGHT = 250;

	/** How much of a square's scenery a mesh holds. */
	public static final class Detail
	{
		/** Everything, exactly. */
		public static final Detail FULL = new Detail(true, false, 0);

		public final boolean decorations;
		public final boolean landmarksOnly;
		/** Grid corners are snapped to (local units), 0 for none (see {@link #cellFor}). */
		final int cell;

		public Detail(boolean decorations, boolean landmarksOnly, int cell)
		{
			this.decorations = decorations;
			this.landmarksOnly = landmarksOnly;
			this.cell = cell;
		}

		/**
		 * The grid for a square whose biggest model is {@code extent} units across: the level's own, or with
		 * anything bigger than {@link #SIZE_REFERENCE} in it, finer in proportion (a square holding a 10-tile-tall
		 * piece of the Necropolis pyramid snaps to a fifth of the grid of one with only market stalls); none once
		 * that's under {@link #MIN_CELL}. One grid for the whole square, so neighbouring pieces of a structure still
		 * meet exactly.
		 */
		float cellFor(float extent)
		{
			if (cell <= 0 || extent <= SIZE_REFERENCE)
				return cell;
			float c = cell * SIZE_REFERENCE / extent;
			return c < MIN_CELL ? 0 : c;
		}
	}

	/**
	 * An object drawn playing its animation, rather than built into its square's mesh: its model (a form, model type
	 * and orientation), where it stands relative to its square's south-west corner (local units, y down), turned by
	 * {@code yaw}, the centre of its tile (for whether the game draws it itself), and how far into its animation it
	 * starts (client cycles).
	 */
	public static final class Animated
	{
		public final long model;
		public final ObjectAnimation animation;
		public final float x, y, z, yaw, tileX, tileZ;
		public final int phase;

		Animated(long model, ObjectAnimation animation, float x, float y, float z, float yaw, float tileX, float tileZ,
			int phase)
		{
			this.model = model;
			this.animation = animation;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.tileX = tileX;
			this.tileZ = tileZ;
			this.phase = phase;
		}
	}

	private final WorldCache cache;
	private final ObjectModels models;
	private final int[] textureColours;
	/** Animated objects are left out of the squares' meshes, to be drawn playing ({@link Animated}). */
	private final boolean animate;
	/**
	 * Animated models' frames built with the squares they're in, so a square is never drawn without them, until
	 * they're taken to be drawn ({@link #takeFrames}).
	 */
	private final java.util.concurrent.ConcurrentHashMap<Long, TerrainMesher.Built[]> ready =
		new java.util.concurrent.ConcurrentHashMap<>();
	/** Models whose frames have been taken, so the squares built after don't build them again. */
	private final java.util.Set<Long> taken = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public ObjectMesher(WorldCache cache, int[] textureColours)
	{
		this(cache, textureColours, false);
	}

	/** The game's low detail mode: most ground decorations left out, as the game leaves them out. */
	private final boolean lowDetail;

	public ObjectMesher(WorldCache cache, int[] textureColours, boolean animate)
	{
		this(cache, textureColours, animate, false);
	}

	public ObjectMesher(WorldCache cache, int[] textureColours, boolean animate, boolean lowDetail)
	{
		this.cache = cache;
		this.models = new ObjectModels(cache, textureColours);
		this.textureColours = textureColours;
		this.animate = animate;
		this.lowDetail = lowDetail;
	}

	/**
	 * Whether the game shows a ground decoration in low detail mode: only those that can be used, block the way, or
	 * cover the ground (measured against the game).
	 */
	private static boolean shownInLowDetail(ObjectDefinition d)
	{
		return d.getWallOrDoor() != 0 || d.getInteractType() == 1 || d.isObstructsGround();
	}

	/**
	 * An animated model's frames built ahead (with a square it's in), handed over once: null if there are none
	 * (build them with {@link #frames}).
	 */
	public TerrainMesher.Built[] takeFrames(long model)
	{
		TerrainMesher.Built[] out = ready.remove(model);
		if (out != null)
			taken.add(model);
		return out;
	}

	/** The models whose frames have been taken. */
	public java.util.List<Long> takenModels()
	{
		return new java.util.ArrayList<>(taken);
	}

	/** Lets a model's frames be built ahead again (its frames were let go). */
	public void forget(long model)
	{
		taken.remove(model);
	}

	/**
	 * Every frame of an animated object's model ({@link Animated#model}), each relative to where it stands
	 * (unanchored: the anchor is given where it's drawn); null where a frame has nothing to draw.
	 */
	public TerrainMesher.Built[] frames(long model)
	{
		ObjectDefinition d = cache.object((int) (model >>> 16));
		int type = (int) (model >> 8 & 0xff), orientation = (int) (model & 0xff);
		ObjectAnimation anim = d == null ? null : cache.animation(d.getAnimationID());
		if (anim == null)
			return new TerrainMesher.Built[0];
		TerrainMesher.Built[] out = new TerrainMesher.Built[anim.frameCount()];
		for (int k = 0; k < out.length; k++)
		{
			ObjectModels.Lit m = models.get(d, type, orientation, k);
			if (m == null)
				continue;
			VertexSink sink = new VertexSink(m.faces * 3), glass = new VertexSink(64, true);
			int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
			for (int v = 0; v < m.faces * 3; v++)
			{
				float px = m.corners[v * 3], py = m.corners[v * 3 + 1], pz = m.corners[v * 3 + 2];
				int colour = stamp(m.colours[v], -1);
				int texture = m.textures == null ? 0 : m.textures[v];
				float u = texture == 0 ? 0 : m.uvs[v * 2], tv = texture == 0 ? 0 : m.uvs[v * 2 + 1];
				int alpha = m.colours[v] >>> 24;
				if (alpha > 0)
					glass.put(px, py, pz, colour, u, tv, texture, alpha);
				else
					sink.put(px, py, pz, colour, u, tv, texture);
				min = Math.min(min, (int) py);
				max = Math.max(max, (int) py);
			}
			out[k] = sink.build(null, 0, min, max);
			if (glass.count > 0)
				out[k].translucent = glass.build(null, 0, min, max);
		}
		return out;
	}

	/**
	 * A placed object whose model the client lights as one with its neighbours' (walls, roofs and the like whose
	 * definition says so): where their vertices meet, their normals are added together, so the light runs on
	 * smoothly from one to the next, and faces wholly against a neighbour on the same floor are hidden.
	 */
	private static final class Merging
	{
		final ObjectDefinition def;
		/** The square's placement (and part of it: a corner wall has two), or -1 for a neighbouring square's. */
		final int loc, part;
		/** World tile, plane and footprint. */
		final int x, y, plane, sx, sy;
		/** Where its model stands: the footprint's middle, and its tile's height (as the client offsets them). */
		final int cx, cy, cz;
		final ModelDefinition model;
		/** Its normals before any merging: what it gives its neighbours (its own are replaced as it's merged). */
		final VertexNormal[] normals;
		final int minX, maxX, minY, maxY, minZ, maxZ;

		Merging(ObjectDefinition def, int loc, int part, int x, int y, int plane, int sx, int sy, int cy,
			ModelDefinition model)
		{
			this.def = def;
			this.loc = loc;
			this.part = part;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.sx = sx;
			this.sy = sy;
			this.cx = x * 128 + sx * 64;
			this.cz = y * 128 + sy * 64;
			this.cy = cy;
			this.model = model;
			this.normals = model.vertexNormals.clone();
			int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = x0, y1 = x1, z0 = x0, z1 = x1;
			for (int v = 0; v < model.vertexCount; v++)
			{
				x0 = Math.min(x0, model.vertexX[v]);
				x1 = Math.max(x1, model.vertexX[v]);
				y0 = Math.min(y0, model.vertexY[v]);
				y1 = Math.max(y1, model.vertexY[v]);
				z0 = Math.min(z0, model.vertexZ[v]);
				z1 = Math.max(z1, model.vertexZ[v]);
			}
			minX = x0;
			maxX = x1;
			minY = y0;
			maxY = y1;
			minZ = z0;
			maxZ = z1;
		}
	}

	/** The placement's model parts (model type, orientation) whose normals the client merges; null for none. */
	private static int[][] mergedParts(ObjectDefinition d, int type, int rot)
	{
		if (d == null || !d.isMergeNormals() || d.getContouredGround() >= 0 || d.getAnimationID() >= 0)
			return null;
		if (type == 2)
			return new int[][]{{2, 4 + rot}, {2, rot + 1 & 3}};
		if (type <= 3 || type == 9 || type == 10 || type >= 12 && type <= 21)
			return new int[][]{{type, rot}};
		return null;
	}

	/**
	 * The square's placements to be merged ({@code own}) and those of its neighbours' within a tile of it, as
	 * region and placement index pairs.
	 */
	private java.util.List<Object[]> mergeable(WorldTerrain.Region r)
	{
		java.util.List<Object[]> out = new java.util.ArrayList<>();
		WorldTerrain world = cache.terrain;
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				WorldTerrain.Region g = dx == 0 && dy == 0 ? r : world.region(r.rx + dx, r.ry + dy);
				if (g == null)
					continue;
				for (int i = 0; i < g.locCount(); i++)
				{
					int wx = g.baseX() + g.locX(i), wy = g.baseY() + g.locY(i);
					// neighbours' placements up to a tile in (a big one's footprint can reach further: near enough)
					if (g != r && (wx < r.baseX() - 4 || wx > r.baseX() + 64 || wy < r.baseY() - 4 || wy > r.baseY() + 64))
						continue;
					if (mergedParts(resolve(cache.object(g.locId(i))), g.locType(i), g.locOrientation(i)) != null)
						out.add(new Object[]{g, i});
				}
			}
		}
		return out;
	}

	/**
	 * The square's merged placements lit, by placement and part ({@code loc * 2 + part}; null for one with nothing
	 * left to draw), as the client lights them: each one's normals added to by every neighbour's (in a tile of it,
	 * a floor up or down) at the vertices they share, and faces wholly shared with a neighbour on the same floor
	 * hidden.
	 */
	private java.util.Map<Integer, ObjectModels.Lit> merged(WorldTerrain.Region r, java.util.List<Object[]> locs)
	{
		java.util.Map<Integer, ObjectModels.Lit> out = new java.util.HashMap<>();
		if (locs.isEmpty())
			return out;
		WorldTerrain world = cache.terrain;
		java.util.List<Merging> all = new java.util.ArrayList<>();
		for (Object[] l : locs)
		{
			WorldTerrain.Region g = (WorldTerrain.Region) l[0];
			int i = (int) l[1];
			ObjectDefinition d = resolve(cache.object(g.locId(i)));
			int type = g.locType(i), rot = g.locOrientation(i), plane = g.locPlane(i);
			int[][] parts = mergedParts(d, type, rot);
			int sx = 1, sy = 1;
			if (type == 10 || type >= 12)
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
			int wx = g.baseX() + g.locX(i), wy = g.baseY() + g.locY(i);
			// as the client: its first tile's height
			int cy = (world.height(wx, wy, plane, g) + world.height(wx + 1, wy, plane, g)
				+ world.height(wx, wy + 1, plane, g) + world.height(wx + 1, wy + 1, plane, g)) / 4;
			for (int p = 0; p < parts.length; p++)
			{
				ModelDefinition m = models.shaped(d, parts[p][0], parts[p][1]);
				if (m == null && type == 9)
					m = models.shaped(d, 10, parts[p][1]);
				if (m != null)
					all.add(new Merging(d, g == r ? i : -1, p, wx, wy, plane, sx, sy, cy, m));
			}
		}

		for (Merging a : all)
		{
			if (a.loc < 0)
				continue;
			int n = a.model.vertexCount;
			int[][] add = new int[n][];
			boolean[] hidden = null;
			for (Merging b : all)
			{
				if (b == a || Math.abs(a.plane - b.plane) > 1)
					continue;
				if (b.x > a.x + a.sx || b.x + b.sx - 1 < a.x - 1 || b.y > a.y + a.sy || b.y + b.sy - 1 < a.y - 1)
					continue;
				boolean pair = b.loc == a.loc;
				boolean samePlane = a.plane == b.plane;
				// on its own floor, not what stands on its own footprint (but a corner wall's two sides)
				if (samePlane && !pair && b.x >= a.x && b.x < a.x + a.sx && b.y >= a.y && b.y < a.y + a.sy)
					continue;
				boolean[] marked = merge(a, b, add);
				if (marked != null && samePlane && !pair)
				{
					for (int f = 0; f < a.model.faceCount; f++)
					{
						if (marked[a.model.faceIndices1[f]] && marked[a.model.faceIndices2[f]] && marked[a.model.faceIndices3[f]])
						{
							if (hidden == null)
								hidden = new boolean[a.model.faceCount];
							hidden[f] = true;
						}
					}
				}
			}
			ModelDefinition m = a.model;
			for (int v = 0; v < n; v++)
			{
				if (add[v] == null)
					continue;
				VertexNormal o = m.vertexNormals[v], s = new VertexNormal();
				s.x = o.x + add[v][0];
				s.y = o.y + add[v][1];
				s.z = o.z + add[v][2];
				s.magnitude = o.magnitude + add[v][3];
				m.vertexNormals[v] = s;
			}
			if (hidden != null)
			{
				if (m.faceRenderTypes == null)
					m.faceRenderTypes = new byte[m.faceCount];
				for (int f = 0; f < m.faceCount; f++)
				{
					if (hidden[f])
						m.faceRenderTypes[f] = 2;
				}
			}
			ObjectModels.Lit lit = models.lit(m, a.def);
			out.put(a.loc * 2 + a.part, lit);
		}
		return out;
	}

	/**
	 * Adds to {@code add} (per vertex of a: x, y, z, magnitude; null to add nothing) the normals of b's vertices
	 * that sit where a's do. Returns which of a's vertices met one, when at least 3 did (else null).
	 */
	private static boolean[] merge(Merging a, Merging b, int[][] add)
	{
		int dx = b.cx - a.cx, dy = b.cy - a.cy, dz = b.cz - a.cz;
		ModelDefinition ma = a.model, mb = b.model;
		boolean[] marked = null;
		int count = 0;
		for (int i = 0; i < ma.vertexCount; i++)
		{
			VertexNormal na = a.normals[i];
			if (na == null || na.magnitude == 0)
				continue;
			int x = ma.vertexX[i] - dx, y = ma.vertexY[i] - dy, z = ma.vertexZ[i] - dz;
			if (x < b.minX || x > b.maxX || y < b.minY || y > b.maxY || z < b.minZ || z > b.maxZ)
				continue;
			for (int j = 0; j < mb.vertexCount; j++)
			{
				VertexNormal nb = b.normals[j];
				if (mb.vertexX[j] != x || mb.vertexY[j] != y || mb.vertexZ[j] != z || nb == null || nb.magnitude == 0)
					continue;
				if (add != null)
				{
					if (add[i] == null)
						add[i] = new int[4];
					add[i][0] += nb.x;
					add[i][1] += nb.y;
					add[i][2] += nb.z;
					add[i][3] += nb.magnitude;
				}
				if (marked == null)
					marked = new boolean[ma.vertexCount];
				marked[i] = true;
				count++;
			}
		}
		return count >= 3 ? marked : null;
	}

	public TerrainMesher.Built build(WorldTerrain.Region r, Detail detail, Reach reach)
	{
		// read the square's models ahead in a few batches, rather than one at a time as they're met
		java.util.Set<Integer> needed = new java.util.HashSet<>();
		// and those of the placements lit with their neighbours', and those neighbours'
		java.util.List<Object[]> mergeable = mergeable(r);
		for (Object[] l : mergeable)
		{
			WorldTerrain.Region g = (WorldTerrain.Region) l[0];
			int i = (int) l[1];
			ObjectDefinition d = resolve(cache.object(g.locId(i)));
			for (int[] part : mergedParts(d, g.locType(i), g.locOrientation(i)))
			{
				ObjectModels.modelsOf(d, part[0], needed);
				if (g.locType(i) == 9)
					ObjectModels.modelsOf(d, 10, needed);
			}
		}
		for (int i = 0; i < r.locCount(); i++)
		{
			ObjectDefinition d = resolve(cache.object(r.locId(i)));
			if (d == null)
				continue;
			int type = r.locType(i), rot = r.locOrientation(i);
			if (type == 2)
			{
				models.needed(d, 2, 4 + rot, needed);
				models.needed(d, 2, rot + 1 & 3, needed);
			}
			else if (type >= 4 && type <= 8)
				models.needed(d, 4, rot, needed);
			else if (type == 11)
				models.needed(d, 10, rot, needed);
			else
			{
				models.needed(d, type, rot, needed);
				if (type == 9)
					models.needed(d, 10, rot, needed);
			}
		}
		try
		{
			cache.prefetchModels(needed);
			return buildPrefetched(r, detail, reach, merged(r, mergeable));
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("stopped", e);
		}
		finally
		{
			cache.releaseModels(needed);
		}
	}

	private TerrainMesher.Built buildPrefetched(WorldTerrain.Region r, Detail detail, Reach reach,
		java.util.Map<Integer, ObjectModels.Lit> merged)
	{
		WorldTerrain world = cache.terrain;
		VertexSink sink = new VertexSink(1 << 14);
		VertexSink glass = new VertexSink(64, true), biasedSink = new VertexSink(64, true);
		java.util.List<Animated> animated = null;
		boolean landmarks = detail.landmarksOnly;
		java.util.HashSet<Long> seen = detail.cell > 0 ? new java.util.HashSet<>() : null;
		// one grid for the whole square, fine enough for the biggest object in it
		float cell = detail.cell > 0 ? detail.cellFor(biggest(r, detail, reach)) : 0;
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
			if (lowDetail && type == 22 && !shownInLowDetail(d))
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
			// as the client: the height of the middle of its footprint (the tile there, or the corner a footprint
			// even across has there)
			int hx0 = wx + (sx >> 1), hx1 = wx + (sx + 1 >> 1), hy0 = wy + (sy >> 1), hy1 = wy + (sy + 1 >> 1);
			int cy = world.height(hx0, hy0, plane, r) + world.height(hx1, hy0, plane, r) + world.height(hx0, hy1, plane, r)
				+ world.height(hx1, hy1, plane, r) >> 2;
			int contourMode = d.getContouredGround();
			boolean contour = contourMode >= 0;
			// played where it's drawn, unless it bends to the ground (then it's built still, as its first frame)
			ObjectAnimation anim = animate && !contour ? cache.animation(d.getAnimationID()) : null;
			if (anim != null && anim.frameCount() < 2)
				anim = null;

			for (int p = 0; p < parts.length; p++)
			{
				int[] part = parts[p];
				int modelType = part[0];
				// lit with its neighbours, or on its own
				boolean isMerged = merged.containsKey(i * 2 + p);
				ObjectModels.Lit m = isMerged ? merged.get(i * 2 + p) : models.get(d, part[0], part[1]);
				if (m == null && type == 9 && !isMerged)
				{
					modelType = 10;
					m = models.get(d, 10, part[1]);
				}
				if (m == null)
					continue;
				// stands out from afar: part of a building, big, or reaching high above the ground (counting the floor
				// it stands on: a flat canopy up on a building is still a roof)
				if (landmarks && !roof && !wall && plane == 0 && sx * sy < 4
					&& m.top + (world.height(wx, wy, 0, r) - cy) < LANDMARK_HEIGHT)
					continue;
				if (anim != null)
				{
					if (animated == null)
						animated = new java.util.ArrayList<>();
					// as the client: each starts somewhere in its animation, unless the object says they go together
					int phase = d.isRandomizeAnimStart() ? 0
						: Math.floorMod((wx * 31 + wy) * 0x9E3779B1 + plane * 0x85EBCA6B, anim.loopLength());
					long model = (long) d.getId() << 16 | modelType << 8 | part[1];
					animated.add(new Animated(model, anim, cx, cy, cz, (float) yaw, x * 128 + 64, y * 128 + 64, phase));
					// its frames built now, so the square shows it as soon as it's drawn
					// (built outside the map's lock: building reads models through the client thread, which takes them
					// from the map)
					if (!taken.contains(model) && !ready.containsKey(model))
						ready.putIfAbsent(model, frames(model));
					min = Math.min(min, (int) cy - m.top);
					max = Math.max(max, (int) cy);
					continue;
				}
				float sin = (float) Math.sin(yaw), cos = (float) Math.cos(yaw);
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
						float py = my + cy;
						if (contour)
						{
							// as the client: the whole model follows the ground under it, or (a model standing on it,
							// like a tree) only its foot, less and less up to the part of its height it gives
							int ground = groundAt(world, r, plane, Math.round(px), Math.round(pz));
							if (contourMode == 0)
								py = my + ground;
							else if (m.top > 0)
							{
								int up = (int) (((long) -Math.round(my) << 16) / m.top);
								if (up < contourMode)
									py = my + cy + (float) ((long) (contourMode - up) * (ground - cy) / contourMode);
							}
						}
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
						int colour = stamp(m.colours[f * 3 + k], anchor);
						int texture = m.textures == null ? 0 : m.textures[f * 3 + k];
						float u = texture == 0 ? 0 : m.uvs[f * 6 + k * 2], v = texture == 0 ? 0 : m.uvs[f * 6 + k * 2 + 1];
						int alpha = m.colours[f * 3 + k] >>> 24;
						int bias = m.biases == null ? 0 : m.biases[f] & 0xff;
						if (alpha > 0)
							glass.put(tri[k * 3], tri[k * 3 + 1], tri[k * 3 + 2], colour, u, v, texture, alpha, bias);
						else if (bias != 0)
							biasedSink.put(tri[k * 3], tri[k * 3 + 1], tri[k * 3 + 2], colour, u, v, texture, 0, bias);
						else
							sink.put(tri[k * 3], tri[k * 3 + 1], tri[k * 3 + 2], colour, u, v, texture);
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
		if (min > max)
			min = max = 0;
		TerrainMesher.Built built = sink.build(r, 0, min, max);
		if (glass.count > 0)
			built.translucent = glass.build(r, 0, min, max);
		if (biasedSink.count > 0)
			built.biased = biasedSink.build(r, 0, min, max);
		built.animated = animated;
		return built;
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
		int overCode = world.overlayColour(r.floorOverlay(i));
		int over = TerrainMesher.resolve(overCode, textureColours);
		boolean textured = WorldTerrain.isTextured(overCode);
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
		if (path != 0 && (over != WorldTerrain.VOID || hole))
		{
			shapedFloor(sink, r, i, x0, z0, ch, cl, path, under, hole ? WorldTerrain.VOID : over, overCode, textured);
			return (int) ch[0];
		}
		// a whole tile: its overlay (or nothing, for a hole), else its underlay
		boolean overlay = over != WorldTerrain.VOID || hole;
		int colour = overlay ? (hole ? WorldTerrain.VOID : over) : under;
		if (colour != WorldTerrain.VOID)
		{
			float[] us = {0, 1, 1, 0}, vs = {0, 0, 1, 1};
			float[] x = new float[4], y = new float[4], z = new float[4];
			int[] c = new int[4];
			int[] tex = overlay && textured ? new int[4] : null;
			for (int k = 0; k < 4; k++)
			{
				x[k] = x0 + us[k] * 128;
				z[k] = z0 + vs[k] * 128;
				y[k] = ch[k];
				c[k] = stamp(TerrainMesher.shade(colour, cl[k]), r.floorX(i) << 6 | r.floorY(i));
				if (tex != null)
					tex[k] = TerrainMesher.texture(overCode, cl[k]);
			}
			sink.quad(x, y, z, c, us, vs, tex);
			// and the other side
			sink.quad(new float[]{x[0], x[3], x[2], x[1]}, new float[]{y[0], y[3], y[2], y[1]},
				new float[]{z[0], z[3], z[2], z[1]}, new int[]{c[0], c[3], c[2], c[1]},
				new float[]{us[0], us[3], us[2], us[1]}, new float[]{vs[0], vs[3], vs[2], vs[1]},
				tex == null ? null : new int[]{tex[0], tex[3], tex[2], tex[1]});
		}
		return (int) ch[0];
	}

	/**
	 * A part-overlay floor tile, as the client builds it (its shape's triangles, as the ground's: see
	 * {@link TileModels}), both sides.
	 */
	private void shapedFloor(VertexSink sink, WorldTerrain.Region r, int i, float x0, float z0, float[] ch, double[] cl,
		int path, int under, int over, int overCode, boolean textured)
	{
		int shape = TileModels.shape(path), rotation = r.floorRotation(i);
		int[] points = TileModels.points(shape, rotation), faces = TileModels.faces(shape, rotation);
		int[] height = new int[4], underColour = new int[4], overColour = new int[4];
		for (int k = 0; k < 4; k++)
		{
			height[k] = (int) ch[k];
			underColour[k] = under == WorldTerrain.VOID ? 0 : TerrainMesher.shade(under, cl[k]);
			overColour[k] = over == WorldTerrain.VOID ? 0 : TerrainMesher.shade(over, cl[k]);
		}
		int anchor = r.floorX(i) << 6 | r.floorY(i);
		float[] x = new float[3], y = new float[3], z = new float[3], u = new float[3], v = new float[3];
		int[] c = new int[3], t = new int[3];
		for (int f = 0; f < faces.length; f += 4)
		{
			boolean overlay = faces[f] == 1;
			if (overlay ? over == WorldTerrain.VOID : under == WorldTerrain.VOID)
				continue;
			for (int k = 0; k < 3; k++)
			{
				int code = points[faces[f + 1 + k]];
				u[k] = TileModels.east(code);
				v[k] = TileModels.north(code);
				x[k] = x0 + u[k] * 128;
				z[k] = z0 + v[k] * 128;
				y[k] = TileModels.of(code, height);
				c[k] = stamp(TileModels.of(code, overlay ? overColour : underColour), anchor);
				t[k] = overlay && textured ? TerrainMesher.texture(overCode, TileModels.light(code, cl)) : 0;
			}
			for (int k = 0; k < 3; k++)
				sink.put(x[k], y[k], z[k], c[k], u[k], v[k], t[k]);
			// and the other side
			for (int k = 2; k >= 0; k--)
				sink.put(x[k], y[k], z[k], c[k], u[k], v[k], t[k]);
		}
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

	/**
	 * The plane's height at a point given relative to the square's south-west corner (local units), across the tile
	 * there as the client works it out (in whole numbers).
	 */
	private static int groundAt(WorldTerrain world, WorldTerrain.Region r, int plane, int px, int pz)
	{
		int tx = Math.floorDiv(px, 128), tz = Math.floorDiv(pz, 128);
		int fx = px & 127, fz = pz & 127;
		int wx = r.baseX() + tx, wy = r.baseY() + tz;
		int south = world.height(wx, wy, plane, r) * (128 - fx) + world.height(wx + 1, wy, plane, r) * fx >> 7;
		int north = world.height(wx, wy + 1, plane, r) * (128 - fx) + fx * world.height(wx + 1, wy + 1, plane, r) >> 7;
		return south * (128 - fz) + north * fz >> 7;
	}
}
