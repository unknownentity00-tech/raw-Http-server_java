package In_MemoryTask_Queue_with_Workers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    /*Create WorkerPool with 3 workers

Create 10 tasks

Submit all 10

Wait for all tasks to complete

Shutdown pool */
    public static void main(String[] args) throws InterruptedException {
        int workerCount = 3;
        int totalTasks = 10;
        WorkerPool pool = new WorkerPool(workerCount);

        AtomicInteger activeCount  = new AtomicInteger(0);
        AtomicInteger maxConcurrent  = new AtomicInteger(0);
       
        for (int i = 1; i <= totalTasks; i++) {
            final int taskId = i;
            pool.submit(() -> {
                int currentActive = activeCount.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, currentActive));

                System.out.println(Thread.currentThread().getName() + " → Executing Task-" + taskId + " (Active: " + currentActive + ")");
                try {
                    Thread.sleep(400); // Simulate workload duration
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    activeCount.decrementAndGet();
                }
            });
        }

        Thread.sleep(4000);
        pool.shutdown();

        System.out.println("\n--- Test Results ---");
        System.out.println("Max concurrent executions observed: " + maxConcurrent.get() + " (Must be <= " + workerCount + ")");

    }
}