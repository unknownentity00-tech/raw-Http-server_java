package In_MemoryTask_Queue_with_Workers;

 
public class SimpleTask  implements Task {
  
     private final String id;
    private final TaskExecutionTracker tracker;
     public SimpleTask(String id, TaskExecutionTracker tracker) {
         this.id = id;
         this.tracker = tracker;
     }

     @Override
     public String getId() {
       return id;
     }

     @Override
    public void execute() {
        try {
            Thread.sleep(500); // Simulate workload
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println(Thread.currentThread().getName() + " finished executing " + id);
        tracker.recordExecution(id);
    }
     
}
