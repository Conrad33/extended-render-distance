package com.extendedrenderdistance.gl;

import com.extendedrenderdistance.world.ObjectMesher;
import com.extendedrenderdistance.world.TerrainMesher;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Draws the scenery's animated objects playing: each model's frames are built once (on a worker thread) and shared
 * by every object showing it, and each frame every object in a square being drawn shows the frame its animation is
 * at, all those showing the same frame in one instanced draw. A model not built yet isn't drawn. Models not drawn
 * for a while are let go. GL thread, apart from the worker.
 */
@Slf4j
final class AnimatedObjects
{
	/** Frames a model can go undrawn before it's let go. */
	private static final int KEEP_FRAMES = 600;
	/** Floats an instance: position (camera-relative), yaw, then its tile's centre (camera-relative x and z). */
	private static final int INSTANCE_FLOATS = 6;

	private static final class Model
	{
		/** Each frame's mesh (null where it has nothing); null while being built. */
		RegionLayer.Mesh[] frames;
		int lastUsed;
	}

	private final Map<Long, Model> models = new HashMap<>();
	private final ConcurrentLinkedQueue<Object[]> built = new ConcurrentLinkedQueue<>();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "Render distance animations");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});
	private ObjectMesher mesher;
	private int generation, frameNo;
	private int instances;
	private FloatBuffer buffer = ByteBuffer.allocateDirect(4096 * INSTANCE_FLOATS * 4).order(ByteOrder.nativeOrder())
		.asFloatBuffer();
	/** This frame's instances, by the mesh they show. */
	private final Map<RegionLayer.Mesh, float[]> groups = new IdentityHashMap<>();
	private final Map<RegionLayer.Mesh, Integer> counts = new IdentityHashMap<>();

	/** Builds models with this (the world or its settings changed: everything built before is let go). */
	void setMesher(ObjectMesher mesher)
	{
		if (mesher == this.mesher)
			return;
		free();
		this.mesher = mesher;
	}

	/**
	 * Draws the animated objects of the squares drawn this frame ({@code drawn}: each a square's objects and its
	 * camera-relative offset x and z), {@code cycle} client cycles in. The program and its state are the scenery's.
	 */
	void draw(List<Object[]> drawn, float cy, int cycle, int uniOffset, int uniInstanced)
	{
		frameNo++;
		upload();
		if (drawn.isEmpty() || mesher == null)
		{
			evict();
			return;
		}
		groups.clear();
		counts.clear();
		for (Object[] d : drawn)
		{
			@SuppressWarnings("unchecked")
			List<ObjectMesher.Animated> objects = (List<ObjectMesher.Animated>) d[0];
			float ox = (float) d[1], oz = (float) d[2];
			for (ObjectMesher.Animated a : objects)
			{
				Model m = model(a.model);
				m.lastUsed = frameNo;
				if (m.frames == null)
					continue;
				int f = a.animation.frameAt(cycle + a.phase);
				RegionLayer.Mesh mesh = f < m.frames.length ? m.frames[f] : null;
				if (mesh == null || mesh.vao == 0 && mesh.translucent == null)
					continue;
				float[] at = {ox + a.x, a.y - cy, oz + a.z, a.yaw, ox + a.tileX, oz + a.tileZ};
				if (mesh.count > 0)
					add(mesh, at);
				if (mesh.translucent != null && mesh.translucent.vao != 0)
					add(mesh.translucent, at);
			}
		}
		if (!groups.isEmpty())
			drawGroups(uniOffset, uniInstanced);
		evict();
	}

	private void add(RegionLayer.Mesh mesh, float[] at)
	{
		float[] g = groups.get(mesh);
		int n = counts.getOrDefault(mesh, 0);
		if (g == null || (n + 1) * INSTANCE_FLOATS > g.length)
		{
			g = g == null ? new float[8 * INSTANCE_FLOATS] : java.util.Arrays.copyOf(g, g.length * 2);
			groups.put(mesh, g);
		}
		System.arraycopy(at, 0, g, n * INSTANCE_FLOATS, INSTANCE_FLOATS);
		counts.put(mesh, n + 1);
	}

	private void drawGroups(int uniOffset, int uniInstanced)
	{
		int total = 0;
		for (int n : counts.values())
			total += n;
		if (buffer.capacity() < total * INSTANCE_FLOATS)
		{
			buffer = ByteBuffer.allocateDirect(Integer.highestOneBit(total * INSTANCE_FLOATS) * 2 * 4)
				.order(ByteOrder.nativeOrder()).asFloatBuffer();
		}
		buffer.clear();
		// solid ones first, then the see-through ones over them
		List<RegionLayer.Mesh> solid = new ArrayList<>(), seeThrough = new ArrayList<>();
		for (RegionLayer.Mesh mesh : groups.keySet())
			(mesh.stride > TerrainMesher.STRIDE ? seeThrough : solid).add(mesh);
		Map<RegionLayer.Mesh, Integer> first = new IdentityHashMap<>();
		int at = 0;
		for (List<RegionLayer.Mesh> list : java.util.Arrays.asList(solid, seeThrough))
		{
			for (RegionLayer.Mesh mesh : list)
			{
				int n = counts.get(mesh);
				buffer.put(groups.get(mesh), 0, n * INSTANCE_FLOATS);
				first.put(mesh, at);
				at += n;
			}
		}
		buffer.flip();
		if (instances == 0)
			instances = glGenBuffers();
		glBindBuffer(GL_ARRAY_BUFFER, instances);
		glBufferData(GL_ARRAY_BUFFER, (long) buffer.capacity() * 4, GL_STREAM_DRAW);
		glBufferSubData(GL_ARRAY_BUFFER, 0, buffer);

		glUniform3f(uniOffset, 0, 0, 0);
		glUniform1i(uniInstanced, 1);
		draw(solid, first);
		if (!seeThrough.isEmpty())
		{
			glEnable(GL_BLEND);
			glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
			glDepthMask(false);
			draw(seeThrough, first);
			glDepthMask(true);
			glDisable(GL_BLEND);
		}
		glUniform1i(uniInstanced, 0);
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private void draw(List<RegionLayer.Mesh> meshes, Map<RegionLayer.Mesh, Integer> first)
	{
		int bytes = INSTANCE_FLOATS * 4;
		for (RegionLayer.Mesh mesh : meshes)
		{
			glBindVertexArray(mesh.vao);
			// the instances are this mesh's run of the shared buffer
			long base = (long) first.get(mesh) * bytes;
			glVertexAttribPointer(5, 4, GL_FLOAT, false, bytes, base);
			glVertexAttribDivisor(5, 1);
			glEnableVertexAttribArray(5);
			glVertexAttribPointer(6, 2, GL_FLOAT, false, bytes, base + 16);
			glVertexAttribDivisor(6, 1);
			glEnableVertexAttribArray(6);
			glDrawElementsInstanced(GL_TRIANGLES, mesh.count, mesh.shortIndices ? GL_UNSIGNED_SHORT : GL_UNSIGNED_INT,
				0, counts.get(mesh));
		}
	}

	/** A model's entry, its frames asked to be built the first time it's wanted. */
	private Model model(long id)
	{
		Model m = models.get(id);
		if (m != null)
			return m;
		m = new Model();
		models.put(id, m);
		// built with the square it's in: drawn right away
		TerrainMesher.Built[] ahead = mesher.takeFrames(id);
		if (ahead != null)
		{
			m.frames = meshes(ahead);
			return m;
		}
		ObjectMesher builder = mesher;
		int gen = generation;
		worker.execute(() ->
		{
			try
			{
				if (gen == generation)
					built.add(new Object[]{id, builder.frames(id), gen});
			}
			catch (RuntimeException | OutOfMemoryError e)
			{
				log.warn("Extended Render Distance: couldn't build animated model {}", id, e);
			}
		});
		return m;
	}

	private void upload()
	{
		Object[] job;
		while ((job = built.poll()) != null)
		{
			if ((int) job[2] != generation)
				continue;
			Model m = models.get((long) job[0]);
			if (m == null || m.frames != null)
				continue;
			m.frames = meshes((TerrainMesher.Built[]) job[1]);
		}
	}

	private static RegionLayer.Mesh[] meshes(TerrainMesher.Built[] frames)
	{
		RegionLayer.Mesh[] meshes = new RegionLayer.Mesh[frames.length];
		for (int k = 0; k < frames.length; k++)
		{
			if (frames[k] == null)
				continue;
			meshes[k] = RegionLayer.mesh(frames[k]);
			if (frames[k].translucent != null)
				meshes[k].translucent = RegionLayer.mesh(frames[k].translucent);
		}
		return meshes;
	}

	/** Lets go of models not drawn for a while. */
	private void evict()
	{
		if (frameNo % 300 != 0)
			return;
		for (Iterator<Model> it = models.values().iterator(); it.hasNext(); )
		{
			Model m = it.next();
			if (frameNo - m.lastUsed > KEEP_FRAMES)
			{
				free(m);
				it.remove();
			}
		}
		// (the ids gone, so the squares built from now build their frames ahead again)
		if (mesher != null)
		{
			for (long id : mesher.takenModels())
			{
				if (!models.containsKey(id))
					mesher.forget(id);
			}
		}
	}

	private static void free(Model m)
	{
		if (m.frames == null)
			return;
		for (RegionLayer.Mesh mesh : m.frames)
			RegionLayer.free(mesh);
	}

	/** Frees every model (GL thread); builds under way are dropped when they finish. */
	void free()
	{
		generation++;
		for (Model m : models.values())
			free(m);
		models.clear();
		built.clear();
		if (instances != 0)
		{
			glDeleteBuffers(instances);
			instances = 0;
		}
	}
}
