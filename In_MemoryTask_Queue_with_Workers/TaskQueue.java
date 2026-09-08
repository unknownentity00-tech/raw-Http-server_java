package In_MemoryTask_Queue_with_Workers;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;





public class TaskQueue {
    private final BlockingQueue<Task> queue;
    private final AtomicLong sequenceCounter;

    public TaskQueue() {
        this.queue = new PriorityBlockingQueue<>();
        this.sequenceCounter = new AtomicLong(0);
    }

    public void submit(String id, int priority, Runnable action) {
        long seq = sequenceCounter.incrementAndGet();
        Task task = new Task(id, priority, seq, action);
        try {
            queue.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Task submission interrupted", e);
        }
    }

    public Task take() throws InterruptedException {
        return queue.take();
    }
}