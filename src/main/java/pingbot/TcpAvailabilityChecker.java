package pingbot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.BooleanSupplier;

record TcpAvailabilityChecker(String host, int port, int timeoutMillis) implements BooleanSupplier {
    @Override
    public boolean getAsBoolean() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
