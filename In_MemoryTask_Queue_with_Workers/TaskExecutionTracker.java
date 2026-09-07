package In_MemoryTask_Queue_with_Workers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class TaskExecutionTracker {
    private final Set<String> executedIds = ConcurrentHashMap.newKeySet();
    private final CountDownLatch latch;

    public TaskExecutionTracker(int totalTasks) {
        this.latch = new CountDownLatch(totalTasks);
    }

    public void recordExecution(String id) {
        if (!executedIds.add(id)) {
            System.err.println("CRITICAL ERROR: Duplicate execution detected for " + id);
        }
        latch.countDown();
    }

    public void awaitCompletion() throws InterruptedException {
        latch.await();
    }

    public int getExecutedCount() {
        return executedIds.size();
    }
}
