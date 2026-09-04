package In_MemoryTask_Queue_with_Workers;

public class Main {
     public static void main(String[] args){
        TaskQueue taskQueue = new TaskQueue();

        for (int i = 1; i <= 5; i++) {
           String taskId = "Task-" + i;
           System.out.println("Submitting " + taskId);
           taskQueue.submit( new SimpleTask(taskId));
        }
        
        System.out.println();
        TaskRunner taskRunner = new TaskRunner(taskQueue);
        taskRunner.run();

        }
     }

