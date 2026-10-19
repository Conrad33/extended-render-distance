package com.horizon.world;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.IndexType;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;

/**
 * The game cache on disk, kept open: the surface world's terrain and placements (read once), every object
 * definition, and models read on demand. Any thread (reads from the store are serialised).
 */
@Slf4j
public final class WorldCache implements Closeable
{
	private final Store store;
	private final Index models;
	public final WorldTerrain terrain;
	private final ObjectDefinition[] objects;

	private WorldCache(Store store, WorldTerrain terrain, ObjectDefinition[] objects)
	{
		this.store = store;
		this.models = store.getIndex(IndexType.MODELS);
		this.terrain = terrain;
		this.objects = objects;
	}

	public static WorldCache open(File dir) throws IOException
	{
		long start = System.nanoTime();
		Store store = new Store(dir);
		try
		{
			store.load();
			WorldTerrain terrain = TerrainLoader.load(store);
			ObjectManager om = new ObjectManager(store);
			om.load();
			int max = 0;
			for (ObjectDefinition d : om.getObjects())
				max = Math.max(max, d.getId());
			ObjectDefinition[] objects = new ObjectDefinition[max + 1];
			for (ObjectDefinition d : om.getObjects())
				objects[d.getId()] = d;
			log.info("Horizon: game cache ready in {} ms ({} objects)", (System.nanoTime() - start) / 1_000_000,
				objects.length);
			return new WorldCache(store, terrain, objects);
		}
		catch (IOException | RuntimeException e)
		{
			store.close();
			throw e;
		}
	}

	public ObjectDefinition object(int id)
	{
		return id >= 0 && id < objects.length ? objects[id] : null;
	}

	/** A fresh copy of a model (safe to change), or null. */
	public ModelDefinition model(int id)
	{
		byte[] data;
		try
		{
			synchronized (store)
			{
				Archive a = models.getArchive(id);
				if (a == null)
					return null;
				byte[] raw = store.getStorage().loadArchive(a);
				if (raw == null)
					return null;
				data = a.decompress(raw);
			}
			return new ModelLoader().load(id, data);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Horizon: model {} didn't load", id, e);
			return null;
		}
	}

	@Override
	public void close() throws IOException
	{
		synchronized (store)
		{
			store.close();
		}
	}
}
