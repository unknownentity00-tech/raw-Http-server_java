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
        int workerCount = 1; // Single worker to strictly prove priority order
        WorkerPool pool = new WorkerPool(workerCount);

        AtomicInteger activeCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        System.out.println("=== Phase 4 Concurrency & Priority Test ===");

        // Submit tasks out of order with explicit priorities
        pool.submit("Task-1", 1, createWrappedTask(1, activeCount, maxConcurrent));
        pool.submit("Task-2", 10, createWrappedTask(2, activeCount, maxConcurrent));
        pool.submit("Task-3", 5, createWrappedTask(3, activeCount, maxConcurrent));
        pool.submit("Task-4", 8, createWrappedTask(4, activeCount, maxConcurrent));
        pool.submit("Task-5", 3, createWrappedTask(5, activeCount, maxConcurrent));

        Thread.sleep(3000);
        pool.shutdown();

        System.out.println("\n--- Test Results ---");
        System.out.println("Max concurrent executions observed: " + maxConcurrent.get() + " (Must be <= " + workerCount + ")");
    }

    // Helper method to wrap task execution logic with concurrency counters
    private static Runnable createWrappedTask(int taskId, AtomicInteger activeCount, AtomicInteger maxConcurrent) {
        return () -> {
            int currentActive = activeCount.incrementAndGet();
            maxConcurrent.updateAndGet(max -> Math.max(max, currentActive));

            System.out.println(Thread.currentThread().getName() + " → Executing Task-" + taskId + " (Active: " + currentActive + ")");
            try {
                Thread.sleep(300); // Simulate workload duration
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                activeCount.decrementAndGet();
            }
        };
    }
    }
