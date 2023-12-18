package proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

public class ClientHandler implements Handler {
    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);
    private static final int BUFFER_SIZE = 4096;
    private final SocketChannel clientChannel;
    private final SelectionKey clientKey;
    private ClientState state;
    private byte authMethod = ProtocolParams.NO_AUTH;
    private final byte[] connectResponse = ProtocolParams.CONNECTING_REPLY_TEMPLATE;
    private byte responseCode;
    private String serverName;
    private InetAddress serverAddress;
    private int serverPort;
    private ServerHandler serverHandler;
    private boolean isClosed;

    public ClientHandler(SelectionKey key) throws IOException {
        clientChannel = ((ServerSocketChannel) key.channel()).accept();
        clientChannel.configureBlocking(false);
        clientKey = clientChannel.register(key.selector(), SelectionKey.OP_READ);
        state = ClientState.GREETING;
    }

    public SocketChannel getClientChannel() {
        return clientChannel;
    }

    public SelectionKey getClientKey() {
        return clientKey;
    }

    public String getServerName() {
        return serverName;
    }

    public boolean isClosed() {
        return isClosed;
    }

    @Override
    public void handleKey() {
        switch (state) {
            case GREETING -> {
                if (clientKey.isReadable()) {
                    readGreeting();
                } else if (clientKey.isWritable()) {
                    writeGreeting();
                }
            }
            case CONNECTING -> {
                if (clientKey.isReadable()) {
                    readConnecting();
                } else if (clientKey.isWritable()) {
                    writeConnecting();
                }
            }
            case CONNECTED -> {
                if (clientKey.isReadable()) {
                    read();
                }
                else if (clientKey.isWritable()) {
                    write();
                }
            }
        }
    }

    private void readGreeting() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        try {
            int len = clientChannel.read(byteBuffer);
            if (len < 2) {
                log.error("Received " + len + " bytes. Full greeting message wasn't received");
                close();
                return;
            }
            byte[] bytes = Arrays.copyOfRange(byteBuffer.array(), 0, len);
            log.info("Greeting received : " + Arrays.toString(bytes));
            if (bytes[0] != ProtocolParams.SUPPORTED_VERSION) {
                log.error("Client doesn't support SOCKS5");
                return;
            }
            boolean isFoundNoAuth = false;
            for (int i = 0; i < bytes[1]; ++i) {
                if (bytes[i + 2] == ProtocolParams.NO_AUTH) {
                    isFoundNoAuth = true;
                    break;
                }
            }
            if (!isFoundNoAuth) {
                log.error("No authentication method wasn't suggested");
                authMethod = ProtocolParams.ERROR_CODE;
            }
            state = ClientState.GREETING;
            clientKey.interestOps(SelectionKey.OP_WRITE);
        } catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    private void writeGreeting() {
        byte[] bytes = new byte[] { ProtocolParams.SUPPORTED_VERSION, authMethod };
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        try {
            clientChannel.write(byteBuffer);
            log.info("Greeting reply was sent : " + Arrays.toString(byteBuffer.array()));
            if (authMethod == ProtocolParams.ERROR_CODE) {
                close();
            } else {
                state = ClientState.CONNECTING;
                clientKey.interestOps(SelectionKey.OP_READ);
            }
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    private void readConnecting() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        try {
            int len = clientChannel.read(byteBuffer);
            if (len < 1) {
                log.error("Received " + len + " bytes");
                close();
                return;
            }
            byte[] connectRequest = Arrays.copyOfRange(byteBuffer.array(), 0, len);
            log.info("Connection info received : " + Arrays.toString(connectRequest));
            if (connectRequest[0] != ProtocolParams.SUPPORTED_VERSION) {
                log.error("Wrong SOCKS version received");
                close();
                return;
            }
            if (connectRequest[1] != ProtocolParams.SUPPORTED_COMMAND_CODE) {
                log.error("Unsupported command code was received");
                responseCode = ProtocolParams.UNSUPPORTED_COMMAND_CODE;
                readyToWriteConnecting();
                return;
            }

            serverPort = ByteBuffer.wrap(Arrays.copyOfRange(connectRequest, len - 2, len)).getShort();
            log.info("Host port : " + serverPort);

            switch (connectRequest[3]) {
                case ProtocolParams.ADDR_TYPE_IPV4 -> {
                    byte[] addressBytes = Arrays.copyOfRange(connectRequest, 4, 8);
                    serverAddress = InetAddress.getByAddress(addressBytes);
                    serverName = serverAddress.getHostAddress();
                    log.info("Server has IPv4 address : " + serverAddress.getHostAddress());
                    launchServerHandler();
                    state = ClientState.WAIT_SERVER;
                    clientKey.interestOps(0);
                }
                case ProtocolParams.ADDR_TYPE_HOST -> {
                    int addressLength = connectRequest[4];
                    serverName = new String(Arrays.copyOfRange(connectRequest, 5, addressLength + 5));
                    log.info("Server name : " + serverName);
                    DnsResolver.getInstance().addNewRequest(this, serverName);
                    state = ClientState.WAIT_DNS;
                    clientKey.interestOps(0);
                }
                case ProtocolParams.ADDR_TYPE_IPV6 -> {
                    log.error("Proxy server doesn't support IPv6 addresses");
                    responseCode = ProtocolParams.UNSUPPORTED_ADDRESS_TYPE;
                    readyToWriteConnecting();
                }
                default -> {
                    log.error("Wrong type of address");
                    responseCode = ProtocolParams.UNSUPPORTED_ADDRESS_TYPE;
                    readyToWriteConnecting();
                }
            }
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    private void launchServerHandler() {
        try {
            serverHandler = new ServerHandler(this, serverAddress, serverPort);
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    public void setServerAddress(InetAddress serverAddress) {
        if (state == ClientState.WAIT_DNS) {
            if (serverAddress == null) {
                log.info("DNS server can't find domain " + serverName);
                responseCode = ProtocolParams.UNAVAILABLE_HOST;
                readyToWriteConnecting();
                return;
            }
            this.serverAddress = serverAddress;
            state = ClientState.WAIT_SERVER;
            log.info("Host address : " + serverAddress.getHostAddress());
            launchServerHandler();
        }
    }

    public void readyToWriteConnecting() {
        state = ClientState.CONNECTING;
        clientKey.interestOps(SelectionKey.OP_WRITE);
        log.info("Client ready to response");
    }

    public void setResponseCode(byte responseCode) {
        this.responseCode = responseCode;
    }

    private void writeConnecting() {
        connectResponse[1] = responseCode;
        if (serverAddress != null) System.arraycopy(serverAddress.getAddress(), 0, connectResponse, 4, 4);
        connectResponse[8] = (byte) (serverPort >> 8);
        connectResponse[9] = (byte) serverPort;

        ByteBuffer byteBuffer = ByteBuffer.wrap(connectResponse);
        try {
            clientChannel.write(byteBuffer);
            log.info("Response sent : " + Arrays.toString(byteBuffer.array()));
            if (responseCode == ProtocolParams.CONNECTION_ESTABLISHED) {
                state = ClientState.CONNECTED;
                clientKey.interestOps(SelectionKey.OP_READ);
            }
            else {
                close();
            }
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    private void read() {
        try {
            int len = clientChannel.read(serverHandler.getRequestBuffer());
            if (len < 0) {
                close();
                return;
            }
            log.info(serverName + " : " + len + " bytes received from client");
            serverHandler.getServerKey().interestOps(serverHandler.getServerKey().interestOps() |
                    SelectionKey.OP_WRITE);
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    private void write() {
        try {
            serverHandler.getResponseBuffer().flip();
            int len = clientChannel.write(serverHandler.getResponseBuffer());
            if (len < 0) {
                close();
                return;
            }
            log.info(serverName + " : " + len + " bytes sent to client");
            if (serverHandler.getResponseBuffer().remaining() == 0) {
                serverHandler.getResponseBuffer().clear();
                clientKey.interestOps(SelectionKey.OP_READ);
                if (serverHandler.isClosed()) {
                    close();
                }
            }
            else {
                serverHandler.getResponseBuffer().compact();
            }
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    public void close() {
        clientKey.cancel();
        Proxy.getInstance().removeChannelFromMap(clientChannel);
        try {
            clientChannel.close();
        }
        catch (IOException e) {
            log.error(e.toString());
        }
        log.info(serverName + " : " + "client closed");
        isClosed = true;
        if (serverHandler != null && !serverHandler.isClosed()) {
            serverHandler.close();
        }
    }
}