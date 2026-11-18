package com.blogPlatform.III.blog.security.monitor;

import com.blogPlatform.III.blog.dto.response.SecurityReport.CountEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

// The k biggest counts without sorting all of them: a min-heap that never holds more than k entries.
// Its top is always the smallest of the current "best k", so a bigger count simply pushes that one out.
// n counts -> O(n log k) instead of O(n log n) for a full sort (k = 5, n can be thousands of IPs or endpoints).
public final class TopK {

    private static final Comparator<CountEntry> BIGGEST_FIRST =
            Comparator.comparingLong(CountEntry::count).reversed().thenComparing(CountEntry::name);

    private TopK() {
    }

    public static List<CountEntry> of(Map<String, AtomicLong> counts, int k) {
        // exactly the reverse of the final order, ties included: the entry on top is always the one a full sort
        // would put last, so equal counts give the same answer every time
        PriorityQueue<CountEntry> heap = new PriorityQueue<>(BIGGEST_FIRST.reversed());
        counts.forEach((name, count) -> {
            heap.offer(new CountEntry(name, count.get()));
            if (heap.size() > k) {
                heap.poll();
            }
        });
        // at most k entries left: sorting them is cheap
        List<CountEntry> top = new ArrayList<>(heap);
        top.sort(BIGGEST_FIRST);
        return top;
    }
}
