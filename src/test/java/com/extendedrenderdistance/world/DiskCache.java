package com.extendedrenderdistance.world;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import net.runelite.cache.IndexType;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.index.FileData;

/** The game cache files on disk, for tests (the plugin reads through the client). */
final class DiskCache implements CacheSource, Closeable
{
	private final Store store;
	/** The last group unpacked (reading a config file at a time would unpack its group every time). */
	private Archive lastArchive;
	private net.runelite.cache.fs.ArchiveFiles lastFiles;

	DiskCache(File dir) throws IOException
	{
		store = new Store(dir);
		store.load();
	}

	private final Files files = new Files()
	{
		@Override
		public byte[] file(int index, int group, int file)
		{
			try
			{
				Archive a = index(index).getArchive(group);
				if (a == null)
					return null;
				if (a != lastArchive)
				{
					byte[] data = store.getStorage().loadArchive(a);
					if (data == null)
						return null;
					lastFiles = a.getFiles(data);
					lastArchive = a;
				}
				FSFile f = lastFiles.findFile(file);
				return f == null ? null : f.getContents();
			}
			catch (IOException e)
			{
				return null;
			}
		}

		@Override
		public int[] fileIds(int index, int group)
		{
			Archive a = index(index).getArchive(group);
			if (a == null)
				return null;
			FileData[] fd = a.getFileData();
			int[] ids = new int[fd.length];
			for (int i = 0; i < fd.length; i++)
				ids[i] = fd[i].getId();
			return ids;
		}
	};

	private Index index(int id)
	{
		for (IndexType t : IndexType.values())
		{
			if (t.getNumber() == id)
				return store.getIndex(t);
		}
		throw new IllegalArgumentException("index " + id);
	}

	@Override
	public synchronized <T> T read(Reader<T> work)
	{
		return work.read(files);
	}

	@Override
	public void close() throws IOException
	{
		store.close();
	}
}
