package com.extendedrenderdistance;

import com.extendedrenderdistance.world.CacheSource;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.IndexDataBase;

/**
 * The game cache as the running client has it ({@link Client#getIndex}): what it has on disk, and anything missing
 * is fetched by the client when asked for. The client's cache may only be touched on the client thread, so reads
 * from other threads are queued and run by {@link #service} each frame, a few milliseconds' worth at a time so
 * loading never makes the game stutter.
 */
@Singleton
public class ClientCache implements CacheSource
{
	/** Milliseconds of queued reads run per frame. */
	private static final long BUDGET_NANOS = 3_000_000;

	private final Client client;
	/** A queued read: running it completes its future; cancelling it wakes its caller. */
	private static final class Pending
	{
		final Runnable run;
		final CompletableFuture<?> done;

		Pending(Runnable run, CompletableFuture<?> done)
		{
			this.run = run;
			this.done = done;
		}
	}

	private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
	private volatile boolean open = true;

	private final Files files = new Files()
	{
		@Override
		public byte[] file(int index, int group, int file)
		{
			IndexDataBase idx = client.getIndex(index);
			return idx == null ? null : idx.loadData(group, file);
		}

		@Override
		public int[] fileIds(int index, int group)
		{
			IndexDataBase idx = client.getIndex(index);
			return idx == null ? null : idx.getFileIds(group);
		}
	};

	@Inject
	ClientCache(Client client)
	{
		this.client = client;
	}

	@Override
	public <T> T read(Reader<T> work) throws InterruptedException
	{
		if (client.isClientThread())
			return work.read(files);
		if (!open)
			throw new InterruptedException("the plugin stopped");
		CompletableFuture<T> done = new CompletableFuture<>();
		queue.add(new Pending(() ->
		{
			try
			{
				done.complete(work.read(files));
			}
			catch (RuntimeException e)
			{
				done.completeExceptionally(e);
			}
		}, done));
		if (!open)
			done.cancel(false);
		try
		{
			return done.get();
		}
		catch (ExecutionException e)
		{
			throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause()
				: new IllegalStateException(e.getCause());
		}
		catch (CancellationException e)
		{
			throw new InterruptedException("the plugin stopped");
		}
	}

	/** Runs queued reads, for a few milliseconds at most. Client thread, every frame. */
	public void service()
	{
		long until = System.nanoTime() + BUDGET_NANOS;
		Pending p;
		while (System.nanoTime() < until && (p = queue.poll()) != null)
			p.run.run();
	}

	public void open()
	{
		open = true;
	}

	/** Stops serving: queued reads are dropped (their callers see an interruption). */
	public void close()
	{
		open = false;
		Pending p;
		while ((p = queue.poll()) != null)
			p.done.cancel(false);
	}
}
