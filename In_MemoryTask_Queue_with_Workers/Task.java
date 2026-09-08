package In_MemoryTask_Queue_with_Workers;

public  class Task  implements Runnable , Comparable<Task>{

     private final String id;
    private final int priority;
    private final long sequenceNumber;
    private final Runnable action;

     public Task(String id , int priority , long sequenceNumber , Runnable action){
        this.id = id;
        this.priority = priority;
        this.sequenceNumber = sequenceNumber;
        this.action = action;
     }

    @Override
    public void run() {
        action.run();
    }

    public int getPriority() {
        return priority;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getId() {
        return id;
    }
   @Override
    public int compareTo(Task other) {
        // Higher priority first (DESC)
        int priorityComparison = Integer.compare(other.priority, this.priority);
        if (priorityComparison != 0) {
            return priorityComparison;
        }
        // If priorities are equal, lower sequence number first (ASC / FIFO)
        return Long.compare(this.sequenceNumber, other.sequenceNumber);
    }
}
 
