package In_MemoryTask_Queue_with_Workers;

public class Producer implements Runnable {
    private final TaskQueue taskQueue;
    private final int taskCount;
    private final String name;
    private final TaskExecutionTracker tracker;

    public Producer(TaskQueue taskQueue, int taskCount, String name, TaskExecutionTracker tracker) {
        this.taskQueue = taskQueue;
        this.taskCount = taskCount;
        this.name = name;
        this.tracker = tracker;
    }

    @Override
    public void run() {
        try {
            for (int i = 1; i <= taskCount; i++) {
                String taskId = name + "-Task-" + i;
                Task task = new SimpleTask(taskId, tracker);
                taskQueue.submit(task);
                System.out.println("Producer " + name + ": submitted " + taskId);
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
