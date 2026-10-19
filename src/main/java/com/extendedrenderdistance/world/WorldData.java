package com.extendedrenderdistance.world;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/** The game cache's world, read once in the background and kept. Any thread. */
@Slf4j
@Singleton
public class WorldData
{
	private CompletableFuture<WorldCache> cache;

	/**
	 * Starts reading from {@code source} (unless it's read, or being read, already) and runs {@code then} with the
	 * result when it's ready (null if it couldn't be read; the next call tries again).
	 */
	public synchronized void whenLoaded(CacheSource source, Consumer<WorldCache> then)
	{
		if (cache == null || cache.isDone() && cache.getNow(null) == null)
		{
			CompletableFuture<WorldCache> f = new CompletableFuture<>();
			Thread t = new Thread(() ->
			{
				try
				{
					f.complete(WorldCache.open(source));
				}
				catch (InterruptedException e)
				{
					log.debug("Extended Render Distance: stopped reading the game cache");
					f.complete(null);
				}
				catch (RuntimeException e)
				{
					log.error("Extended Render Distance: couldn't read the world from the game cache", e);
					f.complete(null);
				}
			}, "Extended Render Distance cache");
			t.setDaemon(true);
			t.start();
			cache = f;
		}
		cache.thenAccept(then);
	}
}
