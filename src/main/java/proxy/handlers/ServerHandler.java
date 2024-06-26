package proxy.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import proxy.Proxy;
import proxy.protocol.ProtocolParams;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class ServerHandler implements Handler {
    private static final Logger log = LoggerFactory.getLogger(ServerHandler.class);
    private static final int BUFFER_SIZE = 4096;
    private final SocketChannel serverChannel;
    private final SelectionKey serverKey;
    private final ClientHandler clientHandler;
    private final ByteBuffer inputBuffer;
    private final ByteBuffer outputBuffer;
    private boolean isClosed = false;

    public ServerHandler(ClientHandler clientHandler, InetAddress serverAddress, int serverPort) throws IOException {
        this.clientHandler = clientHandler;
        serverChannel = SocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.connect(new InetSocketAddress(serverAddress, serverPort));
        log.info("Try to connect to server : " + serverAddress.getHostAddress() + ":" + serverPort);
        Proxy.getInstance().putNewChannel(serverChannel, this);
        serverKey = serverChannel.register(clientHandler.getClientKey().selector(), SelectionKey.OP_CONNECT);
        inputBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        outputBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    }

    @Override
    public void handleKey() {
        if (serverKey.isConnectable()) {
            connect();
        }
        else if (serverKey.isWritable()) {
            write();
        }
        else if (serverKey.isReadable()) {
            read();
        }
    }

    private void connect() {
        try {
            serverChannel.finishConnect();
            serverKey.interestOps(SelectionKey.OP_READ);
            log.info("Connect finished");
            clientHandler.setResponseCode(ProtocolParams.CONNECTION_ESTABLISHED);
            clientHandler.readyToWriteConnecting();
        }
        catch (IOException e) {
            log.error(e.toString());
            clientHandler.setResponseCode(ProtocolParams.UNAVAILABLE_HOST);
            clientHandler.readyToWriteConnecting();
        }
    }

    private void write() {
        try {
            inputBuffer.flip();
            int len = serverChannel.write(inputBuffer);
            if (len < 0) {
                close();
                return;
            }
            log.info(clientHandler.getServerName() + " : " + len + " bytes sent to server");
            if (inputBuffer.remaining() == 0) {
                inputBuffer.clear();
                serverKey.interestOps(SelectionKey.OP_READ);
            }
            else {
                inputBuffer.compact();
            }
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    private void read() {
        try {
            int len = serverChannel.read(outputBuffer);
            if (len < 0) {
                close();
                return;
            }
            log.info(clientHandler.getServerName() + " : " + len + " bytes received from server");
            clientHandler.getClientKey().interestOps(clientHandler.getClientKey().interestOps() |
                    SelectionKey.OP_WRITE);
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    public ByteBuffer getInputBuffer() {
        return inputBuffer;
    }

    public ByteBuffer getOutputBuffer() {
        return outputBuffer;
    }

    public SelectionKey getServerKey() {
        return serverKey;
    }

    public void close() {
        serverKey.cancel();
        Proxy.getInstance().removeChannelFromMap(serverChannel);
        try {
            if (serverChannel != null) {
                serverChannel.shutdownInput();
                serverChannel.shutdownOutput();
            }
        } catch (IOException e) {
            log.error(e.toString());
        }
        try {
            if (serverChannel != null) serverChannel.close();
        }
        catch (IOException e) {
            log.error(e.toString());
        }
        isClosed = true;
        log.info(clientHandler.getServerName() + " : " + "server closed");

        if (outputBuffer.remaining() == 0 && !clientHandler.isClosed()) {
            clientHandler.close();
        }
    }

    public boolean isClosed() {
        return isClosed;
    }
}