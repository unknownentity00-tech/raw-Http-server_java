package In_MemoryTask_Queue_with_Workers;


public class Consumer implements Runnable {
    private final TaskQueue taskQueue;
    private final String name;

    public Consumer(TaskQueue taskQueue, String name) {
        this.taskQueue = taskQueue;
        this.name = name;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Task task = taskQueue.take(); // Blocks here if the queue is empty
                System.out.println("Consumer " + name + ": starting " + task.getId());
                task.execute();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

