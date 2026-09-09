package In_MemoryTask_Queue_with_Workers;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class TaskQueue {
    private final BlockingQueue<Task> queue;
    private final ConcurrentHashMap<String, Task> taskMap;
    private final AtomicLong sequenceCounter;

    public TaskQueue() {
        this.queue = new PriorityBlockingQueue<>();
        this.taskMap = new ConcurrentHashMap<>();
        this.sequenceCounter = new AtomicLong(0);
    }

    public void submit(String id, int priority, Runnable action) {
        long seq = sequenceCounter.incrementAndGet();
        Task task = new Task(id, priority, seq, action);
        taskMap.put(id, task);
        try {
            queue.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Task submission interrupted", e);
        }
    }
     public Task take() throws InterruptedException {
        Task task = queue.take();
        taskMap.remove(task.getId());
        return task;
     }

    public void cancel(String taskId) {
        Task task = taskMap.get(taskId);
        if (task != null) {
            task.cancel();
        }
    }
}