package com.extendedrenderdistance.world;

/**
 * The client's shaped tiles (a tile part overlay, part underlay), as it builds them: each shape's points (codes 1
 * to 16: the corners, the edges' midpoints, and points a quarter in) and its triangles, each overlay or underlay,
 * turned by the tile's rotation. The tables are the client's own.
 */
final class TileModels
{
	/** Each shape's points, by code. */
	private static final int[][] POINTS = {{1, 3, 5, 7}, {1, 3, 5, 7}, {1, 3, 5, 7}, {1, 3, 5, 7, 6}, {1, 3, 5, 7, 6},
		{1, 3, 5, 7, 6}, {1, 3, 5, 7, 6}, {1, 3, 5, 7, 2, 6}, {1, 3, 5, 7, 2, 8}, {1, 3, 5, 7, 2, 8},
		{1, 3, 5, 7, 11, 12}, {1, 3, 5, 7, 11, 12}, {1, 3, 5, 7, 13, 14}};

	/** Each shape's triangles: underlay (0) or overlay (1), then three of its points. */
	private static final int[][] FACES = {{0, 1, 2, 3, 0, 0, 1, 3}, {1, 1, 2, 3, 1, 0, 1, 3}, {0, 1, 2, 3, 1, 0, 1, 3},
		{0, 0, 1, 2, 0, 0, 2, 4, 1, 0, 4, 3}, {0, 0, 1, 4, 0, 0, 4, 3, 1, 1, 2, 4}, {0, 0, 4, 3, 1, 0, 1, 2, 1, 0, 2, 4},
		{0, 1, 2, 4, 1, 0, 1, 4, 1, 0, 4, 3}, {0, 4, 1, 2, 0, 4, 2, 5, 1, 0, 4, 5, 1, 0, 5, 3},
		{0, 4, 1, 2, 0, 4, 2, 3, 0, 4, 3, 5, 1, 0, 4, 5}, {0, 0, 4, 5, 1, 4, 1, 2, 1, 4, 2, 3, 1, 4, 3, 5},
		{0, 0, 1, 5, 0, 1, 4, 5, 0, 1, 2, 4, 1, 0, 5, 3, 1, 5, 4, 3, 1, 4, 2, 3},
		{1, 0, 1, 5, 1, 1, 4, 5, 1, 1, 2, 4, 0, 0, 5, 3, 0, 5, 4, 3, 0, 4, 2, 3},
		{1, 0, 5, 4, 1, 0, 1, 5, 0, 0, 4, 3, 0, 4, 5, 3, 0, 5, 2, 3, 0, 1, 2, 5}};

	/**
	 * Per point code (1 to 16): where it is across the tile (east, north: 0, 1/4, 1/2, 3/4 or 1, in quarters), and
	 * the corners (south-west 0, south-east 1, north-east 2, north-west 3) whose height and colour it takes (two:
	 * their average).
	 */
	private static final int[][] CODE = {null, {0, 0, 0, 0}, {2, 0, 0, 1}, {4, 0, 1, 1}, {4, 2, 1, 2}, {4, 4, 2, 2},
		{2, 4, 2, 3}, {0, 4, 3, 3}, {0, 2, 3, 0}, {2, 1, 0, 1}, {3, 2, 1, 2}, {2, 3, 2, 3}, {1, 2, 3, 0}, {1, 1, 0, 0},
		{3, 1, 1, 1}, {3, 3, 2, 2}, {1, 3, 3, 3}};

	private TileModels()
	{
	}

	/** The client's shape for an overlay path (1 to 11; 0 is a whole tile of overlay). */
	static int shape(int path)
	{
		return path + 1;
	}

	/** A shape's points turned by {@code rotation}: their codes, 1 to 16. */
	static int[] points(int shape, int rotation)
	{
		int[] p = POINTS[shape].clone();
		for (int i = 0; i < p.length; i++)
		{
			int c = p[i];
			if ((c & 1) == 0 && c <= 8)
				c = (c - rotation - rotation - 1 & 7) + 1;
			if (c > 8 && c <= 12)
				c = (c - 9 - rotation & 3) + 9;
			if (c > 12 && c <= 16)
				c = (c - 13 - rotation & 3) + 13;
			p[i] = c;
		}
		return p;
	}

	/** A shape's triangles turned by {@code rotation}: overlay (1) or underlay (0), then three of its points. */
	static int[] faces(int shape, int rotation)
	{
		int[] f = FACES[shape].clone();
		for (int i = 0; i < f.length; i += 4)
		{
			for (int k = 1; k <= 3; k++)
			{
				if (f[i + k] < 4)
					f[i + k] = f[i + k] - rotation & 3;
			}
		}
		return f;
	}

	/** Where a point is across the tile, east (0 to 1). */
	static float east(int code)
	{
		return CODE[code][0] / 4f;
	}

	/** Where a point is across the tile, north (0 to 1). */
	static float north(int code)
	{
		return CODE[code][1] / 4f;
	}

	/** A point's value from the corners' (as the client takes it: one corner's, or two averaged). */
	static int of(int code, int[] corners)
	{
		int a = corners[CODE[code][2]], b = corners[CODE[code][3]];
		return a == b && CODE[code][2] == CODE[code][3] ? a : a + b >> 1;
	}

	/** A point's light from the corners' (one corner's, or two averaged). */
	static double light(int code, double[] corners)
	{
		return (corners[CODE[code][2]] + corners[CODE[code][3]]) / 2;
	}
}
