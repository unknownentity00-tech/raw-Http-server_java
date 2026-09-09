package In_MemoryTask_Queue_with_Workers;

public  class Task  implements Runnable , Comparable<Task>{
     public enum State { QUEUED, RUNNING, SUCCESS, CANCELLED };
     private final String id;
    private final int priority;
    private final long sequenceNumber;
    private final Runnable action;
    private volatile State state = State.QUEUED;
     public Task(String id , int priority , long sequenceNumber , Runnable action){
        this.id = id;
        this.priority = priority;
        this.sequenceNumber = sequenceNumber;
        this.action = action;
     }

    @Override
    public void run() {
        if (state == State.CANCELLED) {
            return;
        }
        state = State.RUNNING;
        try{
            action.run();
            state = State.SUCCESS;
        }catch(Exception e){
            
            throw e;
        }

        action.run();
    }
    public void  cancel(){
      if (state == State.QUEUED) {
            state = State.CANCELLED;
        }
    }
    public int getPriority() {
        return priority;
    }
    public State getState() {
        return state;
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
 
