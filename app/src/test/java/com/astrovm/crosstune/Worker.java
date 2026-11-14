package com.astrovm.crosstune;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;

/**
 * A thread, started before any reading, that waits for a text, reads it and hands back what it read: a reading can hand
 * its work to it, woken one of the ways a thread can be ({@link Woken}). In Java, as Kotlin has no wait or notify.
 */
final class Worker implements AutoCloseable {
    /** How a reading wakes the worker. */
    enum Woken {
        /** Interrupted as it sleeps. */
        INTERRUPTED,
        /** Told, as it waits on a lock. */
        TOLD,
        /** Never: it looks for a text again and again. */
        SPINNING,
        /** By the system, as a pipe it reads gets bytes. */
        PIPED,
    }

    private static final int LENGTH_BYTES = Integer.BYTES;

    private final Woken woken;
    private final ToIntFunction<String> read;
    private final AtomicReference<String> mailbox = new AtomicReference<>();
    private final BlockingQueue<Integer> answers = new LinkedBlockingQueue<>();
    private final Object lock = new Object();
    private final Pipe pipe;
    private final Thread thread;
    private volatile boolean closed;

    Worker(Woken woken, ToIntFunction<String> read) throws IOException {
        this.woken = woken;
        this.read = read;
        pipe = Pipe.open();
        thread = new Thread(this::work, "worker woken ".concat(woken.name()));
        thread.setDaemon(true);
        thread.start();
    }

    /** Hands [text] to the worker, and waits for what it read: the reading waits, and does none of the work. */
    int read(String text) throws IOException, InterruptedException {
        switch (woken) {
            case INTERRUPTED:
                mailbox.set(text);
                thread.interrupt();
                break;
            case TOLD:
                synchronized (lock) {
                    mailbox.set(text);
                    lock.notifyAll();
                }
                break;
            case SPINNING:
                mailbox.set(text);
                break;
            case PIPED:
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                ByteBuffer out = ByteBuffer.allocate(LENGTH_BYTES + bytes.length).putInt(bytes.length).put(bytes);
                out.flip();
                while (out.hasRemaining()) pipe.sink().write(out);
                break;
        }
        return answers.take();
    }

    private void work() {
        try {
            while (!closed) {
                String text = waited();
                if (text != null) answers.add(read.applyAsInt(text));
            }
        } catch (IOException | InterruptedException closing) {
            // Closed as it waited.
        }
    }

    /** The next text, once there is one, or null where there's none yet. */
    private String waited() throws IOException, InterruptedException {
        switch (woken) {
            case INTERRUPTED:
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException woke) {
                    // A text to read, or closed.
                }
                return mailbox.getAndSet(null);
            case TOLD:
                synchronized (lock) {
                    while (mailbox.get() == null && !closed) lock.wait();
                }
                return mailbox.getAndSet(null);
            case SPINNING:
                String text = mailbox.getAndSet(null);
                if (text == null) Thread.onSpinWait();
                return text;
            default:
                ByteBuffer length = filled(ByteBuffer.allocate(LENGTH_BYTES));
                return StandardCharsets.UTF_8.decode(filled(ByteBuffer.allocate(length.getInt()))).toString();
        }
    }

    /** [buffer], filled from the pipe, and flipped to be read. */
    private ByteBuffer filled(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (pipe.source().read(buffer) < 0) throw new IOException("closed");
        }
        buffer.flip();
        return buffer;
    }

    @Override
    public void close() throws IOException, InterruptedException {
        closed = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        pipe.sink().close();
        thread.interrupt();
        thread.join();
        pipe.source().close();
    }
}
