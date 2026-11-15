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

import java.util.List;
import java.util.Locale;

import org.eclipse.debug.internal.ui.actions.LaunchAsChoices.Choice;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Shows the launch choices with a filter field. Enter launches the selected
 * choice. Ctrl+Enter or "Configure..." opens the launch configuration dialog
 * for it, as Ctrl+click in the "Run As" menu. Up and Down select another
 * choice, also while the filter field has the focus.
 */
public final class LaunchAsDialog extends Dialog {

	/** The return code of {@link #open()} after "Configure..." */
	public static final int CONFIGURE_ID = IDialogConstants.CLIENT_ID + 1;

	private final String title;
	private final String buttonLabel;
	private final List<Choice> choices;
	private final Choice initial;
	private Text filterText;
	private TableViewer viewer;
	private Choice result;
	/** The row of the last selected choice, to pass the separator in the same direction */
	private int lastIndex;

	/**
	 * @param buttonLabel the text of the OK button, for example "Run"
	 * @param initial     the choice to select first, or null for the first choice
	 */
	public LaunchAsDialog(Shell parent, String title, String buttonLabel, List<Choice> choices, Choice initial) {
		super(parent);
		this.title = title;
		this.buttonLabel = buttonLabel;
		this.choices = choices;
		this.initial = initial;
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(title);
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		LocalResourceManager images = new LocalResourceManager(JFaceResources.getResources(), area);

		filterText = new Text(area, SWT.SINGLE | SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
		filterText.setMessage(ActionMessages.LaunchAsDialog_filter);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(filterText);

		viewer = new TableViewer(area, SWT.SINGLE | SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
		viewer.setContentProvider(ArrayContentProvider.getInstance());
		viewer.setLabelProvider(new LabelProvider() {
			@Override
			public String getText(Object element) {
				return ((Choice) element).label();
			}

			@Override
			public Image getImage(Object element) {
				Choice choice = (Choice) element;
				return choice.image() != null ? images.create(choice.image()) : null;
			}
		});
		viewer.addFilter(new ViewerFilter() {
			@Override
			public boolean select(Viewer v, Object parentElement, Object element) {
				String filter = filterText.getText().trim().toLowerCase(Locale.ROOT);
				if (element == LaunchAsChoices.SEPARATOR) {
					return filter.isEmpty();
				}
				return filter.isEmpty() || ((Choice) element).label().toLowerCase(Locale.ROOT).contains(filter);
			}
		});
		viewer.setInput(choices);
		Table table = viewer.getTable();
		// Show all choices, up to 60% of the screen height
		int itemHeight = table.getItemHeight();
		int maxRows = maxRows(parent, itemHeight);
		int rows = Math.min(Math.max(choices.size(), 3), maxRows);
		GridDataFactory.fillDefaults().grab(true, true)
				.hint(convertWidthInCharsToPixels(60), itemHeight * rows + 2 * table.getBorderWidth()).applyTo(table);
		drawSeparator(table);
		table.addListener(SWT.MouseDoubleClick, event -> {
			TableItem item = table.getItem(new Point(event.x, event.y));
			if (item != null && item.getData() != LaunchAsChoices.SEPARATOR) {
				okPressed();
			}
		});

		filterText.addModifyListener(event -> {
			viewer.refresh();
			if (selected() == null && table.getItemCount() > 0) {
				viewer.setSelection(new StructuredSelection(table.getItem(0).getData()), true);
			}
			updateOkButton();
		});
		filterText.addListener(SWT.KeyDown, event -> {
			if (event.keyCode == SWT.ARROW_DOWN || event.keyCode == SWT.ARROW_UP) {
				int count = table.getItemCount();
				if (count > 0) {
					int direction = event.keyCode == SWT.ARROW_DOWN ? 1 : -1;
					select(table, table.getSelectionIndex() + direction, direction);
				}
				event.doit = false;
			}
		});
		viewer.addSelectionChangedListener(event -> {
			int index = table.getSelectionIndex();
			if (((IStructuredSelection) event.getSelection()).getFirstElement() == LaunchAsChoices.SEPARATOR) {
				// Up or Down in the table, or a click: go on in the same direction
				int direction = index < lastIndex ? -1 : 1;
				select(table, index + direction, direction);
			} else if (index >= 0) {
				lastIndex = index;
			}
			updateOkButton();
		});

		// Ctrl+Enter: configure. Cancel the traversal, so that Enter does not also
		// press the default button.
		Listener ctrlEnter = event -> {
			boolean enter = event.type == SWT.Traverse ? event.detail == SWT.TRAVERSE_RETURN
					: event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR;
			if (enter && (event.stateMask & SWT.MOD1) != 0) {
				event.doit = false;
				if (event.type == SWT.Traverse) {
					event.detail = SWT.TRAVERSE_NONE;
				}
				configurePressed();
			}
		};
		for (Control control : new Control[] { filterText, table }) {
			control.addListener(SWT.Traverse, ctrlEnter);
			control.addListener(SWT.KeyDown, ctrlEnter);
		}

		Choice first = initial != null && choices.contains(initial) ? initial : choices.isEmpty() ? null : choices.get(0);
		if (first != null) {
			viewer.setSelection(new StructuredSelection(first), true);
		}
		return area;
	}

	/**
	 * Selects the choice at the index. Goes on in the direction past the
	 * separator, and stays at the first or last choice.
	 */
	private void select(Table table, int index, int direction) {
		int count = table.getItemCount();
		int i = Math.max(0, Math.min(count - 1, index));
		while (i >= 0 && i < count && table.getItem(i).getData() == LaunchAsChoices.SEPARATOR) {
			i += direction;
		}
		if (i < 0 || i >= count) {
			// Only the separator is in that direction: go the other way
			i = Math.max(0, Math.min(count - 1, index));
			while (i >= 0 && i < count && table.getItem(i).getData() == LaunchAsChoices.SEPARATOR) {
				i -= direction;
			}
		}
		if (i >= 0 && i < count) {
			viewer.setSelection(new StructuredSelection(table.getItem(i).getData()), true);
		}
	}

	/**
	 * The number of rows that fit into 60% of the height of the monitor of the
	 * dialog, at least 5
	 */
	public static int maxRows(Composite parent, int itemHeight) {
		return Math.max(5, parent.getShell().getMonitor().getClientArea().height * 6 / 10 / itemHeight);
	}

	/** Draws the separator as a line, and never as selected */
	private static void drawSeparator(Table table) {
		table.addListener(SWT.EraseItem, event -> {
			if (event.item.getData() == LaunchAsChoices.SEPARATOR) {
				event.detail &= ~(SWT.SELECTED | SWT.HOT | SWT.FOCUSED);
			}
		});
		table.addListener(SWT.PaintItem, event -> {
			if (event.item.getData() == LaunchAsChoices.SEPARATOR) {
				Rectangle bounds = ((TableItem) event.item).getBounds();
				int y = bounds.y + bounds.height / 2;
				event.gc.setForeground(table.getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
				event.gc.drawLine(bounds.x + 4, y, table.getClientArea().width - 4, y);
			}
		});
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, CONFIGURE_ID, ActionMessages.LaunchAsDialog_configure, false);
		super.createButtonsForButtonBar(parent);
		getButton(IDialogConstants.OK_ID).setText(buttonLabel);
		updateOkButton();
	}

	@Override
	public void create() {
		super.create();
		filterText.setFocus();
	}

	private void updateOkButton() {
		Choice choice = selected();
		if (getButton(IDialogConstants.OK_ID) != null) {
			getButton(IDialogConstants.OK_ID).setEnabled(choice != null);
		}
		if (getButton(CONFIGURE_ID) != null) {
			getButton(CONFIGURE_ID).setEnabled(choice != null && choice.configure() != null);
		}
	}

	@Override
	protected void buttonPressed(int buttonId) {
		if (buttonId == CONFIGURE_ID) {
			configurePressed();
		} else {
			super.buttonPressed(buttonId);
		}
	}

	/** Closes the dialog with {@link #CONFIGURE_ID}, if the choice can be configured */
	private void configurePressed() {
		Choice choice = selected();
		if (choice != null && choice.configure() != null) {
			result = choice;
			setReturnCode(CONFIGURE_ID);
			close();
		}
	}

	private Choice selected() {
		return ((IStructuredSelection) viewer.getSelection()).getFirstElement() instanceof Choice choice
				&& choice != LaunchAsChoices.SEPARATOR ? choice : null;
	}

	@Override
	protected void okPressed() {
		result = selected();
		if (result != null) {
			super.okPressed();
		}
	}

	/** @return the selected choice after OK or "Configure...", or null */
	public Choice getResult() {
		return result;
	}

	/** For the tests: the filter field */
	public Text getFilterText() {
		return filterText;
	}

	/** For the tests: the "Configure..." button */
	public Button getConfigureButton() {
		return getButton(CONFIGURE_ID);
	}

	/** For the tests: the list */
	public TableViewer getViewer() {
		return viewer;
	}
}
