package com.extendedrenderdistance.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.ConfigType;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.loaders.ObjectLoader;

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
	private final Map<Integer, byte[]> prefetched = new ConcurrentHashMap<>();

	private WorldCache(CacheSource source, WorldTerrain terrain, ObjectDefinition[] objects)
	{
		this.source = source;
		this.terrain = terrain;
		this.objects = objects;
	}

	/** Reads the map and the object definitions. Blocks while the reads are served: off the client thread. */
	public static WorldCache open(CacheSource source) throws InterruptedException
	{
		long start = System.nanoTime();
		WorldTerrain terrain = TerrainLoader.load(source);
		Map<Integer, byte[]> files = TerrainLoader.configFiles(source, ConfigType.OBJECT);
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
		log.info("Extended Render Distance: game cache ready in {} ms ({} objects)", (System.nanoTime() - start) / 1_000_000,
			objects.length);
		return new WorldCache(source, terrain, objects);
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
