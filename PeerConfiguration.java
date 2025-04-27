// Represents the configuration details for a single peer in the network
public class PeerConfiguration {
    int ID;                 // Unique identifier for the peer
    int portNumber;         // Port number the peer listens on
    String hostName;        // Hostname or IP address of the peer
    boolean peerHasFile;    // Flag indicating if this peer starts with the complete file

    // Constructor to initialize all fields for this peer
    public PeerConfiguration(int ID, String hostName, int portNumber, boolean peerHasFile) {
        this.ID = ID;
        this.hostName = hostName;
        this.portNumber = portNumber;
        this.peerHasFile = peerHasFile;
    }
}
