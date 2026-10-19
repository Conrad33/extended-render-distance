package com.extendedrenderdistance.gl;

import java.lang.reflect.Field;
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
 * included; before wrapping, the chain of wrappers is followed (through their fields holding draw callbacks) and if
 * our proxy is already somewhere in it, nothing is done, so two wrappers never pile up. 117 HD has its own pipeline
 * and is left alone. Client thread.
 */
@Slf4j
@Singleton
public class CallbacksWrapper
{
	/** How deep a chain of wrappers is followed. */
	private static final int MAX_CHAIN = 8;

	private final Client client;
	private final FarRenderer far;
	private DrawCallbacks delegate;
	private DrawCallbacks proxy;
	private boolean drewThisFrame;
	private String warnedAbout;

	@Inject
	CallbacksWrapper(Client client, FarRenderer far)
	{
		this.client = client;
		this.far = far;
	}

	/** Wraps whatever is drawing, unless our proxy is already in the chain. Call every frame. */
	public void check()
	{
		DrawCallbacks current = client.getDrawCallbacks();
		if (current == null || current == proxy || (proxy != null && inChain(current, proxy, 0)))
			return;
		String name = current.getClass().getName();
		if (name.startsWith("rs117."))
		{
			if (!name.equals(warnedAbout))
			{
				log.info("Extended Render Distance: {} draws with its own pipeline; the far world only works with the GPU plugin", name);
				warnedAbout = name;
			}
			return;
		}
		delegate = current;
		proxy = (DrawCallbacks) Proxy.newProxyInstance(DrawCallbacks.class.getClassLoader(),
			new Class<?>[]{DrawCallbacks.class}, handler(current));
		client.setDrawCallbacks(proxy);
		log.debug("Extended Render Distance: wrapped {}", name);
	}

	/** Whether {@code target} is {@code c} or wraps it, following fields that hold draw callbacks. */
	private static boolean inChain(Object c, Object target, int depth)
	{
		if (c == target)
			return true;
		if (c == null || depth >= MAX_CHAIN || Proxy.isProxyClass(c.getClass()))
			return false;
		for (Class<?> k = c.getClass(); k != null && k != Object.class; k = k.getSuperclass())
		{
			if (k.getName().startsWith("net.runelite.client.plugins.gpu."))
				return false;
			for (Field f : k.getDeclaredFields())
			{
				if (!DrawCallbacks.class.isAssignableFrom(f.getType()))
					continue;
				try
				{
					f.setAccessible(true);
					if (inChain(f.get(c), target, depth + 1))
						return true;
				}
				catch (RuntimeException | IllegalAccessException e)
				{
					// can't look inside: treat as not ours
				}
			}
		}
		return false;
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
				afterPreSceneDraw(method, args);
			else if ("draw".equals(name) && args != null && args.length == 1)
				drewThisFrame = false;
			return result;
		};
	}

	private void afterPreSceneDraw(Method method, Object[] args)
	{
		Scene scene = (Scene) args[0];
		if (drewThisFrame || scene == null || scene.getWorldViewId() != WorldView.TOPLEVEL)
			return;
		drewThisFrame = true;
		// (scene, [projection,] camera x, y, z, pitch, yaw, ...): the projection can be null, so go by the signature
		int i = method.getParameterTypes()[1] == Projection.class ? 2 : 1;
		try
		{
			far.draw((float) args[i], (float) args[i + 1], (float) args[i + 2], (float) args[i + 3], (float) args[i + 4]);
		}
		catch (RuntimeException e)
		{
			log.error("Extended Render Distance: drawing the far world failed", e);
		}
	}
}
