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
		int vao, vbo, ebo, count, minHeight, maxHeight;
		boolean shortIndices;
	}

	private static final class Slot
	{
		final Mesh[] meshes;
		final boolean[] pending;
		/** Meshes of an older copy of the square: drawn until they're built again. */
		final boolean[] stale;
		/** Bumped when the meshes go stale, so builds started before are dropped. */
		int version;

		Slot(int levels)
		{
			meshes = new Mesh[levels];
			pending = new boolean[levels];
			stale = new boolean[levels];
		}

		boolean anyStale()
		{
			for (boolean s : stale)
			{
				if (s)
					return true;
			}
			return false;
		}

		boolean wants(int level)
		{
			return (meshes[level] == null || stale[level]) && !pending[level];
		}

		void markStale()
		{
			version++;
			for (int l = 0; l < meshes.length; l++)
			{
				stale[l] = meshes[l] != null;
				pending[l] = false;
			}
		}
	}

	private final String name;
	/** Level i is used out to ranges[i] tiles. */
	private double[] ranges;
	/** Whether the last level is used beyond the last range (else nothing is drawn there). */
	private boolean everywhere;
	/** Levels a square can hold, whatever the layer's levels are switched to. */
	private static final int MAX_LEVELS = 8;
	/** Added to the distance of squares built out of view, so those in view come first. */
	private static final double OUT_OF_VIEW = 1e9;
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

	/**
	 * Changes how far out each level is used (as many ranges as before). What's built is kept: squares switch
	 * level as they're drawn, and meshes out of range are let go as usual.
	 */
	void setRanges(double[] ranges)
	{
		this.ranges = ranges;
	}

	/**
	 * Switches to other levels (and how many), built by {@code builder}: every square is built again, drawing what
	 * it has meanwhile.
	 */
	void setLevels(double[] ranges, boolean everywhere, Builder builder)
	{
		this.ranges = ranges;
		this.everywhere = everywhere;
		rebuild(r -> true, builder);
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
	 * Moves on to a newer copy of the world, built by {@code builder}. Squares that are the same object as before
	 * keep their meshes; squares it has a new object for keep drawing the old meshes until they're built again.
	 */
	void update(WorldTerrain world, Builder builder)
	{
		Map<WorldTerrain.Region, Slot> old = new IdentityHashMap<>(slots);
		slots.clear();
		for (WorldTerrain.Region r : world.regions())
		{
			Slot slot = old.remove(r);
			if (slot == null && this.world != null)
			{
				WorldTerrain.Region was = this.world.region(r.rx, r.ry);
				slot = was == null ? null : old.remove(was);
				if (slot != null)
					slot.markStale();
			}
			if (slot != null)
				slots.put(r, slot);
		}
		for (Slot slot : old.values())
		{
			for (int l = 0; l < slot.meshes.length; l++)
				freeMesh(slot, l);
		}
		this.world = world;
		this.builder = builder;
	}

	/** Builds these squares again with {@code builder}, drawing their old meshes until then. */
	void rebuild(java.util.function.Predicate<WorldTerrain.Region> which, Builder builder)
	{
		for (Map.Entry<WorldTerrain.Region, Slot> e : slots.entrySet())
		{
			if (which.test(e.getKey()))
				e.getValue().markStale();
		}
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
				slot = new Slot(MAX_LEVELS);
				slots.put(r, slot);
			}

			if (everywhere && slot.wants(levels() - 1))
				wanted.add(new Object[]{r, levels() - 1, dist, true});
			float ox = (r.baseX() - baseX) * 128f - cx, oz = (r.baseY() - baseY) * 128f - cz;
			Mesh any = best(slot, level);
			boolean visible = any == null || visible(proj, ox, any.minHeight - cy, any.maxHeight - cy, oz);
			// squares out of view are built only to replace meshes gone stale (after those in view), so a world update or
			// a change of levels finishes, and lets go of the old meshes, without looking all round
			if (slot.wants(level) && (visible || slot.anyStale()))
				wanted.add(new Object[]{r, level, visible ? dist : dist + OUT_OF_VIEW, false});
			// once the level wanted is built, the old ones stood in for it go; finer levels than needed go once
			// well out of their range
			boolean ready = slot.meshes[level] != null && !slot.stale[level];
			for (int l = 0; l < slot.meshes.length; l++)
			{
				if (l == level)
					continue;
				if (slot.stale[l])
				{
					if (ready)
						freeMesh(slot, l);
				}
				else if (l < level && dist > ranges[l] * 1.25)
					freeMesh(slot, l);
			}
			if (any == null || !visible || skip || any.count == 0)
				continue;
			glUniform3f(uniOffset, ox, -cy, oz);
			glBindVertexArray(any.vao);
			glDrawElements(GL_TRIANGLES, any.count, any.shortIndices ? GL_UNSIGNED_SHORT : GL_UNSIGNED_INT, 0);
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

	/**
	 * The wanted level if built; else, while the square is being built again, what it showed before (its stale
	 * meshes, so new levels built meanwhile don't change it twice); else the nearest built one (coarser first).
	 */
	private static Mesh best(Slot slot, int level)
	{
		if (slot.meshes[level] != null)
			return slot.meshes[level];
		boolean stale = slot.anyStale();
		for (int d = 1; d < slot.meshes.length; d++)
		{
			int coarser = level + d, finer = level - d;
			if (coarser < slot.meshes.length && slot.meshes[coarser] != null && (!stale || slot.stale[coarser]))
				return slot.meshes[coarser];
			if (finer >= 0 && slot.meshes[finer] != null && (!stale || slot.stale[finer]))
				return slot.meshes[finer];
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
			Slot slot = slots.get(r);
			slot.pending[level] = true;
			int version = slot.version;
			inFlight.incrementAndGet();
			workers.execute(() ->
			{
				try
				{
					if (gen == generation)
						built.add(new Object[]{b.build(r, level), level, gen, version});
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
			if (slot == null || slot.version != (int) job[3])
				continue;
			slot.pending[level] = false;
			freeMesh(slot, level);
			Mesh m = new Mesh();
			m.count = b.count;
			m.shortIndices = b.shortIndices;
			m.minHeight = b.minHeight;
			m.maxHeight = b.maxHeight;
			if (b.count > 0)
			{
				m.vao = glGenVertexArrays();
				m.vbo = glGenBuffers();
				glBindVertexArray(m.vao);
				glBindBuffer(GL_ARRAY_BUFFER, m.vbo);
				glBufferData(GL_ARRAY_BUFFER, b.vertices, GL_STATIC_DRAW);
				// the index buffer is part of the vertex array's state: bound after it
				m.ebo = glGenBuffers();
				glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, m.ebo);
				glBufferData(GL_ELEMENT_ARRAY_BUFFER, b.indices, GL_STATIC_DRAW);
				// the driver reads the buffers' memory during the calls: they must not be collected (and freed) before
				Reference.reachabilityFence(b.vertices);
				Reference.reachabilityFence(b.indices);
				glVertexAttribPointer(0, 3, GL_SHORT, false, TerrainMesher.STRIDE, 0);
				glEnableVertexAttribArray(0);
				glVertexAttribIPointer(3, 1, GL_UNSIGNED_SHORT, TerrainMesher.STRIDE, 6);
				glEnableVertexAttribArray(3);
				glVertexAttribIPointer(1, 1, GL_INT, TerrainMesher.STRIDE, 8);
				glEnableVertexAttribArray(1);
				glVertexAttribPointer(2, 2, GL_SHORT, false, TerrainMesher.STRIDE, 12);
				glEnableVertexAttribArray(2);
				glBindVertexArray(0);
				glBindBuffer(GL_ARRAY_BUFFER, 0);
			}
			slot.meshes[level] = m;
		}
	}

	private static void freeMesh(Slot slot, int level)
	{
		Mesh m = slot.meshes[level];
		slot.stale[level] = false;
		if (m == null)
			return;
		if (m.vao != 0)
			glDeleteVertexArrays(m.vao);
		if (m.vbo != 0)
			glDeleteBuffers(m.vbo);
		if (m.ebo != 0)
			glDeleteBuffers(m.ebo);
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
