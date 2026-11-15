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
package org.eclipse.debug.ui;

import java.util.List;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;

/**
 * Gives launch choices for the code at the cursor of an editor or for the
 * selection of a view, for example "JUnit Test: CalculatorTest.adds()" for the
 * test method at the cursor. The workbench shows these choices with the launch
 * shortcuts, for example in the "Run As..." dialog. A launch with a choice must
 * have the same result as a launch with its shortcut, but for a smaller part of
 * the code.
 * <p>
 * Clients contribute implementations with the extension point
 * <code>org.eclipse.debug.ui.launchChoiceProviders</code>. A provider belongs
 * to one launch shortcut. The workbench asks it only when that shortcut applies
 * to the editor or selection and supports the launch mode. As
 * {@link ILaunchShortcut}, the interface has one method for an editor and one
 * for a selection. Both return no choices by default, thus a provider
 * implements only the methods that it needs.
 * </p>
 * <p>
 * The workbench calls the provider on the UI thread, while it builds a menu or
 * a dialog. Thus a provider must be fast: it must not wait for a build, an
 * index or a server.
 * </p>
 *
 * @since 3.23
 */
public interface ILaunchChoiceProvider {

	/**
	 * Returns the launch choices for the code at the cursor of the editor.
	 *
	 * @param editor   the active editor
	 * @param shortcut the launch shortcut of the provider, to launch with. It is
	 *                 a proxy that sends the calls on to the contributed class,
	 *                 not an instance of that class.
	 * @param mode     the launch mode, for example
	 *                 {@link org.eclipse.debug.core.ILaunchManager#RUN_MODE}
	 * @return the choices, the most specific one first, or an empty list or
	 *         <code>null</code> if there are none
	 */
	default List<LaunchChoice> getChoices(IEditorPart editor, ILaunchShortcut shortcut, String mode) {
		return List.of();
	}

	/**
	 * Returns the launch choices for the selection of a view, for example the
	 * test method that is selected in a tree.
	 *
	 * @param selection the selection of the active view
	 * @param shortcut  the launch shortcut of the provider, to launch with. It is
	 *                  a proxy that sends the calls on to the contributed class,
	 *                  not an instance of that class.
	 * @param mode      the launch mode, for example
	 *                  {@link org.eclipse.debug.core.ILaunchManager#RUN_MODE}
	 * @return the choices, the most specific one first, or an empty list or
	 *         <code>null</code> if there are none
	 */
	default List<LaunchChoice> getChoices(ISelection selection, ILaunchShortcut shortcut, String mode) {
		return List.of();
	}
}
