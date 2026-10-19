package com.horizon.gl;

/**
 * Column-major 4x4 matrices, the same as RuneLite's GPU plugin builds its projection with (so the world lines up
 * with the client's own picking and overlays).
 */
final class Mat4
{
	private Mat4()
	{
	}

	static float[] scale(float sx, float sy, float sz)
	{
		return new float[]{sx, 0, 0, 0, 0, sy, 0, 0, 0, 0, sz, 0, 0, 0, 0, 1};
	}

	static float[] rotateX(float rx)
	{
		float s = (float) Math.sin(rx), c = (float) Math.cos(rx);
		return new float[]{1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1};
	}

	static float[] rotateY(float ry)
	{
		float s = (float) Math.sin(ry), c = (float) Math.cos(ry);
		return new float[]{c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1};
	}

	/** The client's perspective: viewport width and height, near plane n, infinitely far, depth reversed. */
	static float[] projection(float w, float h, float n)
	{
		return new float[]{2 / w, 0, 0, 0, 0, -2 / h, 0, 0, 0, 0, 0, 1, 0, 0, 2 * n, 0};
	}

	/** a = a * b. */
	static void mul(float[] a, float[] b)
	{
		float[] r = new float[16];
		for (int col = 0; col < 4; col++)
		{
			for (int row = 0; row < 4; row++)
			{
				float s = 0;
				for (int k = 0; k < 4; k++)
					s += a[k * 4 + row] * b[col * 4 + k];
				r[col * 4 + row] = s;
			}
		}
		System.arraycopy(r, 0, a, 0, 16);
	}

	/** m * (x, y, z, 1): {x, y, z, w}. */
	static void transform(float[] m, float x, float y, float z, float[] out)
	{
		for (int row = 0; row < 4; row++)
			out[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row];
	}
}
