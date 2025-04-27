import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;

public class Logger {
    private PrintWriter ptwrites;

    /**
     * This method is used for writing a timestamped message to both
     * the log file and the console. It ensures thread safety by using
     * the 'synchronized' keyword so that logs from multiple threads
     * don't get mixed up.
     *
     * @param msg The message to be logged
     */
    public synchronized void createLog(String msg) {
        String timeStm = new Date().toString();  // Get the current timestamp

        // Write the log message to the file
        ptwrites.println("[" + timeStm + "]: " + msg);

        // Also print the message to the console for immediate visibility
        System.out.println("[" + timeStm + "]: " + msg);
    }

    /**
     * This constructor is used for setting up the logger.
     * It opens a file (or appends to it if it already exists) to write log messages.
     *
     * @param fle The path to the log file where messages will be stored
     */
    public Logger(String fle) {
        try {
            // Open the file in append mode, and auto-flush the stream after every println
            ptwrites = new PrintWriter(new FileWriter(fle, true), true);
        } catch (IOException exp) {
            // If something goes wrong while opening the file, show an error on the console
            System.err.println("Error initializing logger: " + exp.getMessage());
        }
    }
}
