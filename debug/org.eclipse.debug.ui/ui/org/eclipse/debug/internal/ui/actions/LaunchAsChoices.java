/*******************************************************************************
 * Copyright (c) 2026 Sergii Zolotarov and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Sergii Zolotarov - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.internal.ui.actions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.expressions.Expression;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.SafeRunner;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.internal.ui.DebugUIMessages;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.internal.ui.TerminateToggleValue;
import org.eclipse.debug.internal.ui.launchConfigurations.LaunchConfigurationManager;
import org.eclipse.debug.internal.ui.launchConfigurations.LaunchShortcutExtension;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.ILaunchChoiceProvider;
import org.eclipse.debug.ui.ILaunchGroup;
import org.eclipse.debug.ui.LaunchChoice;
import org.eclipse.jface.action.LegacyActionTools;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.activities.WorkbenchActivityHelper;

/**
 * The choices of the "Run As" (or "Debug As") menu for a selection, in the
 * same order as in {@link org.eclipse.debug.ui.actions.ContextualLaunchAction}:
 * a selected shared launch configuration, the launch shortcuts that apply, and
 * the launch configurations that these shortcuts give. For an editor, the
 * choices of the {@link ILaunchChoiceProvider}s for the code at the cursor come
 * first, then {@link #SEPARATOR}.
 */
public final class LaunchAsChoices {

	/**
	 * One choice.
	 *
	 * @param label  the text in the list
	 * @param image  the icon, or <code>null</code>
	 * @param key    a key that stays the same for the same choice, to find the
	 *               last choice again
	 * @param launch    launches the choice
	 * @param configure opens the launch configuration dialog for the choice, or
	 *                  <code>null</code> if the choice cannot be configured
	 */
	public record Choice(String label, ImageDescriptor image, String key, Runnable launch, Runnable configure) {

		/** A choice that cannot be configured */
		public Choice(String label, ImageDescriptor image, String key, Runnable launch) {
			this(label, image, key, launch, null);
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/**
	 * The line between the choices for the code at the cursor and the other
	 * choices. It cannot be selected.
	 */
	public static final Choice SEPARATOR = new Choice("", null, "", () -> { //$NON-NLS-1$ //$NON-NLS-2$
	});

	private static final String PROVIDERS_EXTENSION_POINT = "launchChoiceProviders"; //$NON-NLS-1$

	/** The key of each provider choice starts with it */
	public static final String PROVIDER_KEY_PREFIX = "provider:"; //$NON-NLS-1$

	/** The providers that were created, as the contributions of the registry */
	private static final Map<IConfigurationElement, ILaunchChoiceProvider> PROVIDERS = new ConcurrentHashMap<>();
	/** Contributions whose provider could not be created, so that the log gets it once */
	private static final Set<IConfigurationElement> FAILED = ConcurrentHashMap.newKeySet();

	private LaunchAsChoices() {
	}

	/**
	 * @param selection the selection of the workbench. If an editor is active, its
	 *                  first element is the editor.
	 * @param mode      the launch mode, for example "run" or "debug"
	 * @return the choices for the code at the cursor and {@link #SEPARATOR}, if
	 *         a provider gives some, then the choices in the order of the "Run
	 *         As" menu
	 */
	public static List<Choice> of(IStructuredSelection selection, String mode) {
		LaunchConfigurationManager manager = DebugUIPlugin.getDefault().getLaunchConfigurationManager();
		List<Choice> choices = new ArrayList<>();
		Set<ILaunchConfiguration> configurations = new HashSet<>();
		if (!selection.isEmpty()) {
			ILaunchConfiguration shared = manager.isSharedConfig(selection.getFirstElement());
			try {
				if (shared != null && shared.exists() && shared.supportsMode(mode) && configurations.add(shared)) {
					choices.add(configurationChoice(shared, null, mode));
				}
			} catch (CoreException e) {
				DebugUIPlugin.log(e);
			}
		}
		List<Object> elements = new ArrayList<>(selection.toList());
		IEditorPart editor = null;
		if (!elements.isEmpty() && elements.get(0) instanceof IEditorPart editorPart) {
			editor = editorPart;
			elements.set(0, editorPart.getEditorInput());
		}
		IEvaluationContext context = DebugUIPlugin.createEvaluationContext(elements);
		context.setAllowPluginActivation(true);
		context.addVariable("selection", elements); //$NON-NLS-1$

		List<Choice> configurationChoices = new ArrayList<>();
		Map<String, LaunchShortcutExtension> applicable = new LinkedHashMap<>();
		for (LaunchShortcutExtension shortcut : manager.getLaunchShortcuts()) {
			if (!shortcut.getModes().contains(mode) || WorkbenchActivityHelper.filterItem(shortcut)
					|| !isApplicable(shortcut, context) || !isEnabled(shortcut, editor, selection)) {
				continue;
			}
			applicable.put(shortcut.getId(), shortcut);
			choices.add(shortcutChoice(shortcut, editor, selection, mode));
			ILaunchConfiguration[] given = editor != null ? shortcut.getLaunchConfigurations(editor)
					: shortcut.getLaunchConfigurations(selection);
			if (given != null) {
				for (ILaunchConfiguration configuration : given) {
					if (configurations.add(configuration)) {
						configurationChoices.add(configurationChoice(configuration, shortcut, mode));
					}
				}
			}
		}
		choices.addAll(configurationChoices);
		List<Choice> specific = providerChoices(editor, selection, applicable, mode);
		if (!specific.isEmpty()) {
			List<Choice> all = new ArrayList<>(specific);
			all.add(SEPARATOR);
			all.addAll(choices);
			return all;
		}
		return choices;
	}

	/**
	 * @return the first choice for the code at the cursor, or <code>null</code> if
	 *         there is none
	 */
	public static Choice specificChoice(List<Choice> choices) {
		return choices.contains(SEPARATOR) && choices.get(0) != SEPARATOR ? choices.get(0) : null;
	}

	/**
	 * Asks the providers whose launch shortcut applies for the choices at the
	 * cursor of the editor or, if there is no editor, for the selection
	 */
	private static List<Choice> providerChoices(IEditorPart editor, IStructuredSelection selection,
			Map<String, LaunchShortcutExtension> shortcuts, String mode) {
		List<Choice> choices = new ArrayList<>();
		IConfigurationElement[] elements = Platform.getExtensionRegistry()
				.getConfigurationElementsFor(DebugUIPlugin.getUniqueIdentifier(), PROVIDERS_EXTENSION_POINT);
		for (int e = 0; e < elements.length; e++) {
			IConfigurationElement element = elements[e];
			String shortcutId = element.getAttribute("shortcutId"); //$NON-NLS-1$
			LaunchShortcutExtension shortcut = shortcutId != null ? shortcuts.get(shortcutId) : null;
			ILaunchChoiceProvider provider = shortcut != null ? provider(element) : null;
			if (provider == null) {
				continue;
			}
			String keyPrefix = PROVIDER_KEY_PREFIX + shortcutId + ":" + e + ":"; //$NON-NLS-1$ //$NON-NLS-2$
			SafeRunner.run(() -> {
				List<LaunchChoice> given = editor != null ? provider.getChoices(editor, shortcut, mode)
						: provider.getChoices(selection, shortcut, mode);
				if (given == null) {
					return;
				}
				for (int i = 0; i < given.size(); i++) {
					LaunchChoice choice = given.get(i);
					choices.add(new Choice(choice.getLabel(),
							choice.getImage() != null ? choice.getImage() : shortcut.getImageDescriptor(),
							keyPrefix + i,
							() -> launchAsMenu(shortcut, editor, selection, choice::launch)));
				}
			});
		}
		return choices;
	}

	/**
	 * @return the provider of the contribution, created once, or
	 *         <code>null</code> if it cannot be created
	 */
	private static ILaunchChoiceProvider provider(IConfigurationElement element) {
		if (FAILED.contains(element)) {
			return null;
		}
		return PROVIDERS.computeIfAbsent(element, key -> {
			try {
				return (ILaunchChoiceProvider) key.createExecutableExtension("class"); //$NON-NLS-1$
			} catch (CoreException | RuntimeException | LinkageError e) {
				FAILED.add(key);
				DebugUIPlugin.log(e);
				return null;
			}
		});
	}

	private static boolean isApplicable(LaunchShortcutExtension shortcut, IEvaluationContext context) {
		try {
			return shortcut.evalEnablementExpression(context, shortcut.getContextualLaunchEnablementExpression());
		} catch (CoreException e) {
			DebugUIPlugin.log(e);
			return false;
		}
	}

	/**
	 * The same check as the "Run As" menu, which shows a shortcut that fails it as
	 * disabled
	 */
	private static boolean isEnabled(LaunchShortcutExtension shortcut, IEditorPart editor,
			IStructuredSelection selection) {
		if (editor != null) {
			return true;
		}
		try {
			Expression expression = shortcut.getShortcutEnablementExpression();
			if (expression == null) {
				return !selection.isEmpty();
			}
			List<?> list = selection.toList();
			IEvaluationContext context = DebugUIPlugin.createEvaluationContext(list);
			context.addVariable("selection", list); //$NON-NLS-1$
			return shortcut.evalEnablementExpression(context, expression);
		} catch (CoreException e) {
			DebugUIPlugin.log(e);
			return false;
		}
	}

	private static Choice shortcutChoice(LaunchShortcutExtension shortcut, IEditorPart editor,
			IStructuredSelection selection, String mode) {
		String label = shortcut.getContextLabel(mode);
		if (label == null) {
			label = shortcut.getLabel();
		}
		return new Choice(LegacyActionTools.removeMnemonics(label), shortcut.getImageDescriptor(),
				"shortcut:" + shortcut.getId(), //$NON-NLS-1$
				() -> launchAsMenu(shortcut, editor, selection, () -> {
					if (editor != null) {
						shortcut.launch(editor, mode);
					} else {
						shortcut.launch(selection, mode);
					}
				}), shortcutConfigure(shortcut, mode));
	}

	/**
	 * @return opens the launch configuration dialog as Ctrl+click on the shortcut
	 *         in the "Run As" menu, or <code>null</code> if the shortcut has no
	 *         configuration types. Then Ctrl+click launches, which "Configure..."
	 *         must not do.
	 */
	private static Runnable shortcutConfigure(LaunchShortcutExtension shortcut, String mode) {
		if (shortcut.getAssociatedConfigurationTypes().isEmpty()) {
			return null;
		}
		return () -> {
			Event ctrlClick = new Event();
			ctrlClick.stateMask = SWT.MOD1;
			new LaunchShortcutAction(mode, shortcut).runWithEvent(ctrlClick);
		};
	}

	/**
	 * Launches as the "Run As" menu: no Shift, so the preference decides whether
	 * to terminate the last launch of the configuration first
	 */
	private static void launchAsMenu(LaunchShortcutExtension shortcut, IEditorPart editor,
			IStructuredSelection selection, Runnable launch) {
		Object context = editor != null ? editor : selection;
		DebugUITools.storeLaunchToggleTerminate(context,
				editor != null ? Boolean.FALSE : new TerminateToggleValue(false, shortcut));
		try {
			launch.run();
		} finally {
			DebugUITools.removeLaunchToggleTerminate(context);
		}
	}

	private static Choice configurationChoice(ILaunchConfiguration configuration, LaunchShortcutExtension shortcut,
			String mode) {
		String label;
		try {
			label = NLS.bind(DebugUIMessages.LaunchShortcutAction_combineLaunchShortcutName, configuration.getName(),
					configuration.getType().getName());
		} catch (CoreException e) {
			label = configuration.getName();
		}
		ImageDescriptor image = shortcut != null ? shortcut.getImageDescriptor()
				: DebugUITools.getDefaultImageDescriptor(configuration);
		ILaunchGroup group = DebugUITools.getLaunchGroup(configuration, mode);
		Runnable configure = group == null ? null
				: () -> DebugUITools.openLaunchConfigurationDialogOnGroup(DebugUIPlugin.getShell(),
						new StructuredSelection(configuration), group.getIdentifier());
		return new Choice(label, image, "configuration:" + configuration.getName(), //$NON-NLS-1$
				() -> DebugUITools.launch(configuration, mode), configure);
	}
}
