package com.extendedrenderdistance.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A growing buffer of mesh vertices: 3 floats then a packed colour int each. */
final class VertexSink
{
	static final int STRIDE = 16;

	ByteBuffer buf;
	int count;

	VertexSink(int vertices)
	{
		buf = ByteBuffer.allocateDirect(Math.max(64, vertices) * STRIDE).order(ByteOrder.nativeOrder());
	}

	void put(float x, float y, float z, int colour)
	{
		if (buf.remaining() < STRIDE)
		{
			ByteBuffer n = ByteBuffer.allocateDirect(buf.capacity() * 2).order(ByteOrder.nativeOrder());
			buf.flip();
			n.put(buf);
			buf = n;
		}
		buf.putFloat(x).putFloat(y).putFloat(z).putInt(colour);
		count++;
	}

	/** Two triangles: a-b-c and a-c-d. */
	void quad(float[] x, float[] y, float[] z, int[] c)
	{
		put(x[0], y[0], z[0], c[0]);
		put(x[1], y[1], z[1], c[1]);
		put(x[2], y[2], z[2], c[2]);
		put(x[0], y[0], z[0], c[0]);
		put(x[2], y[2], z[2], c[2]);
		put(x[3], y[3], z[3], c[3]);
	}

	/** The vertices, ready to read. */
	ByteBuffer finish()
	{
		buf.flip();
		return buf;
	}
}
