package com.horizon.world;

/**
 * The client's overlay tile shapes as 4 x 4 masks (which sixteenths of a tile the overlay covers), per shape and
 * rotation. Generated exactly as RuneLite's cache MapImageDumper does (BSD-2, Copyright (c) 2017, Adam); the
 * generators below are copied from it unchanged. Mask cells run west to east within each row, rows north to south.
 */
final class TileShapes
{
	private static final int MAP_SCALE = 4;
	private static final byte[][][] TILE_SHAPE_2D = new byte[8][4][];

	static
	{
		genTileShapes1();
		genTileShapes2();
		genTileShapes3();
		genTileShapes4();
		genTileShapes5();
		genTileShapes6();
		genTileShapes7();
		genTileShapes8();
	}

	private TileShapes()
	{
	}

	/**
	 * The mask for an overlay path (the map's overlay path, 1 to 12; 0 is a full tile) and rotation (0 to 3): true
	 * where the overlay covers cell [row * 4 + col] (row 0 the north edge, col 0 the west edge).
	 */
	static boolean[] mask(int path, int rotation)
	{
		int shape = path;
		if (shape == 9)
			rotation = rotation + 1 & 3;
		if (shape == 10 || shape == 11)
			rotation = rotation + 3 & 3;
		shape = shape != 9 && shape != 10 ? (shape == 11 ? 8 : shape) : 1;
		boolean[] out = new boolean[16];
		if (shape < 1 || shape > 8)
		{
			java.util.Arrays.fill(out, true);
			return out;
		}
		byte[] m = TILE_SHAPE_2D[shape - 1][rotation & 3];
		for (int i = 0; i < 16; i++)
			out[i] = m[i] != 0;
		return out;
	}

	private static void genTileShapes1()
	{
		byte[] shape = new byte[MAP_SCALE * MAP_SCALE];
		int index = 0;

		int x;
		int y;
		for (x = 0; x < MAP_SCALE; ++x)
		{
			for (y = 0; y < MAP_SCALE; ++y)
			{
				if (y <= x)
				{
					shape[index] = -1;
				}

				++index;
			}
		}

		TILE_SHAPE_2D[0][0] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		index = 0;

		for (x = MAP_SCALE - 1; x >= 0; --x)
		{
			for (y = 0; y < MAP_SCALE; ++y)
			{
				if (y <= x)
				{
					shape[index] = -1;
				}

				++index;
			}
		}

		TILE_SHAPE_2D[0][1] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		index = 0;

		for (x = 0; x < MAP_SCALE; ++x)
		{
			for (y = 0; y < MAP_SCALE; ++y)
			{
				if (y >= x)
				{
					shape[index] = -1;
				}

				++index;
			}
		}

		TILE_SHAPE_2D[0][2] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		index = 0;

		for (x = MAP_SCALE - 1; x >= 0; --x)
		{
			for (y = 0; y < MAP_SCALE; ++y)
			{
				if (y >= x)
				{
					shape[index] = -1;
				}

				++index;
			}
		}

		TILE_SHAPE_2D[0][3] = shape;
	}

	private static void genTileShapes2()
	{
		byte[] shape = new byte[MAP_SCALE * MAP_SCALE];
		int var2 = 0;

		int var3;
		int var4;
		for (var3 = MAP_SCALE - 1; var3 >= 0; --var3)
		{
			for (var4 = 0; var4 < MAP_SCALE; ++var4)
			{
				if (var4 <= var3 >> 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[1][0] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = 0; var3 < MAP_SCALE; ++var3)
		{
			for (var4 = 0; var4 < MAP_SCALE; ++var4)
			{
				if (var2 >= 0 && var2 < shape.length)
				{
					if (var4 >= var3 << 1)
					{
						shape[var2] = -1;
					}

					++var2;
				}
				else
				{
					++var2;
				}
			}
		}

		TILE_SHAPE_2D[1][1] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = 0; var3 < MAP_SCALE; ++var3)
		{
			for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
			{
				if (var4 <= var3 >> 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[1][2] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = MAP_SCALE - 1; var3 >= 0; --var3)
		{
			for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
			{
				if (var4 >= var3 << 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[1][3] = shape;
	}

	private static void genTileShapes3()
	{
		byte[] shape = new byte[MAP_SCALE * MAP_SCALE];
		int var2 = 0;

		int var3;
		int var4;
		for (var3 = MAP_SCALE - 1; var3 >= 0; --var3)
		{
			for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
			{
				if (var4 <= var3 >> 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[2][0] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = MAP_SCALE - 1; var3 >= 0; --var3)
		{
			for (var4 = 0; var4 < MAP_SCALE; ++var4)
			{
				if (var4 >= var3 << 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[2][1] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = 0; var3 < MAP_SCALE; ++var3)
		{
			for (var4 = 0; var4 < MAP_SCALE; ++var4)
			{
				if (var4 <= var3 >> 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[2][2] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = 0; var3 < MAP_SCALE; ++var3)
		{
			for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
			{
				if (var4 >= var3 << 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[2][3] = shape;
	}

	private static void genTileShapes4()
	{
		byte[] shape = new byte[MAP_SCALE * MAP_SCALE];
		int var2 = 0;

		int var3;
		int var4;
		for (var3 = MAP_SCALE - 1; var3 >= 0; --var3)
		{
			for (var4 = 0; var4 < MAP_SCALE; ++var4)
			{
				if (var4 >= var3 >> 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[3][0] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = 0; var3 < MAP_SCALE; ++var3)
		{
			for (var4 = 0; var4 < MAP_SCALE; ++var4)
			{
				if (var4 <= var3 << 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[3][1] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = 0; var3 < MAP_SCALE; ++var3)
		{
			for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
			{
				if (var4 >= var3 >> 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[3][2] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = MAP_SCALE - 1; var3 >= 0; --var3)
		{
			for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
			{
				if (var4 <= var3 << 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[3][3] = shape;
	}

	private static void genTileShapes5()
	{
		byte[] shape = new byte[MAP_SCALE * MAP_SCALE];
		int var2 = 0;

		int var3;
		int var4;
		for (var3 = MAP_SCALE - 1; var3 >= 0; --var3)
		{
			for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
			{
				if (var4 >= var3 >> 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[4][0] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = MAP_SCALE - 1; var3 >= 0; --var3)
		{
			for (var4 = 0; var4 < MAP_SCALE; ++var4)
			{
				if (var4 <= var3 << 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[4][1] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = 0; var3 < MAP_SCALE; ++var3)
		{
			for (var4 = 0; var4 < MAP_SCALE; ++var4)
			{
				if (var4 >= var3 >> 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[4][2] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var2 = 0;

		for (var3 = 0; var3 < MAP_SCALE; ++var3)
		{
			for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
			{
				if (var4 <= var3 << 1)
				{
					shape[var2] = -1;
				}

				++var2;
			}
		}

		TILE_SHAPE_2D[4][3] = shape;
	}

	private static void genTileShapes6()
	{
		byte[] shape = new byte[MAP_SCALE * MAP_SCALE];
		boolean var2 = false;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		int var3 = 0;

		int var4;
		int var5;
		for (var4 = 0; var4 < MAP_SCALE; ++var4)
		{
			for (var5 = 0; var5 < MAP_SCALE; ++var5)
			{
				if (var5 <= MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[5][0] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var3 = 0;

		for (var4 = 0; var4 < MAP_SCALE; ++var4)
		{
			for (var5 = 0; var5 < MAP_SCALE; ++var5)
			{
				if (var4 <= MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[5][1] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var3 = 0;

		for (var4 = 0; var4 < MAP_SCALE; ++var4)
		{
			for (var5 = 0; var5 < MAP_SCALE; ++var5)
			{
				if (var5 >= MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[5][2] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var3 = 0;

		for (var4 = 0; var4 < MAP_SCALE; ++var4)
		{
			for (var5 = 0; var5 < MAP_SCALE; ++var5)
			{
				if (var4 >= MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[5][3] = shape;
	}

	private static void genTileShapes7()
	{
		byte[] shape = new byte[MAP_SCALE * MAP_SCALE];
		boolean var2 = false;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		int var3 = 0;

		int var4;
		int var5;
		for (var4 = 0; var4 < MAP_SCALE; ++var4)
		{
			for (var5 = 0; var5 < MAP_SCALE; ++var5)
			{
				if (var5 <= var4 - MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[6][0] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var3 = 0;

		for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
		{
			for (var5 = 0; var5 < MAP_SCALE; ++var5)
			{
				if (var5 <= var4 - MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[6][1] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var3 = 0;

		for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
		{
			for (var5 = MAP_SCALE - 1; var5 >= 0; --var5)
			{
				if (var5 <= var4 - MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[6][2] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var3 = 0;

		for (var4 = 0; var4 < MAP_SCALE; ++var4)
		{
			for (var5 = MAP_SCALE - 1; var5 >= 0; --var5)
			{
				if (var5 <= var4 - MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[6][3] = shape;
	}

	private static void genTileShapes8()
	{
		byte[] shape = new byte[MAP_SCALE * MAP_SCALE];
		boolean var2 = false;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		int var3 = 0;

		int var4;
		int var5;
		for (var4 = 0; var4 < MAP_SCALE; ++var4)
		{
			for (var5 = 0; var5 < MAP_SCALE; ++var5)
			{
				if (var5 >= var4 - MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[7][0] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var3 = 0;

		for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
		{
			for (var5 = 0; var5 < MAP_SCALE; ++var5)
			{
				if (var5 >= var4 - MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[7][1] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var3 = 0;

		for (var4 = MAP_SCALE - 1; var4 >= 0; --var4)
		{
			for (var5 = MAP_SCALE - 1; var5 >= 0; --var5)
			{
				if (var5 >= var4 - MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[7][2] = shape;
		shape = new byte[MAP_SCALE * MAP_SCALE];
		var3 = 0;

		for (var4 = 0; var4 < MAP_SCALE; ++var4)
		{
			for (var5 = MAP_SCALE - 1; var5 >= 0; --var5)
			{
				if (var5 >= var4 - MAP_SCALE / 2)
				{
					shape[var3] = -1;
				}

				++var3;
			}
		}

		TILE_SHAPE_2D[7][3] = shape;
	}
}
