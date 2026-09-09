package In_MemoryTask_Queue_with_Workers;

import java.util.ArrayList;
import java.util.List;

public class WorkerPool {
    public enum PoolState { RUNNING, SHUTDOWN }

    private final TaskQueue taskQueue;
    private final List<Worker> workers;
    private final List<Thread> workerThreads;
    private volatile PoolState state;

    public WorkerPool(int workerCount) {
        this.taskQueue = new TaskQueue();
        this.workers = new ArrayList<>();
        this.workerThreads = new ArrayList<>();
        this.state = PoolState.RUNNING;

        for (int i = 1; i <= workerCount; i++) {
            Worker worker = new Worker(taskQueue, "Worker-" + i);
            Thread thread = new Thread(worker, "Worker-Thread-" + i);
            workers.add(worker);
            workerThreads.add(thread);
            thread.start();
        }
    }

    public void submit(String id, int priority, Runnable task) {
        if (state == PoolState.SHUTDOWN) {
            System.out.println("Submission rejected (" + id + "): Pool is SHUTDOWN.");
            return;
        }
        taskQueue.submit(id, priority, task);
    }

    public void cancel(String taskId) {
        taskQueue.cancel(taskId);
    }

    public void shutdown() {
        state = PoolState.SHUTDOWN;
        for (Worker worker : workers) {
            worker.stopWorker();
        }
        for (Thread thread : workerThreads) {
            thread.interrupt();
        }
    }

    public PoolState getState() {
        return state;
    }
}
