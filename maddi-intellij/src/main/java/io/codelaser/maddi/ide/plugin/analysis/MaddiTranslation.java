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

package io.codelaser.maddi.ide.plugin.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.history.LocalHistory;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import io.codelaser.maddi.ide.client.AnalysisModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Translate to Kotlin with maddi": Java sources to Kotlin by maddi's translator, which reads the analysis's verdicts
 * (a read-only {@code List} where nothing modifies it, {@code T?} where null can occur), from the daemon's last
 * analysis, analysed first when the files changed since.
 * <ul>
 *   <li>A file, or a few: each on its own, the rest of the code staying Java, shown beside its Java in a diff. A
 *       preview: a single translated file in a Java code base needs interop the translator does not yet write.</li>
 *   <li>A module: its main sources as one Kotlin code base, written under {@code build/maddi-kotlin/} with the
 *       translator's report; then, on request, in place of the Java sources.</li>
 * </ul>
 */
public final class MaddiTranslation {
    private static final Logger LOG = Logger.getInstance(MaddiTranslation.class);
    private static final String NOTIFICATION_GROUP = "maddi";
    static final String OUTPUT = "build/maddi-kotlin";

    private MaddiTranslation() {
    }

    /**
     * What to translate: Java source files by their primary types, and the module when it is the whole of one.
     *
     * @param javaFiles each Java file and the qualified names of its top-level types
     */
    public record Selection(Map<VirtualFile, List<String>> javaFiles, @Nullable Module module) {
        public List<String> types() {
            return javaFiles.values().stream().flatMap(List::stream).toList();
        }
    }

    /** The selection of an action: a module (its main sources), else the Java files among the files; null for none. */
    public static @Nullable Selection of(Project project, @Nullable Module module, VirtualFile @Nullable [] files) {
        return ReadAction.compute(() -> {
            List<VirtualFile> javaFiles = new ArrayList<>();
            if (module != null) {
                for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots(false)) {
                    VfsUtilCore.iterateChildrenRecursively(root, null, f -> {
                        if (!f.isDirectory() && "java".equals(f.getExtension())) javaFiles.add(f);
                        return true;
                    });
                }
            } else if (files != null) {
                for (VirtualFile f : files) {
                    if (!f.isDirectory() && "java".equals(f.getExtension())) javaFiles.add(f);
                }
            }
            Map<VirtualFile, List<String>> types = new LinkedHashMap<>();
            for (VirtualFile f : javaFiles) {
                PsiFile psi = PsiManager.getInstance(project).findFile(f);
                if (!(psi instanceof PsiJavaFile java)) continue;
                List<String> names = new ArrayList<>();
                for (PsiClass c : java.getClasses()) {
                    if (c.getQualifiedName() != null) names.add(c.getQualifiedName());
                }
                if (!names.isEmpty()) types.put(f, names);
            }
            return types.isEmpty() ? null : new Selection(types, module);
        });
    }

    /**
     * Translate the selection: at once when the last analysis has seen these files as they are, else after building
     * the project and analysing it again. On the EDT.
     */
    public static void start(Project project, Selection selection) {
        FileDocumentManager.getInstance().saveAllDocuments();
        MaddiAnalysisService service = MaddiAnalysisService.getInstance(project);
        if (service.isRunning()) {
            tell(project, "An analysis is running; translate when it has finished.", NotificationType.INFORMATION);
            return;
        }
        long seen = service.lastCompleteRunStarted();
        boolean fresh = seen > 0 && service.daemon().isAlive()
                        && selection.javaFiles().keySet().stream().allMatch(f -> f.getTimeStamp() < seen);
        if (fresh) {
            translateInBackground(project, selection);
            return;
        }
        // the translation reads the analysis's verdicts: an analysis of the code as it is now comes first
        CompilerManager.getInstance(project).make((aborted, errors, warnings, context) -> {
            if (!aborted) service.analyzeInBackground(() -> translateInBackground(project, selection));
        });
    }

    private static void translateInBackground(Project project, Selection selection) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "maddi: translating to Kotlin", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    MaddiAnalysisService service = MaddiAnalysisService.getInstance(project);
                    JsonNode node = service.daemon().translate("tr-" + System.nanoTime(), selection.types(),
                            selection.module() != null);
                    if (!AnalysisModel.TRANSLATE_RESULT.equals(node.path("type").asText())) {
                        tell(project, "Translation failed: " + node.path("message").asText("")
                                        + " (a daemon from before the translate request? Restart it from the maddi"
                                        + " tool window.)", NotificationType.ERROR);
                        return;
                    }
                    AnalysisModel.TranslateResult result = service.daemon().client().objectMapper()
                            .treeToValue(node, AnalysisModel.TranslateResult.class);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) return;
                        if (selection.module() == null) preview(project, selection, result);
                        else writeModule(project, selection, result);
                    });
                } catch (Exception e) {
                    LOG.warn("maddi: translation failed", e);
                    tell(project, "Translation failed: " + e.getMessage(), NotificationType.ERROR);
                }
            }
        });
    }

    /* Each Kotlin file beside its Java, in IntelliJ's diff viewer; the translator's messages in a notification. */
    private static void preview(Project project, Selection selection, AnalysisModel.TranslateResult result) {
        FileType kotlin = FileTypeManager.getInstance().getFileTypeByExtension("kt");
        for (AnalysisModel.TranslatedFile file : result.files()) {
            VirtualFile java = javaFileOf(selection, file.type());
            if (java == null) continue;
            DiffContentFactory contents = DiffContentFactory.getInstance();
            String name = file.path().substring(file.path().lastIndexOf('/') + 1);
            DiffManager.getInstance().showDiff(project, new SimpleDiffRequest(
                    java.getName() + " → " + name + " (maddi, a preview: the rest of the code stays Java)",
                    contents.create(project, java), contents.create(project, file.source(), kotlin),
                    java.getName(), name));
        }
        String summary = summary(result);
        if (!summary.isEmpty()) tell(project, summary, NotificationType.INFORMATION);
    }

    /*
     The module's Kotlin under build/maddi-kotlin of its content root, with the report; the notification offers to put
     it in place of the Java sources.
     */
    private static void writeModule(Project project, Selection selection, AnalysisModel.TranslateResult result) {
        Module module = selection.module();
        VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
        if (roots.length == 0) return;
        Path out = roots[0].toNioPath().resolve(OUTPUT);
        try {
            for (AnalysisModel.TranslatedFile file : result.files()) {
                Path target = out.resolve(file.path());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.source(), StandardCharsets.UTF_8);
            }
            Files.createDirectories(out);
            Files.write(out.resolve("translation-report.txt"), report(result), StandardCharsets.UTF_8);
        } catch (IOException e) {
            tell(project, "Could not write the translation: " + e.getMessage(), NotificationType.ERROR);
            return;
        }
        VirtualFile outDir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(out);
        if (outDir != null) outDir.refresh(false, true);
        Notification notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Module " + module.getName() + " in Kotlin: " + result.files().size()
                                    + " file(s) in " + OUTPUT + ". " + summary(result), NotificationType.INFORMATION);
        notification.addAction(NotificationAction.createSimple("Open the report", () -> {
            VirtualFile report = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(
                    out.resolve("translation-report.txt"));
            if (report != null) FileEditorManager.getInstance(project).openFile(report, true);
        }));
        notification.addAction(NotificationAction.createSimpleExpiring("Replace the Java sources…",
                () -> replace(project, selection, result)));
        notification.notify(project);
    }

    /*
     Each translated Java file becomes its Kotlin file, in the same directory, as one command; a Local History label
     marks the state before. The module must build Kotlin for the result to compile.
     */
    private static void replace(Project project, Selection selection, AnalysisModel.TranslateResult result) {
        int answer = Messages.showYesNoDialog(project, "Replace " + result.files().size() + " Java file(s) of module "
                + selection.module().getName() + " by their Kotlin translation?\n\nThe module must be set up to build"
                + " Kotlin. Local History keeps the Java files (label \"Before maddi Translate to Kotlin\").",
                "Translate to Kotlin with maddi", Messages.getQuestionIcon());
        if (answer != Messages.YES) return;
        LocalHistory.getInstance().putSystemLabel(project, "Before maddi Translate to Kotlin");
        WriteCommandAction.writeCommandAction(project).withName("Translate to Kotlin with maddi").run(() -> {
            for (AnalysisModel.TranslatedFile file : result.files()) {
                VirtualFile java = javaFileOf(selection, file.type());
                if (java == null || !java.isValid()) continue;
                String name = file.path().substring(file.path().lastIndexOf('/') + 1);
                try {
                    VirtualFile kt = java.getParent().findOrCreateChildData(MaddiTranslation.class, name);
                    kt.setBinaryContent(file.source().getBytes(StandardCharsets.UTF_8));
                    java.delete(MaddiTranslation.class);
                } catch (IOException e) {
                    LOG.warn("maddi: could not replace " + java.getPath(), e);
                }
            }
        });
    }

    private static @Nullable VirtualFile javaFileOf(Selection selection, @Nullable String type) {
        if (type == null) return null;
        return selection.javaFiles().entrySet().stream().filter(e -> e.getValue().contains(type))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /* "3 messages (1 ERROR, 2 INFO); 1 crash", or empty when the translator said nothing. */
    static String summary(AnalysisModel.TranslateResult result) {
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        for (AnalysisModel.TranslationMessage m : result.messages()) bySeverity.merge(m.severity(), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        if (!result.messages().isEmpty()) {
            parts.add(result.messages().size() + " translator message(s) (" + String.join(", ", bySeverity.entrySet()
                    .stream().map(e -> e.getValue() + " " + e.getKey()).toList()) + ")");
        }
        if (!result.crashes().isEmpty()) parts.add(result.crashes().size() + " type(s) not translated");
        if (!result.unknownTypes().isEmpty()) parts.add(result.unknownTypes().size() + " type(s) not analysed");
        return String.join("; ", parts);
    }

    static List<String> report(AnalysisModel.TranslateResult result) {
        List<String> lines = new ArrayList<>();
        lines.add(result.files().size() + " file(s); " + (summary(result).isEmpty() ? "no messages" : summary(result)));
        result.crashes().forEach(c -> lines.add("not translated: " + c));
        result.unknownTypes().forEach(t -> lines.add("not analysed: " + t));
        for (AnalysisModel.TranslationMessage m : result.messages()) {
            lines.add(m.severity() + " " + m.code() + " " + m.type() + (m.line() < 0 ? "" : ":" + m.line())
                      + (m.detail() == null || m.detail().isEmpty() ? "" : " " + m.detail()));
        }
        return lines;
    }

    private static void tell(Project project, String content, NotificationType type) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(content, type).notify(project);
        });
    }
}
