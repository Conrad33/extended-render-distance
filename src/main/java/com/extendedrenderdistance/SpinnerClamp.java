package com.extendedrenderdistance;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ContainerEvent;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Singleton;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

/**
 * Makes this plugin's number settings take a typed value that's out of range as the nearest limit (typing 9999999
 * gives the maximum) instead of quietly throwing it away, as RuneLite's spinners do. The config panel is RuneLite's,
 * so its spinners are caught as they're added (an AWT listener for added components) and recognised by their
 * label's tooltip, which is this plugin's own description of the setting; their text parsing is then wrapped.
 * Swing thread.
 */
@Slf4j
@Singleton
public class SpinnerClamp
{
	/** Tooltip of each setting's label, as RuneLite's config panel writes it, to the setting's {min, max}. */
	private final Map<String, int[]> ranges = new HashMap<>();
	private final AWTEventListener listener = this::componentAdded;
	private boolean installed;

	public void install()
	{
		ranges.clear();
		range(ExtendedRenderDistanceConfig.TERRAIN_NAME, ExtendedRenderDistanceConfig.TERRAIN_DESCRIPTION,
			ExtendedRenderDistanceConfig.MIN_TERRAIN);
		range(ExtendedRenderDistanceConfig.STRUCTURE_NAME, ExtendedRenderDistanceConfig.STRUCTURE_DESCRIPTION, 0);
		range(ExtendedRenderDistanceConfig.SCENERY_NAME, ExtendedRenderDistanceConfig.SCENERY_DESCRIPTION, 0);
		Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.CONTAINER_EVENT_MASK);
		installed = true;
	}

	private void range(String name, String description, int min)
	{
		ranges.put("<html>" + name + ":<br>" + description + "</html>",
			new int[]{min, ExtendedRenderDistanceConfig.MAX_DISTANCE});
	}

	public void uninstall()
	{
		if (installed)
			Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
		installed = false;
	}

	private void componentAdded(AWTEvent e)
	{
		if (e.getID() != ContainerEvent.COMPONENT_ADDED || !(((ContainerEvent) e).getChild() instanceof JSpinner))
			return;
		JSpinner spinner = (JSpinner) ((ContainerEvent) e).getChild();
		int[] range = rangeFor(((ContainerEvent) e).getContainer());
		if (range != null && spinner.getModel() instanceof SpinnerNumberModel)
			SwingUtilities.invokeLater(() -> clamp(spinner, range));
	}

	/** The range of the setting whose label sits next to the spinner, if it's one of ours. */
	private int[] rangeFor(Container item)
	{
		for (Component c : item.getComponents())
		{
			if (c instanceof JLabel && ((JLabel) c).getToolTipText() != null)
			{
				int[] r = ranges.get(((JLabel) c).getToolTipText());
				if (r != null)
					return r;
			}
		}
		return null;
	}

	private static void clamp(JSpinner spinner, int[] range)
	{
		if (!(spinner.getEditor() instanceof JSpinner.DefaultEditor))
			return;
		JFormattedTextField field = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
		JFormattedTextField.AbstractFormatterFactory delegate = field.getFormatterFactory();
		if (delegate instanceof ClampingFactory)
			return;
		field.setFormatterFactory(new ClampingFactory(delegate, range[0], range[1]));
	}

	/** Formats as before; reads any whole number, brought within [min, max]. */
	private static final class ClampingFactory extends JFormattedTextField.AbstractFormatterFactory
	{
		private final JFormattedTextField.AbstractFormatterFactory delegate;
		private final int min, max;

		ClampingFactory(JFormattedTextField.AbstractFormatterFactory delegate, int min, int max)
		{
			this.delegate = delegate;
			this.min = min;
			this.max = max;
		}

		@Override
		public JFormattedTextField.AbstractFormatter getFormatter(JFormattedTextField tf)
		{
			JFormattedTextField.AbstractFormatter inner = delegate.getFormatter(tf);
			return new JFormattedTextField.AbstractFormatter()
			{
				@Override
				public Object stringToValue(String text) throws ParseException
				{
					String digits = text == null ? "" : text.replaceAll("[^0-9-]", "");
					if (digits.isEmpty() || digits.equals("-"))
						throw new ParseException("not a number: " + text, 0);
					long v;
					try
					{
						v = Long.parseLong(digits);
					}
					catch (NumberFormatException e)
					{
						// longer than a long: as big (or small) as it gets
						v = digits.startsWith("-") ? Long.MIN_VALUE : Long.MAX_VALUE;
					}
					return (int) Math.max(min, Math.min(max, v));
				}

				@Override
				public String valueToString(Object value) throws ParseException
				{
					return inner.valueToString(value);
				}
			};
		}
	}
}
