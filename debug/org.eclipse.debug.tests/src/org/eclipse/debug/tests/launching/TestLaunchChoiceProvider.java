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
package org.eclipse.debug.tests.launching;

import java.util.List;

import org.eclipse.debug.ui.ILaunchChoiceProvider;
import org.eclipse.debug.ui.ILaunchShortcut;
import org.eclipse.debug.ui.LaunchChoice;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;

/**
 * Gives one choice at the cursor, which launches the editor with the shortcut.
 * For a selection, it gives one choice only if the selection is
 * {@link #SELECTION_WITH_CHOICE}.
 */
public class TestLaunchChoiceProvider implements ILaunchChoiceProvider {

	static final TestLaunchShortcut.Target SELECTION_WITH_CHOICE = new TestLaunchShortcut.Target("with choice"); //$NON-NLS-1$

	@Override
	public List<LaunchChoice> getChoices(IEditorPart editor, ILaunchShortcut shortcut, String mode) {
		return List.of(new LaunchChoice("Test at the cursor", null, () -> shortcut.launch(editor, mode))); //$NON-NLS-1$
	}

	@Override
	public List<LaunchChoice> getChoices(ISelection selection, ILaunchShortcut shortcut, String mode) {
		if (selection instanceof IStructuredSelection structured
				&& SELECTION_WITH_CHOICE.equals(structured.getFirstElement())) {
			return List.of(new LaunchChoice("Test in the selection", null, () -> shortcut.launch(selection, mode))); //$NON-NLS-1$
		}
		return null;
	}
}
