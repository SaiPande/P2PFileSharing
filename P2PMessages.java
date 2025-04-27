public enum P2PMessages {
    // These are the different types of messages that peers can exchange in the P2P protocol.
    
    CHOKE((byte) 0),           // Tells the peer to stop sending data (choked).
    UNCHOKE((byte) 1),         // Tells the peer it's allowed to send data again.
    INTERESTED((byte) 2),      // Indicates that a peer is interested in downloading pieces.
    NOT_INTERESTED((byte) 3),  // Indicates that a peer is not interested in any pieces right now.
    HAVE((byte) 4),            // Notifies peers that a new piece has been downloaded.
    BITFIELD((byte) 5),        // Sends a bitfield showing which pieces the sender has.
    REQUEST((byte) 6),         // Requests a specific piece from another peer.
    PIECE((byte) 7);           // Contains the actual piece data being sent.

    private final byte type;  // Byte representation of each message type for wire transmission

    // Constructor to assign the byte value to each message type
    P2PMessages(byte type) {
        this.type = type;
    }

    // Getter to retrieve the byte value of a message type
    public byte getValue() {
        return type;
    }

    /**
     * This static method is used to map a byte value received from the network
     * back to the corresponding enum constant. It's helpful when decoding messages.
     *
     * @param typeByte The byte value of the message type
     * @return Corresponding P2PMessages enum value
     * @throws IllegalArgumentException If no matching message type is found
     */
    public static P2PMessages fromByte(byte typeByte) {
        for (P2PMessages type : values()) {
            if (type.type == typeByte) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid message type: " + typeByte);
    }
}
