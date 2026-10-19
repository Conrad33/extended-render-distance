package com.horizon.gl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Slips the {@link FarRenderer} into the GPU plugin's frame. The client's draw callbacks are swapped for a proxy
 * that passes every call straight through, and after the top-level {@code preSceneDraw} (framebuffer bound and
 * cleared, sky drawn, scene not yet drawn) draws the far world.
 * <p>
 * Other plugins wrap the callbacks the same way (EV Bloat does), so whatever is installed is wrapped, wrappers
 * included, and the proxy is only put back if it stops being called (another plugin replaced it rather than
 * wrapping it), at most once a second, so two wrappers never fight over the slot. 117 HD has its own pipeline and
 * is left alone. Client thread.
 */
@Slf4j
@Singleton
public class CallbacksWrapper
{
	/** Frames without our hook being called before the proxy is put back. */
	private static final int LOST_AFTER = 10;
	/** Frames between wraps at most. */
	private static final int REWRAP_GAP = 60;

	private final Client client;
	private final FarRenderer far;
	private DrawCallbacks delegate;
	private DrawCallbacks proxy;
	private boolean drewThisFrame;
	private long frame, lastCalled, lastWrapped = Long.MIN_VALUE / 2;
	private String warnedAbout;

	@Inject
	CallbacksWrapper(Client client, FarRenderer far)
	{
		this.client = client;
		this.far = far;
	}

	/** Wraps whatever is drawing, if our hook isn't being called. Call every frame. */
	public void check()
	{
		frame++;
		DrawCallbacks current = client.getDrawCallbacks();
		if (current == null || current == proxy)
			return;
		if (frame - lastCalled < LOST_AFTER || frame - lastWrapped < REWRAP_GAP)
			return;
		String name = current.getClass().getName();
		if (name.startsWith("rs117."))
		{
			if (!name.equals(warnedAbout))
			{
				log.info("Horizon: {} draws with its own pipeline; the far world only works with the GPU plugin", name);
				warnedAbout = name;
			}
			return;
		}
		delegate = current;
		proxy = (DrawCallbacks) Proxy.newProxyInstance(DrawCallbacks.class.getClassLoader(),
			new Class<?>[]{DrawCallbacks.class}, handler(current));
		client.setDrawCallbacks(proxy);
		lastWrapped = frame;
		log.debug("Horizon: wrapped {}", name);
	}

	/** Takes the proxy out, if it's still the one installed. */
	public void unwrap()
	{
		if (proxy != null && client.getDrawCallbacks() == proxy)
			client.setDrawCallbacks(delegate);
		proxy = null;
		delegate = null;
		far.free();
	}

	private InvocationHandler handler(DrawCallbacks target)
	{
		return (Object p, Method method, Object[] args) ->
		{
			Object result;
			try
			{
				result = method.invoke(target, args);
			}
			catch (InvocationTargetException e)
			{
				throw e.getCause();
			}
			String name = method.getName();
			if ("preSceneDraw".equals(name))
				afterPreSceneDraw(args);
			else if ("draw".equals(name) && args != null && args.length == 1)
				drewThisFrame = false;
			return result;
		};
	}

	private void afterPreSceneDraw(Object[] args)
	{
		lastCalled = frame;
		Scene scene = (Scene) args[0];
		if (drewThisFrame || scene == null || scene.getWorldViewId() != WorldView.TOPLEVEL)
			return;
		drewThisFrame = true;
		// (scene, [projection,] camera x, y, z, pitch, yaw, ...)
		int i = args[1] instanceof Projection ? 2 : 1;
		try
		{
			far.draw((float) args[i], (float) args[i + 1], (float) args[i + 2], (float) args[i + 3], (float) args[i + 4]);
		}
		catch (RuntimeException e)
		{
			log.error("Horizon: drawing the far world failed", e);
		}
	}
}
