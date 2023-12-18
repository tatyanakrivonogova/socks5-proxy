package proxy.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import proxy.Proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class DnsHandler implements Handler {
    private static class DnsRequest {
        ClientHandler clientHandler;
        String address;

        private DnsRequest(ClientHandler clientHandler, String address) {
            this.clientHandler = clientHandler;
            this.address = address;
        }
    }
    private static final Logger log = LoggerFactory.getLogger(DnsHandler.class);
    private static DnsHandler instance;
    private static final int BUFFER_SIZE = 512;
    private DatagramChannel dnsChannel;
    private Queue<DnsRequest> requestQueue;
    private Map<Name, ClientHandler> responseQueue;
    private SelectionKey dnsKey;

    private DnsHandler() {}
    public static DnsHandler getInstance() {
        if (instance == null) {
            instance = new DnsHandler();
        }
        return instance;
    }

    public void start(String host, int port, Selector selector) throws IOException {
        InetSocketAddress dnsServer = ResolverConfig.getCurrentConfig().server();
        dnsChannel = DatagramChannel.open();
        dnsChannel.socket().connect(dnsServer);
        dnsChannel.configureBlocking(false);
        dnsKey = dnsChannel.register(selector, 0);
        Proxy.getInstance().putNewChannel(dnsChannel, this);
        requestQueue = new ConcurrentLinkedDeque<>();
        responseQueue = new ConcurrentHashMap<>();
        log.info("DNS resolver started. Host : " + host + ". Port : " + port + ". DNS Server : "
                + dnsServer.toString());
    }

    public void addNewRequest(ClientHandler clientHandler, String address) {
        requestQueue.add(new DnsRequest(clientHandler, address));
        dnsKey.interestOps(dnsKey.interestOps() | SelectionKey.OP_WRITE);
        log.info("New DNS request : " + address);
    }

    @Override
    public void handleKey() {
        if (dnsKey.isWritable()) {
            writeDnsMessage();
        }
        if (dnsKey.isReadable()) {
            readDnsMessage();
        }
    }

    private void writeDnsMessage() {
        Message message = new Message();
        Header header = new Header();
        header.setFlag(Flags.AD);
        header.setFlag(Flags.RD);
        message.setHeader(header);
        DnsRequest request = requestQueue.remove();
        try {
            message.addRecord(Record.newRecord(Name.fromString(request.address, Name.root), Type.A, DClass.IN),
                    Section.QUESTION);
            responseQueue.put(Name.fromString(request.address, Name.root), request.clientHandler);
        }
        catch (IOException e) {
            log.error(e.toString());
        }

        ByteBuffer byteBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        byte[] messageBytes = message.toWire();
        byteBuffer.put(messageBytes);
        byteBuffer.flip();

        try {
            log.info("Sending DNS request");
            dnsChannel.write(byteBuffer);
            dnsKey.interestOps(dnsKey.interestOps() | SelectionKey.OP_READ);
        }
        catch (IOException e) {
            log.error(e.toString());
        }

        if (requestQueue.isEmpty()) {
            dnsKey.interestOps(SelectionKey.OP_READ);
        }
    }

    private void readDnsMessage() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        try {
            int len = dnsChannel.read(byteBuffer);
            if (len < 1) {
                return;
            }
            Message message = new Message(byteBuffer.array());
            Name hostName = message.getQuestion().getName();
            List<Record> answers = message.getSection(Section.ANSWER);
            log.info("Received DNS response for " + hostName);
            if (!responseQueue.containsKey(hostName)) {
                return;
            }
            ClientHandler clientHandler = responseQueue.get(hostName);
            for (Record answer : answers) {
                if (answer.getType() == Type.A) {
                    InetAddress inetAddress = ((ARecord) answer).getAddress();
                    clientHandler.setServerAddress(inetAddress);
                    break;
                }
            }
            clientHandler.setServerAddress(null);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
}