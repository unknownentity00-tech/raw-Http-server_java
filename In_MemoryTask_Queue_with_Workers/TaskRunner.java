package In_MemoryTask_Queue_with_Workers;

public class TaskRunner {
    private final int taskCount;
    private final WorkerPool workerPool;
    private final String name;

    public TaskRunner(WorkerPool workerPool, int taskCount, String name) {
        this.workerPool = workerPool;
        this.taskCount = taskCount;
        this.name = name;
    }
     public void run(){
       try{
        for( int i = 1; i <= taskCount; i++) {
            final String taskId = name + "-Task-" + i;
               // Submit a Runnable task directly to the WorkerPool
                workerPool.submit(() -> {
                    System.out.println(Thread.currentThread().getName() + " executing " + taskId);
                    try {
                        Thread.sleep(200); // Simulate work
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                System.out.println("Producer " + name + ": submitted " + taskId);
                
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }
}
