package games.cubi.utils;

import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;

import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * An unbounded intrusive single-producer, single-consumer linked queue.
 *
 * <p>The producer and consumer roles may each migrate between threads, but calls for the same role must never overlap.
 * The volatile role cursors publish a completed operation to a subsequent owner of that role. Nodes are published from
 * the producer to the consumer through a release/acquire pair on the intrusive forward link.</p>
 *
 * <p>Nodes are single-use and must not be offered to this or any other queue more than once. The most recently consumed
 * node becomes the queue's empty sentinel and remains referenced until another node is consumed.</p>
 *
 * @param <N> node type stored by this queue
 */
public final class IntrusiveSPSCQueue<N extends IntrusiveSPSCQueue.Node> {
    private volatile Node producerTail;
    private volatile Node consumerHead;

    public IntrusiveSPSCQueue() {
        Node sentinel = new Node();
        producerTail = sentinel;
        consumerHead = sentinel;
    }

    /**
     * Appends a fresh node. Only the logical producer may call this method.
     *
     * @throws IllegalArgumentException if {@code node} is the current tail
     * @throws IllegalStateException if {@code node} already has an intrusive successor
     */
    public void offer(N node) {
        Objects.requireNonNull(node, "node");

        Node tail = producerTail;
        if (node == tail) {
            throw new IllegalArgumentException("Cannot offer the current queue tail again");
        }
        if (((Node) node).nextPlain() != null) {
            throw new IllegalStateException("Cannot offer a node that is already linked");
        }

        tail.setNextRelease(node);
        producerTail = node;
    }

    /** Returns and removes the next node, or {@code null}. Only the logical consumer may call this method. */
    @SuppressWarnings("unchecked")
    public N poll() {
        Node next = consumerHead.nextAcquire();
        if (next == null) {
            return null;
        }
        consumerHead = next;
        return (N) next;
    }

    public void clear() {
        consumerHead = producerTail;
    }

    /** Returns whether the consumer currently observes no published node. */
    public boolean isEmpty() {
        return consumerHead.nextAcquire() == null;
    }

    /** Base class for objects that are their own intrusive queue node. */
    public static class Node {
        private volatile Node next;

        private static final VarHandle NEXT = ConcurrentUtil.getVarHandle(Node.class, "next", Node.class);

        protected Node() {
        }

        private Node nextPlain() {
            return (Node) NEXT.get(this);
        }

        private Node nextAcquire() {
            return (Node) NEXT.getAcquire(this);
        }

        private void setNextRelease(Node next) {
            NEXT.setRelease(this, next);
        }
    }
}
