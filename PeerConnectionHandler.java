import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.*;

public class PeerConnectionHandler implements Runnable {
    private Socket socket;
    private int remotePeerID;
    private Peer peer;
    private boolean choked = true; // By default, peers are choked (not allowed to download)
    private boolean interested = false; // Tracks if this peer is interested in downloading
    private byte[] remotePeersBitfieldMessage; // Tracks which pieces the remote peer has
    private int trackDownloadRate = 0; // Tracks rate for selecting preferred neighbors
    private int piecesDownloaded = 0; // Pieces received from this peer
    private int totalNoOfBytesReceivedFromPeer = 0; // Total data downloaded from this peer
    private boolean markedComplete = false; // ✅ Prevent marking peer as complete multiple times

    // Constructor initializes the socket, peer ID, and peer instance
    public PeerConnectionHandler(Socket socket, int remotePeerID, Peer peer) {
        this.socket = socket;
        this.remotePeerID = remotePeerID;
        this.peer = peer;

        // Allocate space for the remote peer’s bitfield
        remotePeersBitfieldMessage = new byte[(int) Math.ceil((double) peer.getTotalPieces() / 8)];
        Arrays.fill(remotePeersBitfieldMessage, (byte) 0x00); // Start with empty bitfield
    }

    // Main communication loop with the connected peer
    @Override
    public void run() {
        try {
            // Send our own bitfield after connection setup
            if (peer.getBitfield() != null) {
                sendBitfield();
            }

            // Listen for incoming messages from the remote peer
            while (true) {
                Message message = Message.receiveP2PBitTorrentMessages(socket);
                handleMessage(message); // Process the message accordingly
            }

        } catch (IOException e) {
            // Log the disconnection and clean up
            peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] connection closed with [" + remotePeerID + "].");
            peer.removeClientHandler(remotePeerID); // Remove from handler list
        }
    }

    // Sends this peer’s bitfield to the connected peer
    private void sendBitfield() throws IOException {
        byte[] bitfield = peer.getBitfield();
        if (bitfield != null) {
            Message bitfieldMessage = new Message(P2PMessages.BITFIELD, bitfield);
            bitfieldMessage.sendMessage(socket);
            peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] sent BITFIELD message to [" + remotePeerID + "].");
        }
    }

    // Routes the incoming message to the appropriate handler based on message type
    private void handleMessage(Message message) throws IOException {
        switch (message.getType()) {
            case BITFIELD:
                processReceivedBitfield(message.getPLoad());
                break;
            case INTERESTED:
                handleInterestedMessage();
                break;
            case NOT_INTERESTED:
                handleNotInterestedMessage();
                break;
            case REQUEST:
                handleRequestMessage(message.getPLoad());
                break;
            case PIECE:
                handlePieceMessage(message.getPLoad());
                break;
            case HAVE:
                handleHaveMessage(message.getPLoad());
                break;
            case CHOKE:
                handleChokeMessage();
                break;
            case UNCHOKE:
                handleUnchokeMessage();
                break;
            default:
                System.err.println("Unknown message type received from peer " + remotePeerID);
        }
    }

    // Handles the bitfield sent by the remote peer
    private void processReceivedBitfield(byte[] payload) throws IOException {
        System.arraycopy(payload, 0, remotePeersBitfieldMessage, 0, payload.length);
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] received the BITFIELD message from [" + remotePeerID + "].");

        // Determine if we are interested in any piece the remote peer has
        boolean isInterested = false;
        for (int i = 0; i < peer.getTotalPieces(); i++) {
            if (!peer.hasPiece(i) && hasPiece(i)) {
                isInterested = true;
                break;
            }
        }

        // Send appropriate interest message based on availability
        if (isInterested) {
            sendInterested();
            interested = true;
        } else {
            sendNotInterested();
            interested = false;
        }

        // ✅ Check if remote peer already has full file and mark complete if so
        if (getremotePeersBitfieldMessagePieceCount() == peer.getTotalPieces()) {
            peer.markPeerComplete(remotePeerID);
        }

        // Log current peer status for debugging or monitoring
        logPeerStatusSummary();
    }

    // Handler when the remote peer expresses interest in downloading pieces
    private void handleInterestedMessage() {
        interested = true;
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] received the 'interested' message from [" + remotePeerID + "].");

        // Log peer interest status
        logPeerStatusSummary();
    }

    // Handler when the remote peer is no longer interested
    private void handleNotInterestedMessage() {
        interested = false;
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] received the 'not interested' message from [" + remotePeerID + "].");

        // Log updated peer status
        logPeerStatusSummary();
    }


    private void handleRequestMessage(byte[] payload) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int pieceIndex = buffer.getInt();

        if (!choked && peer.hasPiece(pieceIndex)) {
            // Send piece
            sendPiece(pieceIndex);
            // Log the piece sent
            peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] sent piece [" + pieceIndex + "] to [" + remotePeerID + "].");
        }
    }

    private void handlePieceMessage(byte[] payload) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int pieceIndex = buffer.getInt();
        byte[] pieceData = Arrays.copyOfRange(payload, 4, payload.length);

        // Save piece
        savePiece(pieceIndex, pieceData);

        // Update bitfield
        peer.updateBitfield(pieceIndex);
        peer.checkAndSetCompletion();

        // Send have message to all peers
        for (PeerConnectionHandler handler : peer.getClientHandlers()) {
            handler.sendHave(pieceIndex);
        }

        // Update download rate
        piecesDownloaded++;
        trackDownloadRate++;
        totalNoOfBytesReceivedFromPeer += pieceData.length;

        // Log
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] has downloaded the piece [" + pieceIndex + "] from [" + remotePeerID + "]. Now the number of pieces it has is [" + getNumberOfPieces() + "].");
        peer.getLogger().createLog("Total bytes received so far: " + totalNoOfBytesReceivedFromPeer + " bytes.");

        // Decide next piece to request
        requestPiece();
    }

    private void handleHaveMessage(byte[] payload) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int pieceIndex = buffer.getInt();

        // Update remote bitfield
        setPieceAvailable(pieceIndex);

        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] received the 'have' message from [" + remotePeerID + "] for the piece [" + pieceIndex + "].");

        // Determine if interested
        if (!peer.hasPiece(pieceIndex)) {
            sendInterested();
            interested = true;
        }
        // NEW: Check again on HAVE if remote peer has full file
        if (getremotePeersBitfieldMessagePieceCount() == peer.getTotalPieces()) {
            peer.markPeerComplete(remotePeerID);
        }
        // Log neighbor status
        logPeerStatusSummary();
    }

        // Count how many pieces the remote peer currently has
    private int getremotePeersBitfieldMessagePieceCount() {
        int count = 0;
        for (int i = 0; i < peer.getTotalPieces(); i++) {
            if (hasPiece(i)) count++;
        }
        return count;
    }

    // Handle CHOKE message: stop sending requests to this peer
    private void handleChokeMessage() {
        choked = true;
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] is choked by [" + remotePeerID + "].");

        // Update neighbor status in logs
        logPeerStatusSummary();
    }

    // Handle UNCHOKE message: we're now allowed to request pieces again
    private void handleUnchokeMessage() throws IOException {
        choked = false;
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] is unchoked by [" + remotePeerID + "].");

        // Log the status of this neighbor
        logPeerStatusSummary();

        // Try to request a piece from this peer
        requestPiece();
    }

    // Send INTERESTED message to let the peer know we want pieces
    private void sendInterested() throws IOException {
        Message message = new Message(P2PMessages.INTERESTED);
        message.sendMessage(socket);
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] sent INTERESTED message to [" + remotePeerID + "].");
    }

    // Send NOT INTERESTED message when we no longer need pieces from this peer
    private void sendNotInterested() throws IOException {
        Message message = new Message(P2PMessages.NOT_INTERESTED);
        message.sendMessage(socket);
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] sent NOT INTERESTED message to [" + remotePeerID + "].");
    }

    // Inform the peer that we now have a specific piece
    public void sendHave(int pieceIndex) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(pieceIndex);
        Message message = new Message(P2PMessages.HAVE, buffer.array());
        message.sendMessage(socket);
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] sent HAVE message for piece [" + pieceIndex + "] to [" + remotePeerID + "].");
    }

    // Send CHOKE message to stop this peer from requesting pieces from us
    public void sendChoke() throws IOException {
        Message message = new Message(P2PMessages.CHOKE);
        message.sendMessage(socket);
        choked = true;
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] is choking [" + remotePeerID + "].");

        // Log updated status
        logPeerStatusSummary();
    }

    // Send UNCHOKE message to allow this peer to request pieces from us
    public void sendUnchoke() throws IOException {
        Message message = new Message(P2PMessages.UNCHOKE);
        message.sendMessage(socket);
        choked = false;
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] is unchoking [" + remotePeerID + "].");

        // Log updated status
        logPeerStatusSummary();

        // Request a piece now that we’re unchoked
        requestPiece();
    }

    // Sends the requested piece to the remote peer
    private void sendPiece(int pieceIndex) throws IOException {
        String piecePath = peer.getPeerDirectory() + "/piece_" + pieceIndex;
        File pieceFile = new File(piecePath);
        byte[] pieceData = new byte[(int) pieceFile.length()];
        try (FileInputStream fis = new FileInputStream(pieceFile)) {
            fis.read(pieceData);
        }

        ByteBuffer buffer = ByteBuffer.allocate(4 + pieceData.length);
        buffer.putInt(pieceIndex);
        buffer.put(pieceData);

        Message message = new Message(P2PMessages.PIECE, buffer.array());
        message.sendMessage(socket);
    }

    // Selects a piece we don't have but the remote peer does, then sends a REQUEST message
    private void requestPiece() throws IOException {
        if (choked) {
            return; // Can't request if we're choked
        }
        List<Integer> missingPieces = new ArrayList<>();
        for (int i = 0; i < peer.getTotalPieces(); i++) {
            if (!peer.hasPiece(i) && hasPiece(i)) {
                missingPieces.add(i);
            }
        }
        if (missingPieces.isEmpty()) {
            sendNotInterested(); // Nothing to request
            interested = false;
            return;
        }

        // Randomly pick one missing piece to request
        Random random = new Random();
        int pieceIndex = missingPieces.get(random.nextInt(missingPieces.size()));

        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(pieceIndex);
        Message message = new Message(P2PMessages.REQUEST, buffer.array());
        message.sendMessage(socket);
        peer.getLogger().createLog("Peer [" + peer.getPeerID() + "] sent REQUEST for piece [" + pieceIndex + "] to [" + remotePeerID + "].");
    }

    // Save the received piece to disk
    private void savePiece(int pieceIndex, byte[] pieceData) throws IOException {
        String piecePath = peer.getPeerDirectory() + "/piece_" + pieceIndex;
        try (FileOutputStream fos = new FileOutputStream(piecePath)) {
            fos.write(pieceData);
        }
    }

    // Check if the remote peer has a specific piece
    private boolean hasPiece(int index) {
        int byteIndex = index / 8;
        int bitIndex = 7 - (index % 8);
        return (remotePeersBitfieldMessage[byteIndex] & (1 << bitIndex)) != 0;
    }

    // Mark a specific piece as available in the remote bitfield
    private void setPieceAvailable(int index) {
        int byteIndex = index / 8;
        int bitIndex = 7 - (index % 8);
        remotePeersBitfieldMessage[byteIndex] |= (1 << bitIndex);
    }

    // Returns if this peer is currently interested in pieces from the remote peer
    public boolean isInterested() {
        return interested;
    }

    // Returns if this peer is currently choked by the remote peer
    public boolean isChoked() {
        return choked;
    }

    // Get the ID of the remote peer
    public int getRemotePeerID() {
        return remotePeerID;
    }

    // Returns and resets this peer’s download rate used for neighbor selection
    public int gettrackDownloadRate() {
        int rate = trackDownloadRate;
        trackDownloadRate = 0; // Reset for next interval
        return rate;
    }

    // Count how many pieces this peer has
    public int getNumberOfPieces() {
        int count = 0;
        for (int i = 0; i < peer.getTotalPieces(); i++) {
            if (peer.hasPiece(i)) {
                count++;
            }
        }
        return count;
    }

    // Returns true if this peer has the complete file
    public boolean hasCompleteFile() {
        return peer.hasCompleteFile();
    }

    // Helper to mark peer as complete if all pieces are present
    private void markPeerIfComplete() {
        if (!markedComplete && getremotePeersBitfieldMessagePieceCount() == peer.getTotalPieces()) {
            peer.markPeerComplete(remotePeerID);
            markedComplete = true;
        }
    }

    // Logs whether this peer is interested/choked in a human-readable way
    private void logPeerStatusSummary() {
        String status = "Neighbor [" + remotePeerID + "]: " +
                        (choked ? "Choked" : "Unchoked") + ", " +
                        (interested ? "Interested" : "Not Interested");
        peer.getLogger().createLog(status);
    }
}