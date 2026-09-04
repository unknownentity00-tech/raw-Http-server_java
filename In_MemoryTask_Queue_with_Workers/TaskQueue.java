package In_MemoryTask_Queue_with_Workers;

import java.util.LinkedList;
import java.util.Queue;

public class TaskQueue {
    private final Queue<Task> queue = new LinkedList<>();

    public void submit(Task task) {
        queue.offer(task);
    }

    public Task take() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
