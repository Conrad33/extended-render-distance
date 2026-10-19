package com.extendedrenderdistance.world;

import java.nio.ByteBuffer;
import java.util.Random;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class VertexSinkTest
{
	/** Every corner comes back from the vertices through its index exactly as put (rounded to whole units). */
	@Test
	public void cornersComeBackInOrder()
	{
		Random rnd = new Random(1);
		int n = 30000;
		float[][] put = new float[n][];
		int[] colour = new int[n], tex = new int[n];
		VertexSink sink = new VertexSink(16);
		for (int i = 0; i < n; i++)
		{
			// a third repeat an earlier corner exactly
			int from = i > 0 && rnd.nextInt(3) == 0 ? rnd.nextInt(i) : -1;
			if (from >= 0)
			{
				put[i] = put[from];
				colour[i] = colour[from];
				tex[i] = tex[from];
			}
			else
			{
				put[i] = new float[]{rnd.nextInt(16000) - 4000 + rnd.nextFloat(), -rnd.nextInt(6000) + rnd.nextFloat(),
					rnd.nextInt(16000) - 4000, rnd.nextFloat() * 40 - 20, rnd.nextFloat() * 40 - 20};
				colour[i] = rnd.nextInt();
				tex[i] = rnd.nextBoolean() ? 0 : VertexSink.texture(rnd.nextInt(214), rnd.nextInt(128)) | (rnd.nextBoolean() ? VertexSink.REPEAT : 0);
			}
			sink.put(put[i][0], put[i][1], put[i][2], colour[i], put[i][3], put[i][4], tex[i]);
		}
		TerrainMesher.Built b = sink.build(null, 1, 0, 0);
		assertEquals(n, b.count);
		assertTrue("vertices " + b.vertexCount, b.vertexCount < n);
		ByteBuffer v = b.vertices, idx = b.indices;
		for (int i = 0; i < n; i++)
		{
			int k = b.shortIndices ? idx.getShort(i * 2) & 0xffff : idx.getInt(i * 4);
			int at = k * TerrainMesher.STRIDE;
			assertEquals(Math.round(put[i][0]), v.getShort(at));
			assertEquals(Math.round(put[i][1]), v.getShort(at + 2));
			assertEquals(Math.round(put[i][2]), v.getShort(at + 4));
			assertEquals(tex[i], v.getShort(at + 6) & 0xffff);
			assertEquals(colour[i], v.getInt(at + 8));
			assertEquals(Math.round(put[i][3] * 256), v.getShort(at + 12));
			assertEquals(Math.round(put[i][4] * 256), v.getShort(at + 14));
		}
	}

	@Test
	public void manyVerticesUseIntIndices()
	{
		VertexSink sink = new VertexSink(16);
		for (int i = 0; i < 70000 * 3; i++)
			sink.put(i % 30000, i / 30000, 0, i);
		TerrainMesher.Built b = sink.build(null, 1, 0, 0);
		assertEquals(70000 * 3, b.vertexCount);
		assertTrue(!b.shortIndices);
		assertEquals(b.count * 4, b.indices.remaining());
		assertEquals(70000 * 3 - 1, b.indices.getInt((70000 * 3 - 1) * 4));
	}
}
