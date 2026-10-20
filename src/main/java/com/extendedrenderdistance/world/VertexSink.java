package com.extendedrenderdistance.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Collects a mesh's triangle corners and packs them the way they're drawn: each distinct vertex once
 * ({@link #STRIDE} bytes: x, y and z as 16-bit whole units, the texture ({@link #texture}) in 16 bits, the packed
 * colour int, then the texture coordinates as 16-bit 1/256ths, as the GPU plugin stores them), and an index per
 * corner, in the order they were put. Corners alike in every byte are one vertex, so nothing drawn changes.
 */
final class VertexSink
{
	static final int STRIDE = 16;
	/**
	 * A see-through or depth-biased mesh's vertices: as {@link #STRIDE}, then the face's transparency (0 to 255), its
	 * depth bias (the model's, 0 to 255) and 2 spare bytes.
	 */
	static final int TRANSLUCENT_STRIDE = 20;
	/** Set in a vertex's texture for texture coordinates that run on across several tiles, to be repeated. */
	static final int REPEAT = 1 << 15;

	/** Each corner packed: position and texture, then colour and texture coordinates; transparency if translucent. */
	private long[] first, second;
	private int[] transparency;
	int count;

	VertexSink(int corners)
	{
		this(corners, false);
	}

	VertexSink(int corners, boolean translucent)
	{
		first = new long[Math.max(64, corners)];
		second = new long[first.length];
		transparency = translucent ? new int[first.length] : null;
	}

	/**
	 * A vertex's texture: the texture id plus one in bits 0 to 7, the light the client gives a textured face (its
	 * lightness, 2 to 126) in 8 to 14. 0 for an untextured vertex, and for a texture id past what fits (none in the
	 * game reaches it), which is drawn in its flat colour.
	 */
	static int texture(int textureId, int light)
	{
		if (textureId < 0 || textureId + 1 > 255)
			return 0;
		return textureId + 1 | Math.max(2, Math.min(126, light)) << 8;
	}

	void put(float x, float y, float z, int colour)
	{
		put(x, y, z, colour, 0, 0, 0);
	}

	void put(float x, float y, float z, int colour, float u, float v, int texture)
	{
		put(x, y, z, colour, u, v, texture, 0);
	}

	/** A corner of a see-through face ({@code alpha} 1 to 255, as the client's face transparency); translucent sinks. */
	void put(float x, float y, float z, int colour, float u, float v, int texture, int alpha)
	{
		put(x, y, z, colour, u, v, texture, alpha, 0);
	}

	/**
	 * A corner with a transparency and a depth bias (the model's: the face is drawn that much nearer, as the GPU
	 * plugin draws it, so decals win over what they lie on); translucent sinks.
	 */
	void put(float x, float y, float z, int colour, float u, float v, int texture, int alpha, int bias)
	{
		if (count == first.length)
		{
			first = java.util.Arrays.copyOf(first, count * 2);
			second = java.util.Arrays.copyOf(second, count * 2);
			if (transparency != null)
				transparency = java.util.Arrays.copyOf(transparency, count * 2);
		}
		first[count] = bits16(x) | bits16(y) << 16 | bits16(z) << 32 | (texture & 0xffffL) << 48;
		second[count] = colour & 0xffffffffL | bits16(u * 256) << 32 | bits16(v * 256) << 48;
		if (transparency != null)
			transparency[count] = alpha & 0xff | (bias & 0xff) << 8;
		count++;
	}

	/** Two triangles, as {@link #quad(float[], float[], float[], int[], float[], float[], int[])}. */
	void quad(float[] x, float[] y, float[] z, int[] c)
	{
		quad(x, y, z, c, null, null, null);
	}

	/**
	 * Two triangles: a-b-d and b-c-d, textured when {@code t} isn't null. Split across b-d as the GPU plugin splits a
	 * tile (corners south-west, south-east, north-east, north-west: across south-east to north-west), so a sloping
	 * tile lies exactly on the game's.
	 */
	void quad(float[] x, float[] y, float[] z, int[] c, float[] u, float[] v, int[] t)
	{
		int[] order = {0, 1, 3, 1, 2, 3};
		for (int k : order)
		{
			if (t == null)
				put(x[k], y[k], z[k], c[k]);
			else
				put(x[k], y[k], z[k], c[k], u[k], v[k], t[k]);
		}
	}

	/** The mesh: the distinct vertices and the corners' indices into them. */
	TerrainMesher.Built build(WorldTerrain.Region region, int step, int minHeight, int maxHeight)
	{
		int capacity = Integer.highestOneBit(Math.max(8, count) * 2) * 2;
		int mask = capacity - 1;
		int[] table = new int[capacity];
		java.util.Arrays.fill(table, -1);
		long[] uniqueFirst = new long[count], uniqueSecond = new long[count];
		int[] uniqueAlpha = new int[count];
		int[] index = new int[count];
		int unique = 0;
		for (int i = 0; i < count; i++)
		{
			long a = first[i], b = second[i];
			int t = transparency == null ? 0 : transparency[i];
			int h = (int) (mix(a * 0x9E3779B97F4A7C15L ^ b ^ (long) t << 56) >>> 32) & mask;
			int found = -1;
			while (table[h] >= 0)
			{
				int j = table[h];
				if (uniqueFirst[j] == a && uniqueSecond[j] == b && uniqueAlpha[j] == t)
				{
					found = j;
					break;
				}
				h = h + 1 & mask;
			}
			if (found < 0)
			{
				found = unique++;
				table[h] = found;
				uniqueFirst[found] = a;
				uniqueSecond[found] = b;
				uniqueAlpha[found] = t;
			}
			index[i] = found;
		}

		int stride = transparency == null ? STRIDE : TRANSLUCENT_STRIDE;
		ByteBuffer vertices = ByteBuffer.allocateDirect(Math.max(1, unique) * stride).order(ByteOrder.nativeOrder());
		for (int i = 0; i < unique; i++)
		{
			long a = uniqueFirst[i], b = uniqueSecond[i];
			vertices.putShort((short) a).putShort((short) (a >>> 16)).putShort((short) (a >>> 32))
				.putShort((short) (a >>> 48)).putInt((int) b).putShort((short) (b >>> 32)).putShort((short) (b >>> 48));
			if (transparency != null)
				vertices.put((byte) uniqueAlpha[i]).put((byte) (uniqueAlpha[i] >> 8)).put((byte) 0).put((byte) 0);
		}
		vertices.flip();
		boolean shortIndices = unique <= 65536;
		ByteBuffer indices = ByteBuffer.allocateDirect(Math.max(1, count) * (shortIndices ? 2 : 4))
			.order(ByteOrder.nativeOrder());
		for (int i = 0; i < count; i++)
		{
			if (shortIndices)
				indices.putShort((short) index[i]);
			else
				indices.putInt(index[i]);
		}
		indices.flip();
		return new TerrainMesher.Built(region, step, vertices, unique, indices, count, shortIndices, minHeight,
			maxHeight, stride);
	}

	/** A whole unit (or 1/256th, for texture coordinates) in 16 bits. */
	private static long bits16(float f)
	{
		int i = Math.round(f);
		return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, i)) & 0xffffL;
	}

	private static long mix(long z)
	{
		z = (z ^ z >>> 33) * 0xff51afd7ed558ccdL;
		z = (z ^ z >>> 33) * 0xc4ceb9fe1a85ec53L;
		return z ^ z >>> 33;
	}
}
