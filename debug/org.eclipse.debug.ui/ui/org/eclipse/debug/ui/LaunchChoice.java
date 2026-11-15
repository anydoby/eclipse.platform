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

import java.util.Objects;

import org.eclipse.jface.resource.ImageDescriptor;

/**
 * One launch choice of an {@link ILaunchChoiceProvider}.
 *
 * @since 3.23
 */
public final class LaunchChoice {

	private final String label;
	private final ImageDescriptor image;
	private final Runnable launch;

	/**
	 * @param label  the text that the workbench shows, for example "JUnit
	 *               Test: CalculatorTest.adds()"
	 * @param image  the icon, or <code>null</code> for the icon of the launch
	 *               shortcut
	 * @param launch launches the choice. The workbench calls it on the UI
	 *               thread.
	 */
	public LaunchChoice(String label, ImageDescriptor image, Runnable launch) {
		this.label = Objects.requireNonNull(label);
		this.image = image;
		this.launch = Objects.requireNonNull(launch);
	}

	/**
	 * @return the text that the workbench shows
	 */
	public String getLabel() {
		return label;
	}

	/**
	 * @return the icon, or <code>null</code> for the icon of the launch shortcut
	 */
	public ImageDescriptor getImage() {
		return image;
	}

	/**
	 * Launches the choice.
	 */
	public void launch() {
		launch.run();
	}
}
