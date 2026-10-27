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

import io.codelaser.maddi.ide.client.AnalysisModel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single latest whole-project result, shared by the display surfaces (like the IntelliJ
 * {@code MaddiAnalysisService} cache). {@link #update} is called off the UI thread by the analysis job;
 * listeners marshal to the UI thread themselves.
 */
public final class MaddiResults {

    private static final MaddiResults INSTANCE = new MaddiResults();

    private volatile AnalysisModel.Result latest;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private MaddiResults() {
    }

    public static MaddiResults get() {
        return INSTANCE;
    }

    public void update(AnalysisModel.Result result) {
        this.latest = result;
        notifyListeners();
    }

    /**
     * Fold in values the daemon streamed mid-run, so hints appear before the analysis finishes. Merges by
     * element (see {@link AnalysisModel#merge}) — a frame is one pass, not a complete picture — and nothing
     * is ever retracted, so what is on screen only gets more complete.
     */
    public AnalysisModel.Result mergePartial(AnalysisModel.PartialResult partial) {
        AnalysisModel.Result merged = AnalysisModel.merge(this.latest, partial);
        this.latest = merged;
        notifyListeners();
        return merged; // so the caller can report how much is established without re-reading the field
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    public AnalysisModel.Result latest() {
        return latest;
    }

    public List<AnalysisModel.Finding> findings() {
        AnalysisModel.Result r = latest;
        return r == null ? List.of() : r.findings();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }
}
