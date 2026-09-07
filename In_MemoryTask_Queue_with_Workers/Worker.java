package In_MemoryTask_Queue_with_Workers;

import java.util.concurrent.BlockingQueue;

public class Worker implements  Runnable {
     private final BlockingQueue<Runnable> taskQueue;
     private final String name;
        private volatile boolean isRunning = true;
     public Worker(BlockingQueue<Runnable> taskQueue, String name) {
         this.name =name;
         this.taskQueue = taskQueue;
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
                 Runnable task = taskQueue.take();
                 System.out.println("["+ name +"]"+"starting execution");
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



