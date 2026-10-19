package com.extendedrenderdistance.world;

/**
 * Where the game cache is read from. Reads are done in batches through {@link #read}, which runs them where the
 * cache may be touched (the client's cache only on the client thread) and hands back the result to any thread.
 */
public interface CacheSource
{
	/** The cache's files, inside a {@link #read}. */
	interface Files
	{
		/** A file of a group of an index (decompressed), or null if the client doesn't have it yet. */
		byte[] file(int index, int group, int file);

		/** The file ids of a group, or null if there's no such group. */
		int[] fileIds(int index, int group);
	}

	interface Reader<T>
	{
		T read(Files files);
	}

	/** Runs {@code work} against the cache and returns what it returns; blocks until it has run. */
	<T> T read(Reader<T> work) throws InterruptedException;

	int MAPS = 5, CONFIGS = 2, MODELS = 7;
	/** Groups of the configs index. */
	int UNDERLAYS = 1, OVERLAYS = 4, OBJECTS = 6;
}
