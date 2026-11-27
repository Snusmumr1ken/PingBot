package pingbot;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

class TcpAvailabilityCheckerTest {
    @Test
    void reportsWhetherTheConfiguredLocalPortAcceptsConnections() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        int port;
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            port = server.getLocalPort();
            assertTrue(new TcpAvailabilityChecker(loopback.getHostAddress(), port, 500).getAsBoolean());
        }

        assertFalse(new TcpAvailabilityChecker(loopback.getHostAddress(), port, 500).getAsBoolean());
    }
}
