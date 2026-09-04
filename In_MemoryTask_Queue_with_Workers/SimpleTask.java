package In_MemoryTask_Queue_with_Workers;

 
public class SimpleTask  implements Task {
  
     private final String id;

     public SimpleTask(String id) {
         this.id = id;
     }

     @Override
     public String getId() {
       return id;
     }

     @Override
     public void execute() {
      System.out.println("Processing " + id);
     }
     public static void main(String[] args) {
        Task task1 = new SimpleTask("Task-1");
        Task task2 = new SimpleTask("Task-2");

        task1.execute();
        task2.execute();
     }
}
