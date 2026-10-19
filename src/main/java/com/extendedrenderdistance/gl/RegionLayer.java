package com.extendedrenderdistance.gl;

import com.extendedrenderdistance.world.TerrainMesher;
import com.extendedrenderdistance.world.WorldTerrain;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Per-map-square meshes at several levels, streamed by distance from the camera: each frame every square picks the
 * level its distance calls for, missing meshes are built on worker threads (nearest first) and uploaded a few at a
 * time, the nearest built level is drawn meanwhile, and meshes well out of range are freed. Squares outside the
 * view are skipped. Used for the ground (levels of detail out to the horizon) and the scenery (everything near,
 * landmarks further, nothing beyond). GL thread, apart from the workers.
 */
@Slf4j
final class RegionLayer
{
	/** Builds a square's mesh at a level (any thread). */
	interface Builder
	{
		TerrainMesher.Built build(WorldTerrain.Region region, int level);
	}

	/** Which squares to draw at all. */
	interface Filter
	{
		boolean includes(WorldTerrain.Region region);
	}

	private Filter filter = r -> true;

	void setFilter(Filter filter)
	{
		this.filter = filter;
	}

	private static final class Mesh
	{
		int vao, vbo, count, minHeight, maxHeight;
	}

	private static final class Slot
	{
		final Mesh[] meshes;
		final boolean[] pending;

		Slot(int levels)
		{
			meshes = new Mesh[levels];
			pending = new boolean[levels];
		}
	}

	private final String name;
	/** Level i is used out to ranges[i] tiles. */
	private final double[] ranges;
	/** Whether the last level is used beyond the last range (else nothing is drawn there). */
	private final boolean everywhere;
	private final int maxInFlight, uploadsPerFrame;
	private final ExecutorService workers;
	private final ConcurrentLinkedQueue<Object[]> built = new ConcurrentLinkedQueue<>();
	private final AtomicInteger inFlight = new AtomicInteger();
	private final Map<WorldTerrain.Region, Slot> slots = new IdentityHashMap<>();
	private volatile int generation;
	private WorldTerrain world;
	private Builder builder;
	private final float[] clip = new float[4];

	RegionLayer(String name, double[] ranges, boolean everywhere, int threads, int maxInFlight, int uploadsPerFrame)
	{
		this.name = name;
		this.ranges = ranges;
		this.everywhere = everywhere;
		this.maxInFlight = maxInFlight;
		this.uploadsPerFrame = uploadsPerFrame;
		this.workers = Executors.newFixedThreadPool(threads, r ->
		{
			Thread t = new Thread(r, "Render distance " + name);
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		});
	}

	private int levels()
	{
		return everywhere ? ranges.length + 1 : ranges.length;
	}

	/** Uses this world and builder (null for none); everything built before is freed. */
	void setWorld(WorldTerrain world, Builder builder)
	{
		if (world == this.world && builder == this.builder)
			return;
		free();
		this.world = world;
		this.builder = builder;
	}

	/**
	 * Draws the layer. {@code proj} is the camera-relative projection; the camera is at scene position (cx, cy,
	 * cz) in a scene whose south-west tile is world tile (baseX, baseY). {@code uniOffset} is the program's offset
	 * uniform. Squares further than {@code maxDistance} tiles, or {@code hidden} (wholly drawn by the game itself),
	 * aren't drawn.
	 */
	void draw(float[] proj, float cx, float cy, float cz, int baseX, int baseY, int uniOffset, double maxDistance,
		java.util.function.Predicate<WorldTerrain.Region> hidden)
	{
		if (world == null || builder == null)
			return;
		upload();

		double camTileX = baseX + cx / 128.0, camTileY = baseY + cz / 128.0;
		double far = ranges[ranges.length - 1];
		List<Object[]> wanted = new ArrayList<>();
		for (WorldTerrain.Region r : world.regions())
		{
			if (!filter.includes(r))
				continue;
			boolean skip = hidden.test(r);
			double dx = Math.max(0, Math.max(r.baseX() - camTileX, camTileX - (r.baseX() + 64)));
			double dy = Math.max(0, Math.max(r.baseY() - camTileY, camTileY - (r.baseY() + 64)));
			double dist = Math.sqrt(dx * dx + dy * dy);
			int level = dist > maxDistance ? -1 : level(dist);
			Slot slot = slots.get(r);
			if (level < 0)
			{
				// out of range: let go of it once well past
				if (slot != null && dist > Math.min(far, maxDistance) * 1.25)
				{
					for (int l = 0; l < slot.meshes.length; l++)
						freeMesh(slot, l);
				}
				continue;
			}
			if (slot == null)
			{
				slot = new Slot(levels());
				slots.put(r, slot);
			}

			if (everywhere && slot.meshes[levels() - 1] == null && !slot.pending[levels() - 1])
				wanted.add(new Object[]{r, levels() - 1, dist, true});
			float ox = (r.baseX() - baseX) * 128f - cx, oz = (r.baseY() - baseY) * 128f - cz;
			Mesh any = best(slot, level);
			boolean visible = any == null || visible(proj, ox, any.minHeight - cy, any.maxHeight - cy, oz);
			if (visible && slot.meshes[level] == null && !slot.pending[level])
				wanted.add(new Object[]{r, level, dist, false});
			// finer levels than needed, once well out of their range
			for (int l = 0; l < level; l++)
			{
				if (dist > ranges[l] * 1.25)
					freeMesh(slot, l);
			}
			if (any == null || !visible || skip || any.count == 0)
				continue;
			glUniform3f(uniOffset, ox, -cy, oz);
			glBindVertexArray(any.vao);
			glDrawArrays(GL_TRIANGLES, 0, any.count);
		}
		glBindVertexArray(0);
		request(wanted);
	}

	private int level(double dist)
	{
		for (int i = 0; i < ranges.length; i++)
		{
			if (dist < ranges[i])
				return i;
		}
		return everywhere ? ranges.length : -1;
	}

	/** The wanted level if built, else the nearest built one (coarser first). */
	private static Mesh best(Slot slot, int level)
	{
		if (slot.meshes[level] != null)
			return slot.meshes[level];
		for (int d = 1; d < slot.meshes.length; d++)
		{
			if (level + d < slot.meshes.length && slot.meshes[level + d] != null)
				return slot.meshes[level + d];
			if (level - d >= 0 && slot.meshes[level - d] != null)
				return slot.meshes[level - d];
		}
		return null;
	}

	/** False when the square's box is wholly outside one side of the view. Heights are camera-relative. */
	private final int[] out = new int[5];

	private boolean visible(float[] proj, float ox, float minY, float maxY, float oz)
	{
		java.util.Arrays.fill(out, 0);
		float s = 64 * 128f;
		for (int c = 0; c < 8; c++)
		{
			float x = ox + ((c & 1) != 0 ? s : 0);
			float y = (c & 2) != 0 ? maxY + 512 : minY - 512;
			float z = oz + ((c & 4) != 0 ? s : 0);
			Mat4.transform(proj, x, y, z, clip);
			float w = clip[3];
			if (clip[0] > w)
				out[0]++;
			if (clip[0] < -w)
				out[1]++;
			if (clip[1] > w)
				out[2]++;
			if (clip[1] < -w)
				out[3]++;
			if (w < 1)
				out[4]++;
		}
		for (int o : out)
		{
			if (o == 8)
				return false;
		}
		return true;
	}

	/** Queues the wanted builds: everywhere-meshes far away first (so the horizon fills), then nearest first. */
	private void request(List<Object[]> wanted)
	{
		if (wanted.isEmpty() || inFlight.get() >= maxInFlight)
			return;
		wanted.sort((a, b) ->
		{
			boolean ba = (boolean) a[3], bb = (boolean) b[3];
			if (ba != bb)
				return ba ? -1 : 1;
			return Double.compare((double) a[2], (double) b[2]);
		});
		int gen = generation;
		Builder b = builder;
		for (Object[] job : wanted)
		{
			if (inFlight.get() >= maxInFlight)
				break;
			WorldTerrain.Region r = (WorldTerrain.Region) job[0];
			int level = (int) job[1];
			slots.get(r).pending[level] = true;
			inFlight.incrementAndGet();
			workers.execute(() ->
			{
				try
				{
					if (gen == generation)
						built.add(new Object[]{b.build(r, level), level, gen});
				}
				catch (RuntimeException | OutOfMemoryError e)
				{
					log.warn("Extended Render Distance: couldn't build {} for square {},{}", name, r.rx, r.ry, e);
				}
				finally
				{
					inFlight.decrementAndGet();
				}
			});
		}
	}

	private void upload()
	{
		for (int i = 0; i < uploadsPerFrame; i++)
		{
			Object[] job = built.poll();
			if (job == null)
				return;
			TerrainMesher.Built b = (TerrainMesher.Built) job[0];
			int level = (int) job[1];
			if ((int) job[2] != generation)
				continue;
			Slot slot = slots.get(b.region);
			if (slot == null)
				continue;
			slot.pending[level] = false;
			freeMesh(slot, level);
			Mesh m = new Mesh();
			m.count = b.count;
			m.minHeight = b.minHeight;
			m.maxHeight = b.maxHeight;
			if (b.count > 0)
			{
				m.vao = glGenVertexArrays();
				m.vbo = glGenBuffers();
				glBindVertexArray(m.vao);
				glBindBuffer(GL_ARRAY_BUFFER, m.vbo);
				glBufferData(GL_ARRAY_BUFFER, b.vertices, GL_STATIC_DRAW);
				// the driver reads the buffer's memory during the call: it must not be collected (and freed) before
				Reference.reachabilityFence(b.vertices);
				glVertexAttribPointer(0, 3, GL_FLOAT, false, TerrainMesher.STRIDE, 0);
				glEnableVertexAttribArray(0);
				glVertexAttribIPointer(1, 1, GL_INT, TerrainMesher.STRIDE, 12);
				glEnableVertexAttribArray(1);
				glBindVertexArray(0);
				glBindBuffer(GL_ARRAY_BUFFER, 0);
			}
			slot.meshes[level] = m;
		}
	}

	private static void freeMesh(Slot slot, int level)
	{
		Mesh m = slot.meshes[level];
		if (m == null)
			return;
		if (m.vao != 0)
			glDeleteVertexArrays(m.vao);
		if (m.vbo != 0)
			glDeleteBuffers(m.vbo);
		slot.meshes[level] = null;
	}

	/** Frees every mesh (GL thread); queued builds are dropped when they finish. */
	void free()
	{
		generation++;
		for (Slot s : slots.values())
		{
			for (int l = 0; l < s.meshes.length; l++)
				freeMesh(s, l);
		}
		slots.clear();
		built.clear();
	}
}
