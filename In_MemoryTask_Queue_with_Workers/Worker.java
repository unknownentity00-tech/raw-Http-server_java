package In_MemoryTask_Queue_with_Workers;

import java.util.concurrent.BlockingQueue;

public class Worker implements  Runnable {
     private final TaskQueue taskQueue;
     private final String name;
    private volatile boolean isRunning = true;

     public Worker(TaskQueue taskQueue, String name) {
         this.taskQueue = taskQueue;
        this.name = name;
    }
    /* Worker starts
     ↓
Wait for task
     ↓
Take task from queue
     ↓
Execute task
     ↓
   
Task finished
 */
@Override 
   public  void run (){
        try{
             while (isRunning && !Thread.currentThread().isInterrupted()) {
                Task task = taskQueue.take();
               System.out.println("[" + name + "] Starting execution of " + task.getId() 
                    + " (Priority: " + task.getPriority() + ")");
                task.run();
             }
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
        }

        }

  public void stopWorker(){
    isRunning = false ;
  }

     }



