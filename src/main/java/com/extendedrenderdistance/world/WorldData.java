package com.extendedrenderdistance.world;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/** The game cache's world, opened once in the background and kept. Any thread. */
@Slf4j
@Singleton
public class WorldData
{
	private CompletableFuture<WorldCache> cache;

	/** Starts reading (once) and runs {@code then} with the cache when it's ready (null if it couldn't be read). */
	public synchronized void whenLoaded(Consumer<WorldCache> then)
	{
		if (cache == null)
		{
			File dir = new File(RuneLite.RUNELITE_DIR, "jagexcache/oldschool/LIVE");
			cache = CompletableFuture.supplyAsync(() ->
			{
				try
				{
					return WorldCache.open(dir);
				}
				catch (Exception e)
				{
					log.error("Extended Render Distance: couldn't read the world from the game cache at {}", dir, e);
					return null;
				}
			});
		}
		cache.thenAccept(then);
	}
}
