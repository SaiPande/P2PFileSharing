import java.nio.ByteBuffer;
import java.io.IOException;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;

public class Message {
    private int length;           // Total length of the message (type + payload)
    private P2PMessages type;     // Enum representing the type of message
    private byte[] pload;         // Payload of the message, if any

    // Getter for the payload
    public byte[] getPLoad() {
        return pload;
    }

    /**
     * This constructor is used for messages that do not carry any payload.
     * Only the message type is set, and length is 1 (just the type byte).
     */
    public Message(P2PMessages type) {
        this.type = type;
        this.pload = null;
        this.length = 1;
    }

    // Getter for the message type
    public P2PMessages getType() {
        return type;
    }

    /**
     * This constructor is used for messages with a payload.
     * It sets the message type and stores the payload,
     * calculating total message length as 1 (type) + payload length.
     */
    public Message(P2PMessages type, byte[] pload) {
        this.type = type;
        this.pload = pload;
        this.length = 1 + (pload != null ? pload.length : 0);
    }

    /**
     * This method is used to receive a BitTorrent-style P2P message from a socket.
     * It first reads the length (4 bytes), then reads the full message based on that length.
     * It extracts the message type and payload, if any, and returns a new Message object.
     */
    public static Message receiveP2PBitTorrentMessages(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();

        byte[] lenByte = new byte[4]; // Buffer to store message length
        int lengthOfByte = 0;

        // Read exactly 4 bytes to get the message length
        while (lengthOfByte < 4) {
            int finalRlt = in.read(lenByte, lengthOfByte, 4 - lengthOfByte);
            if (finalRlt == -1) {
                throw new IOException("End of stream");
            }
            lengthOfByte += finalRlt;
        }

        // Convert the 4 bytes to an int to get the message length
        int length = ByteBuffer.wrap(lenByte).getInt();

        // Now read the actual message of the given length
        byte[] msgByt = new byte[length];
        lengthOfByte = 0;

        while (lengthOfByte < length) {
            int resultingPayload = in.read(msgByt, lengthOfByte, length - lengthOfByte);
            if (resultingPayload == -1) {
                throw new IOException("End of stream");
            }
            lengthOfByte += resultingPayload;
        }

        // Extract message type (first byte) and payload (remaining bytes, if any)
        byte typeB = msgByt[0];
        P2PMessages p2pmsgtype = P2PMessages.fromByte(typeB);

        byte[] pload = null;
        if (length > 1) {
            pload = new byte[length - 1];
            System.arraycopy(msgByt, 1, pload, 0, length - 1);
        }

        // Return the fully constructed message
        return new Message(p2pmsgtype, pload);
    }

    /**
     * This method is used to send a BitTorrent-style P2P message over a socket.
     * It prepares a byte buffer containing the length, type, and payload (if any),
     * then writes it to the socket's output stream.
     */
    public void sendMessage(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();

        // Allocate space: 4 bytes for length, 1 for type, and payload bytes
        ByteBuffer buff = ByteBuffer.allocate(4 + length);

        // Add the message length and type to the buffer
        buff.putInt(length);
        buff.put(type.getValue());

        // If payload exists, add it too
        if (pload != null) {
            buff.put(pload);
        }

        // Write the buffer to the socket and flush
        out.write(buff.array());
        out.flush();
    }
}
