package cs425.mp4.crane;

import cs425.mp3.FailureDetector.FailureDetector;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import cs425.mp3.Pid;
import cs425.mp4.crane.Exceptions.UnhandledCaseException;
import cs425.mp4.crane.Messages.AcceptTaskMessage;
import cs425.mp4.crane.Messages.AcceptTopologyMessage;
import cs425.mp4.crane.Messages.Message;
import cs425.mp4.crane.Messages.UpdateTaskListMessage;
import cs425.mp4.crane.Topology.Bolt;
import cs425.mp4.crane.Topology.Spout;
import cs425.mp4.crane.Topology.Topology;
import cs425.mp4.crane.Topology.TopologyRecord;

/**
 * Nimbus - The crane task which gets topology from client, launches
 * tasks in various worker machines, redistributes tasks on failures.
 */
public class Nimbus extends Thread {
    private static final int NIMBUS_TIMEOUT=1000;
    private static final int SPOUT_PORT=9501;
    private final FailureDetector failureDetector;
    private final ServerSocket sock;
    private HashMap<String,ArrayList<String>> workerID2Tasks;
    private HashMap<String,TaskAddress> task2Address;
    private HashMap<String,Task> id2Task;
    private AtomicBoolean spoutEmitTuples;

    /**
     *
     * @param failureDetector Failure detector object to get membership
     *                        list from
     * @throws IOException
     */
    public Nimbus(FailureDetector failureDetector) throws IOException {
        this.failureDetector = failureDetector;
        sock=new ServerSocket(Constants.NIMBUS_PORT);
        sock.setSoTimeout(NIMBUS_TIMEOUT);
        workerID2Tasks=new HashMap<String, ArrayList<String>>();
        task2Address=new HashMap<String, TaskAddress>();
        id2Task=new HashMap<String, Task>();
        spoutEmitTuples =new AtomicBoolean(false);
    }

    public void printTaskDistribution() {
        for (String taskID : task2Address.keySet())
            System.err.println(taskID+" = "+task2Address.get(taskID).hostname+" : "+task2Address.get(taskID).port);
    }

    /**
     * 1. Check if redistribution needed or not. Do redistribution if necessary
     * 2. Wait for topology to be submitted
     */
    public void run() {
        System.out.println("[NIMBUS] Nimbus run started");
        while(true) {
            taskRedistribution();
            try {
                Socket reqSock=sock.accept();
                ObjectInputStream is=new ObjectInputStream(reqSock.getInputStream());
                ObjectOutputStream os=new ObjectOutputStream(reqSock.getOutputStream());
                handleMessage(is,os);
                reqSock.close();
            } catch (SocketTimeoutException e) {
            } catch (IOException e) {
                System.out.println("[NIMBUS] Unknown IOException");
                e.printStackTrace();
            }
        }
    }

    /**
     * Check if any member node failed or not. If failed, redistribute.
     */
    private void taskRedistribution() {
        boolean redistributionDone=false;

        ArrayList<String> clone=new ArrayList<String>(workerID2Tasks.keySet());
        for (String workerID : clone) {
            if (!failureDetector.isAlive(workerID)) {
                redistributionDone=true;
                Random rn=new Random();
                List<String> availableWorkers=failureDetector.getMemlistSkipIntroducer();
                for (String taskID : workerID2Tasks.get(workerID)) {
                    String newWorker=availableWorkers.get(rn.nextInt(availableWorkers.size()));
                    int newPort=communicateLaunch(newWorker,taskID,id2Task.get(taskID).b,id2Task.get(taskID).fd);
                    task2Address.put(taskID,new TaskAddress(Pid.getPid(newWorker).hostname,newPort));
                    if (!workerID2Tasks.containsKey(newWorker)) {
                        workerID2Tasks.put(newWorker, new ArrayList<String>());
                    }

                    workerID2Tasks.get(newWorker).add(taskID);
                }

                workerID2Tasks.remove(workerID);
            }
        }

        if (redistributionDone) {
            updateTaskLists();
            System.err.println("task redistribution done");
            System.err.println(workerID2Tasks);
            for (String taskID : task2Address.keySet())
                System.err.println(taskID+" = "+task2Address.get(taskID).hostname+" : "+task2Address.get(taskID).port);
        }
    }

    /**
     * Send updated distribution of tasks to Worker nodes
     */
    private void updateTaskLists() {
        for (String workerid : workerID2Tasks.keySet()) {
            try {
                Socket sock=new Socket(Pid.getPid(workerid).hostname,Constants.WORKER_PORT);
                ObjectOutputStream os=new ObjectOutputStream(sock.getOutputStream());
                os.writeObject(new UpdateTaskListMessage());
                os.writeObject(task2Address);
                os.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Message handing
     * @param is socket input stream
     * @param os socket output stream
     */
    private void handleMessage(ObjectInputStream is, ObjectOutputStream os) {
        try {
            Message recd=(Message) is.readObject();
            if (recd.getMessageType().equals(AcceptTopologyMessage.messageType)) {
                Topology recdTopology=(Topology) is.readObject();
                workerID2Tasks=new HashMap<String, ArrayList<String>>();
                task2Address=new HashMap<String, TaskAddress>();
                spoutEmitTuples.set(false);
                distributeTasks(recdTopology);
                updateTaskLists();
                spoutEmitTuples.set(true);
            } else {
                throw new UnhandledCaseException("Message unknown in Nimbus");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (UnhandledCaseException e) {
            e.printStackTrace();
        }
    }

    /**
     * Helper method
     * @param recdTopology
     * @return
     */
    private HashMap<String,TopologyRecord> getID2RecordMap(Topology recdTopology) {
        HashMap<String,TopologyRecord> idToRecord=new HashMap<String, TopologyRecord>();
        for (TopologyRecord tr : recdTopology.getRecords())
            idToRecord.put(tr.id,tr);

        return idToRecord;
    }

    /**
     * Helper method
     * @param recdTopology
     * @return
     */
    private HashMap<String,ArrayList<String>> getID2ChildrenMap(Topology recdTopology) {
        HashMap<String,ArrayList<String>> children=new HashMap<String, ArrayList<String>>();
        for (TopologyRecord tr : recdTopology.getRecords()) {
            if (children.containsKey(tr.parentID))
                children.get(tr.parentID).add(tr.id);
            else {
                children.put(tr.parentID,new ArrayList<String>());
                children.get(tr.parentID).add(tr.id);
            }
        }

        return children;
    }

    /**
     * deciper the topology sent, break down into tasks, communicate
     * the tasks with workers
     * @param recdTopology
     * @throws UnhandledCaseException
     * @throws UnknownHostException
     */
    private void distributeTasks(Topology recdTopology) throws UnhandledCaseException, UnknownHostException {
        HashMap<String,TopologyRecord> idToRecord=getID2RecordMap(recdTopology);
        HashMap<String,ArrayList<String>> children=getID2ChildrenMap(recdTopology);
        HashMap<String,Integer> numAcks=countNumAcks(recdTopology, children);
        String spoutID=getSpoutID(recdTopology);
        for (TopologyRecord tr : recdTopology.getRecords()) {
            Forwarder fd=new Forwarder(spoutID,numAcks.get(tr.id));
            if (children.containsKey(tr.id)) {
                for (String child : children.get(tr.id)) {
                    fd.addChild(child, idToRecord.get(child).numTasks, idToRecord.get(child).groupingField);
                }
            }

            if (tr.type==TopologyRecord.spoutType) {
                SpoutTask st=new SpoutTask((Spout) tr.operationUnit,fd,task2Address,spoutEmitTuples,SPOUT_PORT);
                st.setDaemon(true);
                st.start();
                task2Address.put(tr.id, new TaskAddress(InetAddress.getLocalHost().getHostName(), SPOUT_PORT));
            } else if (tr.type==TopologyRecord.boltType) {
                launchTasks(tr,fd);
            } else {
                throw new UnhandledCaseException("type not recognized");
            }
        }
    }

    /**
     * Helper method
     * @param recdTopology
     * @return
     */
    private String getSpoutID(Topology recdTopology) {
        for (TopologyRecord tr : recdTopology.getRecords()) {
            if (tr.type==TopologyRecord.spoutType)
                return tr.id;
        }

        return null;
    }

    /**
     * Helper method to count the number of leaf bolts that a parent bolt has including self.
     * @param recdTopology
     * @param children
     * @return
     */
    private HashMap<String, Integer> countNumAcks(Topology recdTopology, HashMap<String,ArrayList<String>> children) {
        HashMap<String,Integer> ret=new HashMap<String,Integer>();
        for (TopologyRecord tr : recdTopology.getRecords()) {
            if (tr.type==TopologyRecord.spoutType) {
                countAckHelper(tr.id, ret, children);
                break;
            }
        }
        return ret;
    }

    /**
     * Helper method for countAcks
     * @param id
     * @param ret
     * @param children
     * @return
     */
    private Integer countAckHelper(String id, HashMap<String, Integer> ret, HashMap<String, ArrayList<String>> children) {
        if (!children.containsKey(id)) {
            ret.put(id,1);
            return 1;
        } else {
            int totalCount=0;
            for (String child : children.get(id))
                totalCount+=countAckHelper(child,ret,children);

            ret.put(id,totalCount);
            return totalCount;
        }
    }

    /**
     * launch tasks in Worker nodes.
     * @param tr spout/bolt description
     * @param fd Forwarding logic for spout/bolt output
     */
    private void launchTasks(TopologyRecord tr, Forwarder fd) {
        List<String> workers=failureDetector.getMemlistSkipIntroducer();
        Random rn=new Random();
        for (int i=0;i<tr.numTasks;i++) {
            String workerID=workers.get(rn.nextInt(workers.size()));
            String taskID=tr.id+String.valueOf(i);
            int launchedPort=communicateLaunch(workerID, taskID,(Bolt) tr.operationUnit,fd);
            task2Address.put(taskID,new TaskAddress(Pid.getPid(workerID).hostname,launchedPort));
            id2Task.put(taskID,new Task((Bolt) tr.operationUnit,fd));
            if (!workerID2Tasks.containsKey(workerID))
                workerID2Tasks.put(workerID,new ArrayList<String>());

            workerID2Tasks.get(workerID).add(taskID);
        }
    }

    /**
     * send task to worker
     * @param workerID
     * @param taskID
     * @param operationUnit spout/bolt
     * @param fd Forwarder of the spout/bolt
     * @return port number in which task is launched in worker node
     */
    private int communicateLaunch(String workerID, String taskID, Bolt operationUnit, Forwarder fd) {
        try {
            Socket sock=new Socket(Pid.getPid(workerID).hostname,Constants.WORKER_PORT);
            ObjectOutputStream os=new ObjectOutputStream(sock.getOutputStream());
            os.writeObject(new AcceptTaskMessage());
            os.writeObject(taskID);
            os.writeObject((Bolt) operationUnit);
            os.writeObject(fd);
            os.flush();

            ObjectInputStream is=new ObjectInputStream(sock.getInputStream());
            Integer portReplied=(Integer) is.readObject();
            sock.close();
            return portReplied;
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            return -1;
        }
    }
}
