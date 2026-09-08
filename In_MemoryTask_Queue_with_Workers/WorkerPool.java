package In_MemoryTask_Queue_with_Workers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class WorkerPool {
   /*  
the pool creates 3 workers.

WorkerPool
    │
    ├── Worker-1
    ├── Worker-2
    └── Worker-3
    */
     private final TaskQueue taskQueue;
     private final List<Worker> workers;
     private final List<Thread> workerThreads;

   public WorkerPool(int workerCount){
         this.taskQueue =  new TaskQueue();
        this.workers = new ArrayList<>();
        this.workerThreads = new ArrayList<>();

        for (int i = 1; i <= workerCount; i++) {
            Worker worker = new Worker(taskQueue, "Worker-" + i);
            Thread thread = new Thread(worker, "Worker-Thread-" + i);
            workers.add(worker);
            workerThreads.add(thread);
            thread.start();
        }

   }

    public void submit(String id, int priority, Runnable task) {
        taskQueue.submit(id, priority, task);
    }

    

public void shutdown() {
        for (Worker worker : workers) {
            worker.stopWorker();
        }
        for (Thread thread : workerThreads) {
            thread.interrupt();
        }
    }
    }


