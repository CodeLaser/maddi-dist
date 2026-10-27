/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.codelaser.maddi.ide.eclipse;

import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.source.ISourceViewerExtension5;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Re-queries the code minings of every open editor when a new analysis result lands.
 * <p>
 * JDT's mining reconciler refreshes on document change; an analysis run changes no document, so without this
 * the inline hints would only appear after the next keystroke. Registered on {@link MaddiResults} by the
 * activator, and also called when a preference changes the hints' content.
 */
public final class MaddiCodeMiningRefresher implements Runnable {

    @Override
    public void run() {
        // MaddiResults notifies from the analysis job; touching the workbench requires the UI thread
        Display display = Display.getDefault();
        if (display.isDisposed()) return;
        display.asyncExec(MaddiCodeMiningRefresher::refreshOpenEditors);
    }

    public static void refreshOpenEditors() {
        if (!PlatformUI.isWorkbenchRunning()) return;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference reference : page.getEditorReferences()) {
                    // false: refresh what is already open; never restore an editor just to update hints
                    refresh(reference.getEditor(false));
                }
            }
        }
    }

    private static void refresh(IEditorPart editor) {
        if (!(editor instanceof ITextEditor textEditor)) return;
        // AbstractTextEditor hands out its source viewer under ITextOperationTarget; the viewer is what
        // owns the installed mining providers.
        Object target = textEditor.getAdapter(ITextOperationTarget.class);
        if (target instanceof ISourceViewerExtension5 viewer && viewer.hasCodeMiningProviders()) {
            viewer.updateCodeMinings();
        }
    }
}
