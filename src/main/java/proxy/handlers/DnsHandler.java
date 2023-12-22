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

public class DnsHandler implements Handler, LostDatagramsHandler {
    private static class DnsRequest {
        ClientHandler clientHandler;
        String address;

        private DnsRequest(ClientHandler clientHandler, String address) {
            this.clientHandler = clientHandler;
            this.address = address;
        }
    }

    private static class DnsRequestAttempt {
        int attemptsNumber;
        DnsRequest request;
        private DnsRequestAttempt(int attemptsNumber, DnsRequest request) {
            this.attemptsNumber = attemptsNumber;
            this.request = request;
        }
        private DnsRequest getRequest() { return request; }
        private int getAttemptsNumber() { return attemptsNumber; }
    }
    private static final Logger log = LoggerFactory.getLogger(DnsHandler.class);
    private static DnsHandler instance;
    private static final int BUFFER_SIZE = 512;
    private DatagramChannel dnsChannel;
    private Queue<DnsRequest> requestQueue;
    private Map<Name, ClientHandler> responseQueue;
    private Map<Name, DnsRequestAttempt> notCompletedRequests;
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
        //InetSocketAddress dnsServer = new InetSocketAddress("93.184.216.34", 9000);
        dnsChannel = DatagramChannel.open();
        dnsChannel.socket().connect(dnsServer);
        dnsChannel.configureBlocking(false);
        dnsKey = dnsChannel.register(selector, 0);
        Proxy.getInstance().putNewChannel(dnsChannel, this);
        requestQueue = new ConcurrentLinkedDeque<>();
        responseQueue = new ConcurrentHashMap<>();
        notCompletedRequests = new ConcurrentHashMap<>();
        log.info("DNS resolver started. Host : " + host + ". Port : " + port + ". DNS Server : "
                + dnsServer.toString());
    }

    public void addNewRequest(ClientHandler clientHandler, String address) {
        requestQueue.add(new DnsRequest(clientHandler, address));
        dnsKey.interestOps(dnsKey.interestOps() | SelectionKey.OP_WRITE);
        log.info("New DNS request : " + address);
    }

    public boolean isWaitingForResponse() { return !notCompletedRequests.isEmpty(); }

    @Override
    public void handleKey() {
        if (dnsKey.isReadable()) {
            readDnsMessage();
        } else
        if (dnsKey.isWritable()) {
            writeDnsMessage();
        }
    }

    @Override
    public void handleLostDatagram() {
        log.info("Dns datagram was lost");
        readDnsMessage();
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
            Name name = Name.fromString(request.address, Name.root);
            responseQueue.put(name, request.clientHandler);
            notCompletedRequests.put(name, new DnsRequestAttempt(1, request));
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
                for (Map.Entry<Name, DnsRequestAttempt> request: notCompletedRequests.entrySet()) {
                    if (request.getValue().getAttemptsNumber() == 3) {
                        log.info("Response for " + request.getKey() + " wasn't received after 3 attempts");
                        ClientHandler clientHandler = responseQueue.get(request.getKey());
                        clientHandler.setServerAddress(null);
                        notCompletedRequests.remove(request.getKey());
                        return;
                    }
                    log.info("Resend request for " + request.getKey());

                    requestQueue.add(request.getValue().getRequest());
                    notCompletedRequests.replace(request.getKey(),
                            new DnsRequestAttempt(request.getValue().getAttemptsNumber() + 1,
                                    request.getValue().getRequest()));
                }
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
            responseQueue.remove(hostName);
            for (Record answer : answers) {
                if (answer.getType() == Type.A) {
                    InetAddress inetAddress = ((ARecord) answer).getAddress();
                    clientHandler.setServerAddress(inetAddress);
                    break;
                }
            }
            clientHandler.setServerAddress(null);
            notCompletedRequests.remove(hostName);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
}