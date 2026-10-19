package com.extendedrenderdistance.world;

import java.nio.ByteBuffer;

/**
 * Builds one map square's ground as triangles at a level of detail, leaving out tiles outside the {@link Reach}. At full detail (step 1) it follows the
 * client: each tile's underlay colour is blended across its corners from the tiles there, overlays (paths, water,
 * floors) cover the whole tile or the part their shape says, and every corner is lit from the slope there (the
 * client's terrain light, from the west-south-west). Coarser levels sample a grid every {@code step} tiles with each
 * corner coloured by the tile there. Edges get skirts hanging down, so neighbours at a coarser level leave no cracks.
 * <p>
 * Vertices are relative to the square's south-west corner in the client's scene axes (x east, y down, z north; 128
 * to a tile), as {@link VertexSink} lays them out ({@link #STRIDE} bytes). Textured overlays (water, paths) carry
 * their texture, mapped once across each tile as the client maps it; coarse quads repeat theirs tile by tile. Any
 * thread.
 */
public final class TerrainMesher
{
	public static final int STRIDE = VertexSink.STRIDE;
	private static final float TILE = 128f;

	private TerrainMesher()
	{
	}

	/**
	 * A built mesh: its distinct vertices, the triangle corners' indices into them (16-bit when they fit), and the
	 * height range (client heights).
	 */
	public static final class Built
	{
		public final WorldTerrain.Region region;
		public final int step;
		public final ByteBuffer vertices;
		public final int vertexCount;
		public final ByteBuffer indices;
		/** Triangle corners: three per triangle. */
		public final int count;
		public final boolean shortIndices;
		public final int minHeight, maxHeight;

		public Built(WorldTerrain.Region region, int step, ByteBuffer vertices, int vertexCount, ByteBuffer indices,
			int count, boolean shortIndices, int minHeight, int maxHeight)
		{
			this.region = region;
			this.step = step;
			this.vertices = vertices;
			this.vertexCount = vertexCount;
			this.indices = indices;
			this.count = count;
			this.shortIndices = shortIndices;
			this.minHeight = minHeight;
			this.maxHeight = maxHeight;
		}
	}

	/**
	 * @param textureColours the client's colour for each texture (packed HSL), for textured tiles like water
	 */
	public static Built build(WorldTerrain world, WorldTerrain.Region r, int step, int[] textureColours, Reach reach)
	{
		int n = 64 / step;
		int bx = r.baseX(), by = r.baseY();
		int[] h = new int[(n + 1) * (n + 1)];
		double[] lit = new double[(n + 1) * (n + 1)];
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		for (int i = 0; i <= n; i++)
		{
			for (int j = 0; j <= n; j++)
			{
				int wx = bx + i * step, wy = by + j * step;
				int v = world.height(wx, wy, r);
				h[i * (n + 1) + j] = v;
				min = Math.min(min, v);
				max = Math.max(max, v);
				int dx = world.height(wx + step, wy, r) - world.height(wx - step, wy, r);
				int dz = world.height(wx, wy + step, r) - world.height(wx, wy - step, r);
				lit[i * (n + 1) + j] = light(dx, dz, step);
			}
		}

		VertexSink sink = new VertexSink(n * n * 6 + 4 * n * 6);
		// the colour standing for each grid quad (VOID where nothing is drawn), for the coarse levels and skirts
		int[] quadColour = new int[n * n];
		for (int i = 0; i < n; i++)
		{
			for (int j = 0; j < n; j++)
				quadColour[i * n + j] = reach != null && !reach.has(bx + i * step, by + j * step) ? WorldTerrain.VOID
					: resolve(world.colour(bx + i * step, by + j * step), textureColours);
		}

		if (step == 1)
		{
			for (int i = 0; i < 64; i++)
			{
				for (int j = 0; j < 64; j++)
				{
					if (reach == null || reach.has(bx + i, by + j))
						tile(world, sink, bx, by, i, j, h, lit, textureColours);
				}
			}
		}
		else
			coarse(world, sink, bx, by, n, step, h, lit, quadColour, textureColours);

		float t = step * TILE;
		float drop = 2 * t;
		for (int k = 0; k < n; k++)
		{
			skirt(sink, h, lit, quadColour, n, t, drop, 0, k, 0, k + 1, 0, k);
			skirt(sink, h, lit, quadColour, n, t, drop, n, k, n, k + 1, n - 1, k);
			skirt(sink, h, lit, quadColour, n, t, drop, k, 0, k + 1, 0, k, 0);
			skirt(sink, h, lit, quadColour, n, t, drop, k, n, k + 1, n, k, n - 1);
		}
		return sink.build(r, step, min, max);
	}

	/** A coarse grid: each corner coloured by the tile there (or the quad's own colour where that tile is void). */
	private static void coarse(WorldTerrain world, VertexSink sink, int bx, int by, int n, int step, int[] h, double[] lit,
		int[] quadColour, int[] textureColours)
	{
		int[] vc = new int[(n + 1) * (n + 1)];
		for (int i = 0; i <= n; i++)
		{
			for (int j = 0; j <= n; j++)
				vc[i * (n + 1) + j] = resolve(world.colour(bx + i * step, by + j * step), textureColours);
		}
		float t = step * TILE;
		float[] x = new float[4], y = new float[4], z = new float[4];
		int[] c = new int[4];
		float[] u = new float[4], v = new float[4];
		int[] tex = new int[4];
		for (int i = 0; i < n; i++)
		{
			for (int j = 0; j < n; j++)
			{
				int own = quadColour[i * n + j];
				if (own == WorldTerrain.VOID)
					continue;
				int code = world.colour(bx + i * step, by + j * step);
				boolean textured = WorldTerrain.isTextured(code);
				int[] corner = {i * (n + 1) + j, (i + 1) * (n + 1) + j, (i + 1) * (n + 1) + j + 1, i * (n + 1) + j + 1};
				for (int k = 0; k < 4; k++)
				{
					int ci = corner[k];
					x[k] = (i + (k == 1 || k == 2 ? 1 : 0)) * t;
					z[k] = (j + (k >= 2 ? 1 : 0)) * t;
					y[k] = h[ci];
					int col = vc[ci] == WorldTerrain.VOID ? own : vc[ci];
					c[k] = shade(col, lit[ci]);
					// in tiles, so the texture repeats once a tile like the client's
					u[k] = x[k] / TILE;
					v[k] = z[k] / TILE;
					tex[k] = textured ? texture(code, lit[ci]) | VertexSink.REPEAT : 0;
				}
				sink.quad(x, y, z, c, u, v, tex);
			}
		}
	}

	/** One tile at full detail. */
	private static void tile(WorldTerrain world, VertexSink sink, int bx, int by, int i, int j, int[] h, double[] lit,
		int[] textureColours)
	{
		int wx = bx + i, wy = by + j;
		int under = world.underlay(wx, wy);
		int over = world.overlay(wx, wy);
		int path = world.overlayPath(wx, wy);
		if (over == WorldTerrain.VOID && under == WorldTerrain.VOID)
			return;
		// corners: south-west, south-east, north-east, north-west
		int[] ci = {i * 65 + j, (i + 1) * 65 + j, (i + 1) * 65 + j + 1, i * 65 + j + 1};
		int[][] cornerTile = {{wx, wy}, {wx + 1, wy}, {wx + 1, wy + 1}, {wx, wy + 1}};
		float[] ch = new float[4];
		double[] cl = new double[4];
		int[] cu = new int[4];
		for (int k = 0; k < 4; k++)
		{
			ch[k] = h[ci[k]];
			cl[k] = lit[ci[k]];
			int u = world.underlay(cornerTile[k][0], cornerTile[k][1]);
			cu[k] = u == WorldTerrain.VOID ? under : u;
		}
		float x0 = i * TILE, z0 = j * TILE;

		if (over == WorldTerrain.VOID || path == 0)
		{
			float[] x = {x0, x0 + TILE, x0 + TILE, x0}, z = {z0, z0, z0 + TILE, z0 + TILE};
			int[] c = new int[4];
			int[] tex = null;
			if (over == WorldTerrain.VOID)
			{
				for (int k = 0; k < 4; k++)
					c[k] = shade(cu[k], cl[k]);
			}
			else
			{
				if (over == WorldTerrain.HOLE)
					return;
				int oc = resolve(over, textureColours);
				if (WorldTerrain.isTextured(over))
					tex = new int[4];
				for (int k = 0; k < 4; k++)
				{
					c[k] = shade(oc, cl[k]);
					if (tex != null)
						tex[k] = texture(over, cl[k]);
				}
			}
			sink.quad(x, ch, z, c, CORNER_U, CORNER_V, tex);
			return;
		}

		// part overlay: sixteen cells, each overlay or underlay as the shape says
		boolean[] mask = TileShapes.mask(path, world.overlayRotation(wx, wy));
		int oc = over == WorldTerrain.HOLE ? WorldTerrain.VOID : resolve(over, textureColours);
		boolean textured = WorldTerrain.isTextured(over);
		float[] x = new float[4], y = new float[4], z = new float[4];
		int[] c = new int[4];
		float[] tu = new float[4], tv = new float[4];
		int[] tex = new int[4];
		for (int row = 0; row < 4; row++)
		{
			for (int col = 0; col < 4; col++)
			{
				boolean overlay = mask[row * 4 + col];
				if (overlay ? oc == WorldTerrain.VOID : under == WorldTerrain.VOID)
					continue;
				// fractions across the tile: east u, north v (row 0 is the north edge)
				double[] us = {col / 4.0, (col + 1) / 4.0, (col + 1) / 4.0, col / 4.0};
				double[] vs = {1 - (row + 1) / 4.0, 1 - (row + 1) / 4.0, 1 - row / 4.0, 1 - row / 4.0};
				for (int k = 0; k < 4; k++)
				{
					double u = us[k], v = vs[k];
					x[k] = x0 + (float) (u * TILE);
					z[k] = z0 + (float) (v * TILE);
					y[k] = (float) bilinear(ch[0], ch[1], ch[2], ch[3], u, v);
					double l = bilinear(cl[0], cl[1], cl[2], cl[3], u, v);
					c[k] = shade(overlay ? oc : blendHsl(cu, u, v), l);
					tu[k] = (float) u;
					tv[k] = (float) v;
					tex[k] = overlay && textured ? texture(over, l) : 0;
				}
				sink.quad(x, y, z, c, tu, tv, tex);
			}
		}
	}

	/** Bilinear across a tile's corners (south-west, south-east, north-east, north-west). */
	private static double bilinear(double sw, double se, double ne, double nw, double u, double v)
	{
		double south = sw + (se - sw) * u, north = nw + (ne - nw) * u;
		return south + (north - south) * v;
	}

	/** Packed HSL blended across the corners, component by component. */
	private static int blendHsl(int[] c, double u, double v)
	{
		double hue = bilinear(c[0] >> 10 & 63, c[1] >> 10 & 63, c[2] >> 10 & 63, c[3] >> 10 & 63, u, v);
		double sat = bilinear(c[0] >> 7 & 7, c[1] >> 7 & 7, c[2] >> 7 & 7, c[3] >> 7 & 7, u, v);
		double lum = bilinear(c[0] & 127, c[1] & 127, c[2] & 127, c[3] & 127, u, v);
		return (int) Math.round(hue) << 10 | (int) Math.round(sat) << 7 | (int) Math.round(lum);
	}

	/** A hanging quad under the grid edge from corner (i0, j0) to (i1, j1), coloured as quad (qi, qj). */
	private static void skirt(VertexSink sink, int[] h, double[] lit, int[] colour, int n, float t, float drop,
		int i0, int j0, int i1, int j1, int qi, int qj)
	{
		int c = colour[qi * n + qj];
		if (c == WorldTerrain.VOID)
			return;
		int a = i0 * (n + 1) + j0, b = i1 * (n + 1) + j1;
		float[] x = {i0 * t, i1 * t, i1 * t, i0 * t}, z = {j0 * t, j1 * t, j1 * t, j0 * t};
		float[] y = {h[a], h[b], h[b] + drop, h[a] + drop};
		int ca = shade(c, lit[a]), cb = shade(c, lit[b]);
		sink.quad(x, y, z, new int[]{ca, cb, cb, ca});
	}

	/**
	 * Light at a corner as a lightness multiplier (the client's light over 128): the client's terrain light, in its
	 * whole-number steps, from the height differences across 2 * step tiles and the light from (-50, -10, -50): 84 on
	 * the flat.
	 */
	static double light(int dx, int dz, int step)
	{
		int span = 256 * step;
		int len = (int) Math.sqrt((double) dx * dx + (double) span * span + (double) dz * dz);
		int nx = (dx << 8) / len, ny = (span << 8) / len, nz = (dz << 8) / len;
		// 213 is the light's length (71) times 768 over 256
		return (96 + (-50 * nx - 10 * ny - 50 * nz) / 213) / 128.0;
	}

	/** The client's lightness times the light, rounded down as the client does. */
	static int shade(int hsl, double light)
	{
		int l = (int) Math.floor((hsl & 127) * light + 1e-9);
		return (hsl & ~127) | Math.max(2, Math.min(126, l));
	}

	/** A tile's corners' texture coordinates (south-west, south-east, north-east, north-west), as the client's. */
	private static final float[] CORNER_U = {0, 1, 1, 0}, CORNER_V = {0, 0, 1, 1};

	/** A textured colour code's texture, lit by the terrain light at the vertex. */
	static int texture(int code, double light)
	{
		return VertexSink.texture(WorldTerrain.texture(code), (int) Math.round(128 * light));
	}

	static int resolve(int code, int[] textureColours)
	{
		if (code < 0 || !WorldTerrain.isTextured(code))
			return code < 0 ? WorldTerrain.VOID : code;
		int t = WorldTerrain.texture(code);
		return textureColours != null && t < textureColours.length ? textureColours[t] : 0x2b4a;
	}
}
