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

package io.codelaser.maddi.ide.daemon;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/**
 * The daemon's memory headroom, for the progress line: heap in use against the maximum, and the share of the
 * time since the previous sample spent collecting garbage. The second is the one to watch. A run short of
 * memory does not stop at once: it spends a growing share of each interval in GC first, while "in use" swings
 * with every collection. No forced GC: it would perturb the run it measures.
 * <p>
 * Called from the heartbeat thread only; the MXBeans are thread-safe and never touch maddi state.
 */
final class HeapSampler {
    private static final double GB = 1024.0 * 1024 * 1024;

    private long lastGcMillis = gcMillis();
    private long lastSampleNanos = System.nanoTime();

    String sample() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        long gc = gcMillis();
        long now = System.nanoTime();
        long wallMillis = Math.max(1, (now - lastSampleNanos) / 1_000_000);
        long gcShare = Math.min(100, Math.round(100.0 * (gc - lastGcMillis) / wallMillis));
        lastGcMillis = gc;
        lastSampleNanos = now;
        return String.format(java.util.Locale.ROOT, "heap %.1f of %.1f GB, GC %d%%", heap.getUsed() / GB, max / GB, gcShare);
    }

    private static long gcMillis() {
        long sum = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            if (t > 0) sum += t;
        }
        return sum;
    }
}
