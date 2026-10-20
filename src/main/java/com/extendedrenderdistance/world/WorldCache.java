package com.extendedrenderdistance.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import com.extendedrenderdistance.cache.definitions.ModelDefinition;
import com.extendedrenderdistance.cache.definitions.ObjectDefinition;
import com.extendedrenderdistance.cache.definitions.loaders.ModelLoader;
import com.extendedrenderdistance.cache.definitions.loaders.ObjectLoader;

/**
 * The game cache as this plugin uses it: the whole map's terrain and placements and every object definition (read
 * once, at the start), and models read as scenery is built. Everything comes through a {@link CacheSource}. Any
 * thread.
 */
@Slf4j
public final class WorldCache
{
	/** Models read per batch. */
	private static final int MODEL_BATCH = 200;

	private final CacheSource source;
	public final WorldTerrain terrain;
	private final ObjectDefinition[] objects;
	/** Model files read ahead for scenery being built, by id. */
	private final Map<Integer, byte[]> prefetched;
	/** Animations read so far, by id (NONE for one that can't be played). */
	private final Map<Integer, Object> animations = new ConcurrentHashMap<>();
	private static final Object NONE = new Object();
	/** Objects' model heights (for their shadows), by object id: kept as more of the world is read. */
	private final Map<Integer, Integer> heights;
	/** The map squares whose objects' model heights have all been read. */
	private final java.util.Set<WorldTerrain.Region> heightsRead;
	/** Every square of this copy of the map has its heights read ({@link #readModelHeights}). */
	private volatile boolean heightsComplete;
	/** Where the heights are kept between sessions (in RuneLite's folder). */
	private static final java.io.File HEIGHTS_FILE = new java.io.File(
		new java.io.File(net.runelite.client.RuneLite.RUNELITE_DIR, "extended-render-distance"), "shadow-heights.dat");

	private WorldCache(CacheSource source, WorldTerrain terrain, ObjectDefinition[] objects,
		Map<Integer, byte[]> prefetched,
		Map<Integer, Integer> heights, java.util.Set<WorldTerrain.Region> heightsRead)
	{
		this.source = source;
		this.terrain = terrain;
		this.objects = objects;
		this.prefetched = prefetched;
		this.heights = heights;
		this.heightsRead = heightsRead;
	}

	/** Reads the map and the object definitions. Blocks while the reads are served: off the client thread. */
	public static WorldCache open(CacheSource source) throws InterruptedException
	{
		return open(source, TerrainLoader.load(source).terrain);
	}

	/** Reads the object definitions, for this map. Blocks while the reads are served: off the client thread. */
	public static WorldCache open(CacheSource source, WorldTerrain terrain) throws InterruptedException
	{
		long start = System.nanoTime();
		Map<Integer, byte[]> files = TerrainLoader.configFiles(source, CacheSource.OBJECTS);
		int max = 0;
		for (int id : files.keySet())
			max = Math.max(max, id);
		ObjectDefinition[] objects = new ObjectDefinition[max + 1];
		ObjectLoader loader = new ObjectLoader();
		for (Map.Entry<Integer, byte[]> e : files.entrySet())
		{
			try
			{
				objects[e.getKey()] = loader.load(e.getKey(), e.getValue());
			}
			catch (RuntimeException ex)
			{
				log.debug("Extended Render Distance: object {} didn't decode", e.getKey(), ex);
			}
		}
		// the client animates an object that changes form with the object's animation, whatever the form: a form
		// without one of its own takes it
		for (ObjectDefinition d : objects)
		{
			if (d == null || d.getAnimationID() < 0 || d.getConfigChangeDest() == null)
				continue;
			for (int form : d.getConfigChangeDest())
			{
				if (form >= 0 && form < objects.length && objects[form] != null && objects[form].getAnimationID() < 0)
					objects[form].setAnimationID(d.getAnimationID());
			}
		}
		log.info("Extended Render Distance: read {} object definitions in {} ms", objects.length,
			(System.nanoTime() - start) / 1_000_000);
		return new WorldCache(source, terrain, objects, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
			ConcurrentHashMap.newKeySet());
	}

	/** This cache with more of the map read. */
	public WorldCache withTerrain(WorldTerrain terrain)
	{
		return new WorldCache(source, terrain, objects, prefetched, heights, heightsRead);
	}

	public ObjectDefinition object(int id)
	{
		return id >= 0 && id < objects.length ? objects[id] : null;
	}

	/** Reads these models' files ahead, in a few batches, so building a square's scenery doesn't wait on each. */
	public void prefetchModels(Collection<Integer> ids) throws InterruptedException
	{
		List<Integer> wanted = new ArrayList<>();
		for (int id : ids)
		{
			if (!prefetched.containsKey(id))
				wanted.add(id);
		}
		for (int i = 0; i < wanted.size(); i += MODEL_BATCH)
		{
			List<Integer> batch = wanted.subList(i, Math.min(wanted.size(), i + MODEL_BATCH));
			byte[][] data = source.read(files ->
			{
				byte[][] out = new byte[batch.size()][];
				for (int k = 0; k < batch.size(); k++)
					out[k] = files.file(CacheSource.MODELS, batch.get(k), 0);
				return out;
			});
			for (int k = 0; k < batch.size(); k++)
			{
				if (data[k] != null)
					prefetched.put(batch.get(k), data[k]);
			}
		}
	}

	/** Lets go of models read ahead. */
	public void releaseModels(Collection<Integer> ids)
	{
		for (int id : ids)
			prefetched.remove(id);
	}

	/** An animation, or null when there's none to play. Read the first time (and waited for). */
	ObjectAnimation animation(int id)
	{
		if (id < 0)
			return null;
		Object a = animations.get(id);
		if (a == null)
		{
			try
			{
				a = source.read(files -> ObjectAnimation.read(files, id));
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return null;
			}
			catch (RuntimeException e)
			{
				log.debug("Extended Render Distance: animation {} didn't read", id, e);
				a = null;
			}
			animations.put(id, a == null ? NONE : a);
		}
		return a == NONE ? null : (ObjectAnimation) a;
	}

	/**
	 * Reads the model heights of every shadow-casting object on the map at once (in a few large batches), rather
	 * than a few at a time square by square. Blocks while the reads are served: off the client thread.
	 */
	public void readModelHeights() throws InterruptedException
	{
		readHeights();
		heightsComplete = true;
	}

	/** Whether {@link #readModelHeights} has finished for this copy of the map (its squares' shadows are quick). */
	public boolean modelHeightsRead()
	{
		return heightsComplete;
	}

	private void readHeights() throws InterruptedException
	{
		synchronized (heightsRead)
		{
			// the first time: those kept from earlier sessions
			if (heightsRead.isEmpty() && heights.isEmpty())
			{
				long start = System.nanoTime();
				int kept = HeightStore.load(HEIGHTS_FILE, this, heights);
				log.info("Extended Render Distance: {} objects' model heights kept from before ({} ms)", kept,
					(System.nanoTime() - start) / 1_000_000);
			}
			java.util.Map<Integer, ObjectDefinition> wanted = new java.util.HashMap<>();
			java.util.List<WorldTerrain.Region> regions = new java.util.ArrayList<>();
			for (WorldTerrain.Region g : terrain.regions())
			{
				if (heightsRead.contains(g))
					continue;
				regions.add(g);
				for (int i = 0; i < g.locCount(); i++)
				{
					ObjectDefinition d = object(g.locId(i));
					int type = g.locType(i);
					if (g.locPlane(i) == 0 && d != null && d.isShadow() && (type == 10 || type == 11)
						&& Shadows.plainModel(d) && !heights.containsKey(d.getId()))
						wanted.put(d.getId(), d);
				}
			}
			if (wanted.isEmpty())
			{
				heightsRead.addAll(regions);
				return;
			}
			long start = System.nanoTime();
			java.util.Set<Integer> ids = new java.util.HashSet<>();
			for (ObjectDefinition d : wanted.values())
				ObjectModels.modelsOf(d, 10, ids);
			prefetchModels(ids);
			try
			{
				for (ObjectDefinition d : wanted.values())
					modelHeight(d);
			}
			finally
			{
				releaseModels(ids);
			}
			heightsRead.addAll(regions);
			HeightStore.save(HEIGHTS_FILE, this, heights);
			log.info("Extended Render Distance: read {} objects' model heights ({} models) in {} ms", wanted.size(),
				ids.size(), (System.nanoTime() - start) / 1_000_000);
		}
	}

	boolean hasModelHeight(int objectId)
	{
		return heights.containsKey(objectId);
	}

	/** How far an object's model (as a type 10 placement) reaches above the ground, scaled and offset as placed. */
	int modelHeight(ObjectDefinition d)
	{
		Integer h = heights.get(d.getId());
		if (h != null)
			return h;
		java.util.Set<Integer> ids = new java.util.HashSet<>();
		ObjectModels.modelsOf(d, 10, ids);
		int top = 0;
		for (int id : ids)
		{
			ModelDefinition m = model(id);
			if (m != null)
				top = Math.max(top, Shadows.height(m, d));
		}
		heights.putIfAbsent(d.getId(), top);
		return top;
	}

	/** A fresh copy of a model (safe to change), or null. Read ahead if it can be; else read now (and waited for). */
	public ModelDefinition model(int id)
	{
		byte[] data = prefetched.get(id);
		try
		{
			if (data == null)
				data = source.read(files -> files.file(CacheSource.MODELS, id, 0));
			return data == null ? null : new ModelLoader().load(id, data);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return null;
		}
		catch (RuntimeException e)
		{
			log.debug("Extended Render Distance: model {} didn't load", id, e);
			return null;
		}
	}
}
