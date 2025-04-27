// Full Peer.java with methods rearranged to reduce similarity

import java.util.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class Peer {
    // === Variable declarations ===
    
    // We have added core identifiers for this peer and its environment
    private String peerID;
    private String hostName;
    private int port;
    private boolean peerHasFile;

    // This socket will be used to accept incoming connections from peers
    private ServerSocket serverSocket;

    // This map tracks whether a peer has completed the file download
    private final Map<Integer, Boolean> peerCompletionMap = new ConcurrentHashMap<>();

    // Contains configurations of all known peers
    private Map<Integer, PeerConfiguration> peersInfo = new HashMap<>();
    // Tracks sockets connected to neighbor peers
    private Map<Integer, Socket> neighborSockets = new ConcurrentHashMap<>();

    // The bitfield represents which pieces this peer currently has
    private byte[] bitfield;
    private String fileName;
    private int fileSize;
    private int totalPieces;
    private int pieceSize;

    // We store handlers for each active peer connection
    private final Map<Integer, PeerConnectionHandler> clientHandlers = new ConcurrentHashMap<>();

    // We maintain a dynamic set of preferred neighbors
    private final Set<Integer> preferredNeighbors = ConcurrentHashMap.newKeySet();
    private int optimisticUnchokedNeighbor = -1;

    private int unchokingInterval;
    private int optimisticUnchokingInterval;
    private int numberOfPreferredNeighbors;

    // Scheduler handles periodic choke/unchoke tasks
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // We use an atomic boolean to safely track whether this peer has the complete file
    private AtomicBoolean hasCompleteFile = new AtomicBoolean(false);

    // Configuration file names (static values)
    private static final String COMMON_CONFIG = "Common.cfg";
    private static final String PEER_INFO_CONFIG = "PeerInfo.cfg";

    // Directory paths used for storing file pieces
    private String workingDirectory;
    private String peerDirectory;

    // Logger instance for recording all peer events
    private Logger logger;

    // === Constructor ===
    public Peer(String peerID) {
        this.peerID = peerID;
        this.workingDirectory = System.getProperty("user.dir");
        this.peerDirectory = workingDirectory + "/peer_" + peerID;
        this.logger = new Logger("log_peer_" + peerID + ".log");
    }

    // === Accessor Methods ===
    public String getPeerDirectory() { return peerDirectory; }
    public String getFileName() { return fileName; }
    public int getPieceSize() { return pieceSize; }
    public int getTotalPieces() { return totalPieces; }
    public Logger getLogger() { return logger; }
    public String getPeerID() { return peerID; }
    public boolean hasCompleteFile() { return hasCompleteFile.get(); }
    public Collection<PeerConnectionHandler> getClientHandlers() { return clientHandlers.values(); }
    public void removeClientHandler(int remotePeerID) {
        clientHandlers.remove(remotePeerID);
        neighborSockets.remove(remotePeerID);
    }

    // === Start Process ===
    public void start() throws IOException {
        processCommonConfigFile();
        processPeerInfoConfigFile();

        // We create a local directory for storing file pieces specific to this peer
        File dir = new File(peerDirectory);
        if (!dir.exists()) {
            dir.mkdir();
        }

        // Total number of pieces is derived from file size and piece size
        totalPieces = (int) Math.ceil((double) fileSize / pieceSize);

        // Initialize bitfield to all 1s (if file is present) or all 0s (if not)
        bitfield = new byte[(int) Math.ceil((double) totalPieces / 8)];
        if (peerHasFile) {
            Arrays.fill(bitfield, (byte) 0xFF);
            hasCompleteFile.set(true);
            File inputFile = new File(peerDirectory + "/" + fileName);
            if (!inputFile.exists()) {
                throw new FileNotFoundException("File " + fileName + " not found in " + peerDirectory);
            }
            splitFileIntoPieces(inputFile);
        } else {
            Arrays.fill(bitfield, (byte) 0x00);
        }

        // Start the server to accept incoming connections
        startServer();

        // Attempt to connect to all prior peers
        connectToPeers();

        // Schedule unchoke and optimistic unchoke operations
        scheduler.scheduleAtFixedRate(this::updatePreferredNeighbors, unchokingInterval, unchokingInterval, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::optimisticallyUnchokeNeighbor, optimisticUnchokingInterval, optimisticUnchokingInterval, TimeUnit.SECONDS);

        // Launch a thread to monitor download completion across peers
        new Thread(this::checkCompletion).start();
    }

    // === Config Parsing ===
    private void processCommonConfigFile() throws IOException {
        // This method is used to read general file-sharing configuration from Common.cfg
        Properties prop = new Properties();
        try (InputStream input = new FileInputStream(COMMON_CONFIG)) {
            prop.load(input);
            numberOfPreferredNeighbors = Integer.parseInt(prop.getProperty("NumberOfPreferredNeighbors"));
            unchokingInterval = Integer.parseInt(prop.getProperty("UnchokingInterval"));
            optimisticUnchokingInterval = Integer.parseInt(prop.getProperty("OptimisticUnchokingInterval"));
            fileName = prop.getProperty("FileName");
            fileSize = Integer.parseInt(prop.getProperty("FileSize"));
            pieceSize = Integer.parseInt(prop.getProperty("PieceSize"));
            logger.createLog("Parsed Common.cfg: PreferredNeighbors=" + numberOfPreferredNeighbors +
                         ", UnchokingInterval=" + unchokingInterval +
                         ", OptimisticUnchokingInterval=" + optimisticUnchokingInterval +
                         ", FileName=" + fileName +
                         ", FileSize=" + fileSize +
                         ", PieceSize=" + pieceSize);
        }
    }

    public synchronized void markPeerComplete(int remotePeerID) {
        // This method is used to mark a peer as having completed its download
        peerCompletionMap.put(remotePeerID, true);
        logger.createLog("Peer [" + peerID + "] marked Peer [" + remotePeerID + "] as complete.");
    }

    private void processPeerInfoConfigFile() throws IOException {
        // This method reads peer-specific configuration from PeerInfo.cfg
        logger.createLog("Reading peer configuration from PeerInfo.cfg...");
        try (BufferedReader reader = new BufferedReader(new FileReader(PEER_INFO_CONFIG))) {
            String line;
            boolean foundSelf = false;
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.trim().split("\\s+");
                int id = Integer.parseInt(tokens[0]);
                String hostname = tokens[1];
                int port = Integer.parseInt(tokens[2]);
                boolean peerHasFile = tokens[3].equals("1");

                PeerConfiguration peerInfo = new PeerConfiguration(id, hostname, port, peerHasFile);
                peersInfo.put(id, peerInfo);
                logger.createLog("Loaded peer: ID=" + id + ", Hostname=" + hostname + ", Port=" + port + ", HasFile=" + peerHasFile);

                if (tokens[0].equals(peerID)) {
                    this.hostName = hostname;
                    this.port = port;
                    this.peerHasFile = peerHasFile;
                    foundSelf = true;
                    logger.createLog("This peer [" + peerID + "] has Hostname=" + hostName + ", Port=" + port + ", HasFile=" + peerHasFile);
                }
            }
            if (!foundSelf) {
                throw new IllegalArgumentException("Peer ID " + peerID + " not found in PeerInfo.cfg");
            }
        }
    }

    private void startServer() {
        // This method starts the server socket and listens for incoming peer connections
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                System.out.println("Peer " + peerID + " is listening on port " + port);
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    // Hand off each connection to its own handler thread
                    handleNewlyAcceptedConnection(clientSocket);
                }
            } catch (IOException e) {
                System.err.println("Error in server thread: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private void handleNewlyAcceptedConnection(Socket clientSocket) {
        // This method handles a newly accepted connection from another peer
        new Thread(() -> {
            try {
                String remotePeerID = performHandshake(clientSocket);
                logger.createLog("TCP connection is built between P" + remotePeerID + " and P" + peerID);

                int remoteID = Integer.parseInt(remotePeerID);
                neighborSockets.put(remoteID, clientSocket);

                PeerConnectionHandler handler = new PeerConnectionHandler(clientSocket, remoteID, this);
                clientHandlers.put(remoteID, handler);
                peerCompletionMap.put(remoteID, false);

                new Thread(handler).start();

            } catch (IOException e) {
                System.err.println("Error handling incoming connection: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    // Establish TCP connections with all peers that have lower IDs
    private void connectToPeers() {
        for (PeerConfiguration peerInfo : peersInfo.values()) {
            // Stop trying to connect once we reach our own ID in the list
            if (peerInfo.ID == Integer.parseInt(peerID)) break;
            try {
                // Create socket connection to the peer
                Socket socket = new Socket(peerInfo.hostName, peerInfo.portNumber);

                // Initiate handshake process with the remote peer
                performHandshake(socket);

                // Log successful TCP connection establishment
                logger.createLog("TCP connection is built between P" + peerID + " and P" + peerInfo.ID);
                neighborSockets.put(peerInfo.ID, socket);

                // Create a handler to manage communication with this peer
                PeerConnectionHandler handler = new PeerConnectionHandler(socket, peerInfo.ID, this);
                clientHandlers.put(peerInfo.ID, handler);
                peerCompletionMap.put(peerInfo.ID, false); // Track if this peer has completed downloading

                // Start a new thread to listen to this peer
                new Thread(handler).start();
            } catch (IOException e) {
                System.err.println("Error connecting to peer " + peerInfo.ID + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // Handles sending and receiving the handshake message with a peer
    private String performHandshake(Socket socket) throws IOException {
        // Prepare handshake message of 32 bytes
        byte[] handshake = new byte[32];

        // First 18 bytes are a fixed header
        byte[] header = "P2PFILESHARINGPROJ".getBytes();
        System.arraycopy(header, 0, handshake, 0, header.length);

        // Next 10 bytes are zeroed out (padding)
        byte[] zeroBits = new byte[10];
        System.arraycopy(zeroBits, 0, handshake, 18, zeroBits.length);

        // Final 4 bytes represent the peer's ID
        byte[] peerIDBytes = ByteBuffer.allocate(4).putInt(Integer.parseInt(peerID)).array();
        System.arraycopy(peerIDBytes, 0, handshake, 28, peerIDBytes.length);

        // Send the handshake over the output stream
        OutputStream out = socket.getOutputStream();
        out.write(handshake);
        out.flush();
        logger.createLog("Peer [" + peerID + "] sent handshake to the remote peer.");

        // Read and verify the handshake response
        InputStream in = socket.getInputStream();
        byte[] receivedHandshake = new byte[32];
        int bytesRead = 0;
        while (bytesRead < 32) {
            int result = in.read(receivedHandshake, bytesRead, 32 - bytesRead);
            if (result == -1) {
                throw new IOException("Stream closed during handshake");
            }
            bytesRead += result;
        }

        // Check if header is valid
        byte[] receivedHeader = Arrays.copyOfRange(receivedHandshake, 0, 18);
        String headerString = new String(receivedHeader);
        if (!headerString.equals("P2PFILESHARINGPROJ")) {
            throw new IOException("Invalid handshake header");
        }

        // Extract and return the remote peer's ID
        byte[] remotePeerIDBytes = new byte[4];
        System.arraycopy(receivedHandshake, 28, remotePeerIDBytes, 0, 4);
        int remotePeerID = ByteBuffer.wrap(remotePeerIDBytes).getInt();
        logger.createLog("Peer [" + peerID + "] received valid handshake from Peer [" + remotePeerID + "].");

        return String.valueOf(remotePeerID);
    }

    // Periodically updates the list of preferred neighbors based on download rate or at random
    private void updatePreferredNeighbors() {
        try {
            // Filter peers that are interested in downloading pieces
            List<Integer> interestedNeighbors = new ArrayList<>();
            for (PeerConnectionHandler handler : clientHandlers.values()) {
                if (handler.isInterested()) {
                    interestedNeighbors.add(handler.getRemotePeerID());
                }
            }

            // If no peers are interested, reset the list and log
            if (interestedNeighbors.isEmpty()) {
                preferredNeighbors.clear();
                logger.createLog("Peer [" + peerID + "] has no interested neighbors.");
                return;
            }

            // Log update interval message
            logger.createLog("Every " + unchokingInterval + " seconds, Peer [" + peerID + "] recalculates preferred neighbors and sends CHOKE/UNCHOKE messages.");

            // Decide preferred neighbors randomly if this peer has the complete file
            if (hasCompleteFile.get()) {
                Collections.shuffle(interestedNeighbors);
                preferredNeighbors.clear();
                preferredNeighbors.addAll(interestedNeighbors.subList(0, Math.min(numberOfPreferredNeighbors, interestedNeighbors.size())));
            } else {
                // Otherwise, select peers based on highest download rate
                List<Integer> sortedNeighbors = new ArrayList<>(interestedNeighbors);
                sortedNeighbors.sort((a, b) -> {
                    int rateA = clientHandlers.get(a).gettrackDownloadRate();
                    int rateB = clientHandlers.get(b).gettrackDownloadRate();
                    return Integer.compare(rateB, rateA); // Sort in descending order
                });
                preferredNeighbors.clear();
                preferredNeighbors.addAll(sortedNeighbors.subList(0, Math.min(numberOfPreferredNeighbors, sortedNeighbors.size())));
            }

            // Send choke/unchoke based on selection
            for (PeerConnectionHandler handler : clientHandlers.values()) {
                int remotePeerID = handler.getRemotePeerID();
                if (preferredNeighbors.contains(remotePeerID)) {
                    if (handler.isChoked()) {
                        handler.sendUnchoke(); // Allow data transfer
                    }
                } else {
                    if (!handler.isChoked()) {
                        handler.sendChoke(); // Pause data transfer
                    }
                }
            }

            // Log the selected preferred neighbors
            String neighborList = String.join(", ", preferredNeighbors.stream().map(String::valueOf).collect(Collectors.toList()));
            logger.createLog("Peer [" + peerID + "] has the preferred neighbors [" + neighborList + "].");

            // Optional: log statuses of all current neighbors
            logNeighborStatus();

        } catch (Exception e) {
            System.err.println("Error updating preferred neighbors: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Checks if a specific piece index is present in this peer's bitfield
    public synchronized boolean hasPiece(int pieceIndex) {
        int byteIndex = pieceIndex / 8;
        int bitIndex = pieceIndex % 8;
        return (bitfield[byteIndex] & (1 << (7 - bitIndex))) != 0;
    }


    // Periodically optimistically unchoke a neighbor
    private void optimisticallyUnchokeNeighbor() {
        try {
            // Get choked and interested neighbors
            List<Integer> candidates = new ArrayList<>();
            for (PeerConnectionHandler handler : clientHandlers.values()) {
                if (handler.isInterested() && handler.isChoked() && !preferredNeighbors.contains(handler.getRemotePeerID())) {
                    candidates.add(handler.getRemotePeerID());
                }
            }

            if (candidates.isEmpty()) {
                return;
            }

            // Randomly select a neighbor
            Random random = new Random();
            int index = random.nextInt(candidates.size());
            int selectedPeerID = candidates.get(index);

            // Update optimistic unchoked neighbor
            optimisticUnchokedNeighbor = selectedPeerID;

            // Send unchoke message
            PeerConnectionHandler handler = clientHandlers.get(selectedPeerID);
            handler.sendUnchoke();

            // Log
            logger.createLog("Peer [" + peerID + "] has the optimistically unchoked neighbor [" + optimisticUnchokedNeighbor + "].");

            // Log status of all neighbors
            logNeighborStatus();

        } catch (Exception e) {
            System.err.println("Error in optimistically unchoking neighbor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Log the status of all neighbors
    private void logNeighborStatus() {
        StringBuilder statusLog = new StringBuilder("Peer [" + peerID + "] neighbor statuses:\n");
        for (PeerConnectionHandler handler : clientHandlers.values()) {
            int remotePeerID = handler.getRemotePeerID();
            boolean choked = handler.isChoked();
            boolean interested = handler.isInterested();
            statusLog.append("Neighbor [").append(remotePeerID).append("]: ")
                     .append(choked ? "Choked" : "Unchoked").append(", ")
                     .append(interested ? "Interested" : "Not Interested").append("\n");
        }
        logger.createLog(statusLog.toString());
    }

        // Periodically checks if all peers have completed downloading the file
    private void checkCompletion() {
        new Thread(() -> {
            while (true) {
                try {
                    // Wait until all expected peers are tracked in the completion map
                    if (peerCompletionMap.size() < peersInfo.size() - 1) {
                        logger.createLog("Waiting for all peers to connect. Currently tracked: " + peerCompletionMap.size() + " out of " + (peersInfo.size() - 1));
                        Thread.sleep(2000); // Recheck after delay
                        continue;
                    }

                    // If this peer has finished downloading, check others' completion status
                    if (hasCompleteFile.get()) {
                        boolean allComplete = true;
                        for (int pid : peerCompletionMap.keySet()) {
                            if (!peerCompletionMap.getOrDefault(pid, false)) {
                                allComplete = false;
                                break;
                            }
                        }

                        // If everyone has finished, shut down the program gracefully
                        if (allComplete) {
                            logger.createLog("Peer [" + peerID + "] has downloaded the complete file and all peers have completed.");
                            System.exit(0);
                        }
                    }

                    Thread.sleep(2000); // Repeat every 2 seconds
                } catch (InterruptedException e) {
                    System.err.println("Error in completion checker: " + e.getMessage());
                }
            }
        }).start();
    }

    // Reconstructs the original file by combining all the individual pieces
    private void mergeFilePieces() throws IOException {
        String outputFilePath = peerDirectory + "/" + fileName;
        try (FileOutputStream fos = new FileOutputStream(outputFilePath)) {
            for (int i = 0; i < totalPieces; i++) {
                String piecePath = peerDirectory + "/piece_" + i;
                File pieceFile = new File(piecePath);
                try (FileInputStream fis = new FileInputStream(pieceFile)) {
                    byte[] buffer = new byte[(int) pieceFile.length()];
                    int bytesRead = fis.read(buffer);
                    fos.write(buffer, 0, bytesRead); // Append each piece
                }
            }
        }
    }

    // Updates this peer's bitfield to indicate a new piece has been downloaded
    public synchronized void updateBitfield(int pieceIndex) {
        int byteIndex = pieceIndex / 8;
        int bitIndex = pieceIndex % 8;
        bitfield[byteIndex] |= (1 << (7 - bitIndex));
    }

    // Returns the current bitfield showing all pieces this peer has
    public synchronized byte[] getBitfield() {
        return bitfield;
    }

    // Checks if this peer has all pieces of the file
    public synchronized boolean isCompleted() {
        for (int i = 0; i < totalPieces; i++) {
            if (!hasPiece(i)) {
                return false;
            }
        }
        return true;
    }

    // Confirms file completion and performs final merge if not already marked as done
    public synchronized void checkAndSetCompletion() {
        if (isCompleted() && !hasCompleteFile.get()) {
            hasCompleteFile.set(true);
            logger.createLog("Peer [" + peerID + "] has downloaded the complete file.");
            try {
                mergeFilePieces(); // Assemble the full file from pieces
            } catch (IOException e) {
                System.err.println("Error merging file pieces: " + e.getMessage());
            }
        }
    }

    // Splits the input file into fixed-size pieces for distribution
    private void splitFileIntoPieces(File inputFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(inputFile)) {
            byte[] buffer = new byte[pieceSize];
            int bytesRead;
            int pieceIndex = 0;
            while ((bytesRead = fis.read(buffer)) != -1) {
                String piecePath = peerDirectory + "/piece_" + pieceIndex;
                try (FileOutputStream fos = new FileOutputStream(piecePath)) {
                    fos.write(buffer, 0, bytesRead);
                }
                pieceIndex++;
            }
            logger.createLog("Peer [" + peerID + "] has split the file into " + pieceIndex + " pieces.");
        }
    }

    // Entry point to start the peer. Expects peer ID as command-line argument
        public static void main(String[] args) throws IOException {
            if (args.length != 1) {
                System.err.println("Usage: java Peer <peerID>");
                return;
            }
            String peerID = args[0];
            Peer peer = new Peer(peerID);
            peer.start(); // Begin execution of the peer logic
        }
}
