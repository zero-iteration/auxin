package io.auxin.agent.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Hand-rolled MPSC ring over a {@code long[]}, allocation-free on the producer side.
 *
 * <p>Why not {@code MpscArrayQueue<E>}: it stores an object reference, so a compound event
 * (methodId, duration, error) cannot be enqueued without allocating (VALIDATION A7 "the
 * compound record allocates"). One event is packed into one {@code long} instead — see
 * {@link Events}.
 *
 * <p>Full policy, verbatim from C27: <i>one attempt, no retry, no spin, no park.</i> A failed
 * CAS is also a drop — deliberately: a retry loop on an application thread is exactly the
 * unbounded cost this design exists to avoid. Both drop kinds are counted.
 *
 * <p>Memory model: the producer's CAS is a full barrier; the slot store after it is plain. The
 * consumer re-reads the volatile producer index on every drain pass, so a store can be late by
 * at most one drain interval but can never be lost or mis-ordered — a slot that still reads 0
 * ends the pass and is picked up next time. Zero is therefore reserved as "unpublished", which
 * is why {@link Events#pack} always sets bit 63.
 */
public final class Ring {

    /** Cache-line padded producer index. 15 longs covers a 128-byte line (Apple M-series). */
    static final class PaddedProducerIndex extends AtomicLong {
        private static final long serialVersionUID = 1L;
        @SuppressWarnings("unused") volatile long p1, p2, p3, p4, p5, p6, p7, p8,
                p9, p10, p11, p12, p13, p14, p15;
    }

    static final class PaddedConsumerIndex {
        @SuppressWarnings("unused") volatile long p1, p2, p3, p4, p5, p6, p7, p8;
        volatile long value;
        @SuppressWarnings("unused") volatile long q1, q2, q3, q4, q5, q6, q7, q8;
    }

    public interface EventHandler {
        void onEvent(long event);
    }

    private final long[] buffer;
    private final int capacity;
    private final int mask;
    private final PaddedProducerIndex producerIndex = new PaddedProducerIndex();
    private final PaddedConsumerIndex consumerIndex = new PaddedConsumerIndex();

    /** Drain-thread private; never touched by a producer. */
    private long consumerLocal;

    private long droppedFull;
    private long droppedContended;

    public Ring(int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two: " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.buffer = new long[capacity];
    }

    /**
     * Application-thread hot path. No allocation, no loop, no blocking.
     *
     * @param event must be non-zero (bit 63 set by {@link Events#pack})
     * @return false when the event was dropped
     */
    public boolean offer(long event) {
        final long p = producerIndex.get();
        if (p - consumerIndex.value >= capacity) {
            droppedFull++;              // racy by design: a counter, not a ledger
            return false;
        }
        if (!producerIndex.compareAndSet(p, p + 1)) {
            droppedContended++;
            return false;
        }
        buffer[(int) (p & mask)] = event;
        return true;
    }

    /** Drain thread only. @return number of events handed to the handler. */
    public int drain(EventHandler handler, int limit) {
        long c = consumerLocal;
        final long p = producerIndex.get();
        int n = 0;
        while (n < limit && c < p) {
            final int i = (int) (c & mask);
            final long v = buffer[i];
            if (v == 0) break;          // claimed but not yet published: next pass gets it
            buffer[i] = 0;
            c++;
            n++;
            handler.onEvent(v);
        }
        if (n > 0) {
            consumerLocal = c;
            consumerIndex.value = c;    // release to producers
        }
        return n;
    }

    public int capacity() { return capacity; }
    public long droppedFull() { return droppedFull; }
    public long droppedContended() { return droppedContended; }
    public long size() { return producerIndex.get() - consumerIndex.value; }
}
