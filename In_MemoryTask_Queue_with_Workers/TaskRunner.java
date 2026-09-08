package In_MemoryTask_Queue_with_Workers;



public class TaskRunner implements Runnable {
    private final int taskCount;
    private final WorkerPool workerPool;
    private final String name;

    public TaskRunner(WorkerPool workerPool, int taskCount, String name) {
        this.workerPool = workerPool;
        this.taskCount = taskCount;
        this.name = name;
    }

    @Override
    public void run() {
        try {
            for (int i = 1; i <= taskCount; i++) {
                String taskId = name + "-Task-" + i;
                // Assign a dummy priority or randomize it for testing
                int priority = (int) (Math.random() * 10) + 1; 

                workerPool.submit(taskId, priority, () -> {
                    System.out.println(Thread.currentThread().getName() + " executing " + taskId + " (Priority: " + priority + ")");
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

                System.out.println("Producer " + name + ": submitted " + taskId + " with priority " + priority);
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}