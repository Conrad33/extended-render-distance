package com.extendedrenderdistance.world;

import com.extendedrenderdistance.cache.definitions.ObjectDefinition;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;

/**
 * Objects' model heights (for the ground's shadows) kept on disk between sessions, in RuneLite's own folder, so they
 * needn't be read from the game cache again. Each is kept with a key made from what it was worked out from (the
 * object's model ids and its height scale and offset): one whose object has changed since (a game update) is left
 * out, and read again. A file that's missing or doesn't read is as good as empty.
 */
@Slf4j
final class HeightStore
{
	private static final int MAGIC = 0x45524448, VERSION = 1;

	private HeightStore()
	{
	}

	/** What an object's height was worked out from. */
	static int key(ObjectDefinition d)
	{
		Set<Integer> ids = new TreeSet<>();
		ObjectModels.modelsOf(d, 10, ids);
		int k = d.getModelSizeHeight() * 31 + (short) d.getOffsetHeight();
		for (int id : ids)
			k = k * 31 + id;
		return k;
	}

	/** Puts the heights kept in {@code file} whose objects haven't changed into {@code heights}; how many. */
	static int load(File file, WorldCache cache, Map<Integer, Integer> heights)
	{
		if (!file.isFile())
			return 0;
		int loaded = 0;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file))))
		{
			if (in.readInt() != MAGIC || in.readInt() != VERSION)
				return 0;
			int n = in.readInt();
			for (int i = 0; i < n; i++)
			{
				int id = in.readInt(), key = in.readInt(), height = in.readInt();
				ObjectDefinition d = cache.object(id);
				if (d != null && key(d) == key)
				{
					heights.putIfAbsent(id, height);
					loaded++;
				}
			}
		}
		catch (IOException e)
		{
			log.debug("Extended Render Distance: couldn't read {}", file, e);
		}
		return loaded;
	}

	/** Keeps {@code heights} in {@code file} (written whole, then put in place). */
	static void save(File file, WorldCache cache, Map<Integer, Integer> heights)
	{
		Set<Integer> ids = new HashSet<>(heights.keySet());
		try
		{
			Files.createDirectories(file.getParentFile().toPath());
			File tmp = new File(file.getPath() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp))))
			{
				int n = 0;
				for (int id : ids)
				{
					if (cache.object(id) != null)
						n++;
				}
				out.writeInt(MAGIC);
				out.writeInt(VERSION);
				out.writeInt(n);
				for (int id : ids)
				{
					ObjectDefinition d = cache.object(id);
					if (d == null)
						continue;
					out.writeInt(id);
					out.writeInt(key(d));
					out.writeInt(heights.get(id));
				}
			}
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			log.debug("Extended Render Distance: couldn't write {}", file, e);
		}
	}
}
