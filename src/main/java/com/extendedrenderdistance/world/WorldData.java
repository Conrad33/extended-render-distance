package com.extendedrenderdistance.world;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * The game cache's world, read in the background and kept. Map squares the client didn't have yet are read again a
 * few times while it fetches them, and each time more arrive the world is handed on again. Any thread.
 */
@Slf4j
@Singleton
public class WorldData
{
	/**
	 * When squares the client doesn't have yet are read again (seconds after the first pass). A few changed by a
	 * game update arrive within seconds; on a fresh install the client fetches the whole map in about two minutes.
	 * Each read asks the client for them again, so only a few times.
	 */
	private static final int[] RETRY_AFTER_SECONDS = {10, 30, 60, 120, 300};

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r ->
	{
		Thread t = new Thread(r, "Extended Render Distance cache");
		t.setDaemon(true);
		return t;
	});

	private CacheSource source;
	private Consumer<WorldCache> listener;
	private WorldCache world;
	/** Null once there's nothing more to read. */
	private TerrainLoader.Progress progress;
	private long firstPass;
	private int retries;
	/** A read is queued or running. */
	private boolean reading;
	private ScheduledFuture<?> retry;
	/** Which scheduled retry is the current one (one cancelled too late to stop it then does nothing). */
	private int retryId;

	/**
	 * Hands {@code listener} the world now if it's read, and whenever more of it is; starts reading from
	 * {@code source} whatever is still to be read.
	 */
	public synchronized void start(CacheSource source, Consumer<WorldCache> listener)
	{
		this.source = source;
		this.listener = listener;
		if (world != null)
			listener.accept(world);
		if (!reading && retry == null)
			next();
	}

	/** Stops handing the world on and reading more (a read under way ends when the source stops serving it). */
	public synchronized void stop()
	{
		listener = null;
		retryId++;
		if (retry != null)
		{
			retry.cancel(false);
			retry = null;
		}
	}

	/** Queues the next read, if there's one to do. */
	private void next()
	{
		if (world == null)
		{
			reading = true;
			executor.execute(this::read);
		}
		else if (progress != null && !progress.missing.isEmpty() && retries < RETRY_AFTER_SECONDS.length)
		{
			long delay = firstPass + RETRY_AFTER_SECONDS[retries] * 1000L - System.currentTimeMillis();
			int id = ++retryId;
			retry = executor.schedule(() ->
			{
				synchronized (this)
				{
					if (id != retryId)
						return;
					retry = null;
					reading = true;
				}
				read();
			}, Math.max(0, delay), TimeUnit.MILLISECONDS);
		}
		else if (progress != null)
		{
			if (!progress.missing.isEmpty())
			{
				log.warn("Extended Render Distance: {} map squares weren't available from the client",
					progress.missing.size());
			}
			progress = null;
		}
	}

	private void read()
	{
		CacheSource src;
		TerrainLoader.Progress last;
		WorldCache current;
		synchronized (this)
		{
			src = source;
			last = progress;
			current = world;
		}
		TerrainLoader.Progress read = null;
		WorldCache updated = null;
		boolean again = true;
		try
		{
			if (current == null)
			{
				firstPass = System.currentTimeMillis();
				read = TerrainLoader.load(src);
				updated = WorldCache.open(src, read.terrain);
			}
			else
			{
				read = TerrainLoader.retry(src, last);
				if (read.terrain != last.terrain)
					updated = current.withTerrain(read.terrain);
			}
		}
		catch (InterruptedException e)
		{
			log.debug("Extended Render Distance: stopped reading the game cache");
		}
		catch (RuntimeException e)
		{
			log.error("Extended Render Distance: couldn't read the world from the game cache", e);
			// tried again on the next start
			again = false;
		}
		synchronized (this)
		{
			reading = false;
			if (read != null)
			{
				progress = read;
				if (current != null)
					retries++;
			}
			if (updated != null)
			{
				world = updated;
				if (listener != null)
					listener.accept(world);
			}
			if (again && listener != null)
				next();
		}
	}
}
