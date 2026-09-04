package In_MemoryTask_Queue_with_Workers;

public class TaskRunner {

    private final TaskQueue taskQueue;

    public TaskRunner(TaskQueue taskQueue) {
        this.taskQueue = taskQueue;
    }
     public void run(){
        while (!taskQueue.isEmpty()) {
            Task task = taskQueue.take();
            if (task != null) {
                task.execute();
            }
     }
    }
}
