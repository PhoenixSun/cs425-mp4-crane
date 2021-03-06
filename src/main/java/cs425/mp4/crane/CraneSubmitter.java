package cs425.mp4.crane;
import cs425.mp4.crane.Messages.AcceptTopologyMessage;
import cs425.mp4.crane.Topology.Topology;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * Submitter class for topology. This class is a client
 * which serializes topology and sends it to Nimbus.
 */
public class CraneSubmitter {
    private final String nimbusHostname;
    private final int nimbusPort;
    public CraneSubmitter(String hostname, int port) {
        nimbusHostname=hostname;
        nimbusPort=port;
    }

    /**
     *
     * @param t topology to submit to Crane
     */
    public void submitTopology(Topology t) {
        try {
            Socket sock=new Socket(nimbusHostname,nimbusPort);
            ObjectOutputStream os=new ObjectOutputStream(sock.getOutputStream());
            os.writeObject(new AcceptTopologyMessage());
            os.writeObject(t);
            os.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
