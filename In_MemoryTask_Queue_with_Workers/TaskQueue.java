package In_MemoryTask_Queue_with_Workers;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;





public class TaskQueue {
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();

    public void submit(Task task) throws InterruptedException {
        queue.put(task);
    }

    public Task take() throws InterruptedException {
        return queue.take();
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}

