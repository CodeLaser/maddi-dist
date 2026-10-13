/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.codelaser.maddi.j2k;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.backend.observation.Observation;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import org.jetbrains.kotlin.idea.actions.JavaToKotlinActionHandler;
import org.jetbrains.kotlin.j2k.ConverterSettings;
import org.jetbrains.kotlin.j2k.J2kPostprocessorExtension;
import org.jetbrains.kotlin.j2k.J2kPreprocessorExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/*
 `idea.sh maddi-j2k <project dir> <source root, relative to it>`: IntelliJ's J2K on every .java file under the source
 root, exactly as "Convert Java File to Kotlin File" on that root does (JavaToKotlinActionHandler.convertFiles), minus
 the dialogs: the answer to "also correct the code outside the converted files?" is yes, which is what makes the
 module's Java tests compile against the converted classes. The project must have been imported already (`idea.sh
 warmup`, with -Dproject.jdk); scripts/j2k-baseline/j2k.py in maddi-mod drives both steps.
 `idea.sh maddi-j2k <project dir> @<list>` converts the .java files the list names instead (one path per line, relative
 to the project): selecting those files and converting them together. guava's larger packages fail in one call with
 'PSI and index do not match' on files J2K itself has edited (CodeLaser/maddi-mod#36).
 */
public final class J2kStarter implements ApplicationStarter {

    @Override
    public int getRequiredModality() {
        return ApplicationStarter.NOT_IN_EDT;
    }

    @Override
    public void main(List<String> args) {
        int rc;
        try {
            rc = run(args);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            rc = 3;
        }
        System.out.flush();
        System.exit(rc);
    }

    private static void log(String message) {
        System.out.println("[maddi-j2k] " + message);
        System.out.flush();
    }

    private static int run(List<String> args) throws InterruptedException {
        if (args.size() < 3) {
            log("usage: maddi-j2k <project dir> <source root relative to it>");
            return 2;
        }
        // args.get(0) is the command name
        Path projectDir = Path.of(args.get(1)).toAbsolutePath().normalize();
        List<Path> listed = null;
        if (args.get(2).startsWith("@")) {
            try {
                listed = java.nio.file.Files.readAllLines(Path.of(args.get(2).substring(1))).stream()
                        .filter(l -> !l.isBlank()).map(l -> projectDir.resolve(l.strip()).normalize()).toList();
            } catch (java.io.IOException e) {
                log("cannot read " + args.get(2) + ": " + e);
                return 2;
            }
        }
        Path sourceRoot = listed != null ? projectDir : projectDir.resolve(args.get(2)).normalize();

        Project project = ProjectUtil.openOrImport(projectDir, null, true);
        if (project == null) {
            log("could not open " + projectDir);
            return 1;
        }
        BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> Observation.INSTANCE.awaitConfiguration(project, null, continuation));
        DumbService.getInstance(project).waitForSmartMode();
        log("project open and indexed: " + project.getName());

        VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceRoot);
        if (root == null) {
            log("no source root " + sourceRoot);
            return 1;
        }
        List<Path> only = listed;
        List<PsiJavaFile> files = ReadAction.compute(() -> {
            List<PsiJavaFile> list = new ArrayList<>();
            PsiManager psiManager = PsiManager.getInstance(project);
            if (only != null) {
                for (Path path : only) {
                    VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(path);
                    if (vf != null && psiManager.findFile(vf) instanceof PsiJavaFile javaFile) list.add(javaFile);
                }
                return list;
            }
            VfsUtilCore.iterateChildrenRecursively(root, null, vf -> {
                if (!vf.isDirectory() && "java".equals(vf.getExtension())) {
                    PsiFile psi = psiManager.findFile(vf);
                    if (psi instanceof PsiJavaFile javaFile) list.add(javaFile);
                }
                return true;
            });
            return list;
        });
        if (files.isEmpty()) {
            log("no .java files under " + sourceRoot);
            return 1;
        }
        Module module = ReadAction.compute(() -> ModuleUtilCore.findModuleForFile(files.getFirst().getVirtualFile(), project));
        if (module == null) {
            log("no module for " + files.getFirst().getVirtualFile().getPath());
            return 1;
        }
        log("converting " + files.size() + " files of module " + module.getName());

        long start = System.currentTimeMillis();
        BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) ->
                JavaToKotlinActionHandler.INSTANCE.convertFiles(files, project, module,
                        true, // enableExternalCodeProcessing: correct the usages outside the converted files ...
                        false, // askExternalCodeProcessing: ... without the dialog
                        null, ConverterSettings.Companion.getDefaultSettings(),
                        J2kPreprocessorExtension.Companion.getEP_NAME().getExtensionList(),
                        J2kPostprocessorExtension.Companion.getEP_NAME().getExtensionList(),
                        continuation));
        ApplicationManager.getApplication().invokeAndWait(() -> FileDocumentManager.getInstance().saveAllDocuments());
        log("converted in " + (System.currentTimeMillis() - start) / 1000 + "s");
        return 0;
    }
}
