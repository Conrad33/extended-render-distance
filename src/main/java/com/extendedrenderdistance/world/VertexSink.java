package com.extendedrenderdistance.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A growing buffer of mesh vertices: 3 floats, a packed colour int, the texture coordinates (2 floats) and the
 * texture ({@link #texture}, 0 for none) each.
 */
final class VertexSink
{
	static final int STRIDE = 28;
	/** Set in a vertex's texture for texture coordinates that run on across several tiles, to be repeated. */
	static final int REPEAT = 1 << 24;

	ByteBuffer buf;
	int count;

	VertexSink(int vertices)
	{
		buf = ByteBuffer.allocateDirect(Math.max(64, vertices) * STRIDE).order(ByteOrder.nativeOrder());
	}

	/**
	 * A vertex's texture: the texture id plus one in bits 0 to 15, the light the client gives a textured face (its
	 * lightness, 2 to 126) in 16 to 23. 0 for an untextured vertex.
	 */
	static int texture(int textureId, int light)
	{
		return textureId + 1 | Math.max(2, Math.min(126, light)) << 16;
	}

	void put(float x, float y, float z, int colour)
	{
		put(x, y, z, colour, 0, 0, 0);
	}

	void put(float x, float y, float z, int colour, float u, float v, int texture)
	{
		if (buf.remaining() < STRIDE)
		{
			ByteBuffer n = ByteBuffer.allocateDirect(buf.capacity() * 2).order(ByteOrder.nativeOrder());
			buf.flip();
			n.put(buf);
			buf = n;
		}
		buf.putFloat(x).putFloat(y).putFloat(z).putInt(colour).putFloat(u).putFloat(v).putInt(texture);
		count++;
	}

	/** Two triangles: a-b-c and a-c-d. */
	void quad(float[] x, float[] y, float[] z, int[] c)
	{
		quad(x, y, z, c, null, null, null);
	}

	/** Two triangles: a-b-c and a-c-d, textured when {@code t} isn't null. */
	void quad(float[] x, float[] y, float[] z, int[] c, float[] u, float[] v, int[] t)
	{
		int[] order = {0, 1, 2, 0, 2, 3};
		for (int k : order)
		{
			if (t == null)
				put(x[k], y[k], z[k], c[k]);
			else
				put(x[k], y[k], z[k], c[k], u[k], v[k], t[k]);
		}
	}

	/** The vertices, ready to read. */
	ByteBuffer finish()
	{
		buf.flip();
		return buf;
	}
}
