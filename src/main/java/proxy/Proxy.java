package proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Optional;

import exceptions.Socks5ProtocolException;
import org.xbill.DNS.Record;
import org.xbill.DNS.*;


public class Proxy implements Runnable {
    private static final int BUFFER_SIZE = 4096;
    private final String ip;
    private final int port;

    public Proxy(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }
    @Override
    public void run() {
        try {
            Selector selector = Selector.open();
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);

            serverChannel.socket().bind(new InetSocketAddress(ip, port));
            serverChannel.register(selector, serverChannel.validOps());

            while (true) {
                selector.select();
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (key.isValid()) {
                        try {
                            if (key.isAcceptable()) {
                                accept(key);
                            } else if (key.isConnectable()) {
                                connect(key);
                            } else if (key.isReadable()) {
                                read(key);
                            } else if (key.isWritable()) {
                                write(key);
                            }
                        } catch (Socks5ProtocolException e) {
                            e.printStackTrace();
                            sendError(key);
                        } catch (Exception e) {
                            e.printStackTrace();
                            close(key);
                        }
                    }
                }
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void accept(SelectionKey key) throws IOException {
        System.out.println("Client was accepted");
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel channel = serverSocketChannel.accept();
        channel.configureBlocking(false);
        channel.register(key.selector(), SelectionKey.OP_READ);
    }

    private void connect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Attachment attachment = (Attachment) key.attachment();
        channel.finishConnect();

        attachment.inputBuffer = ByteBuffer.allocate(BUFFER_SIZE);

        Attachment outAttachment = (Attachment) attachment.connectedKey.attachment();
        attachment.inputBuffer.put(new byte[]{ 5, 0, 0, 1 });
        attachment.inputBuffer.put(outAttachment.ip.getAddress());
        attachment.inputBuffer.putShort((short) outAttachment.port);
        attachment.inputBuffer.flip();


        attachment.outputBuffer = outAttachment.inputBuffer;
        outAttachment.outputBuffer = attachment.inputBuffer;

        attachment.connectedKey.interestOps(SelectionKey.OP_WRITE);
        outAttachment.state = State.CONNECTING;
        key.interestOps(0);
    }

    private void read(SelectionKey key) throws IOException, Socks5ProtocolException {
        System.out.println("reading...");
        Attachment attachment = (Attachment) key.attachment();
        if (attachment == null) {
            System.out.println("Start of greeting message");
            attachment = new Attachment();
            attachment.state = State.GREETING;
            attachment.inputBuffer = ByteBuffer.allocate(BUFFER_SIZE);
            key.attach(attachment);
        }

        if (attachment.state == State.DNS) {
            DatagramChannel channel = (DatagramChannel) key.channel();
            if (channel.read(attachment.inputBuffer) <= 0) {
                close(key);
                throw new IOException("Hostname wasn't resolved");
            } else {
                Message message = new Message(attachment.inputBuffer.array());
                Optional<Record> recordOptional = message.getSection(Section.ANSWER).stream().findAny();
                if (recordOptional.isPresent()) {
                    InetAddress ipAddress = InetAddress.getByName(recordOptional.get().rdataToString());
                    System.out.println("Resolved: " + recordOptional.get().rdataToString());

                    registerServer(ipAddress, attachment.port, attachment.connectedKey);
                    key.interestOps(0);
                    closeEnd(key);
                } else {
                    System.out.println(message);
                    close(key);
                    throw new RuntimeException("Host can't be resolved");
                }
            }
        } else {
            SocketChannel channel = (SocketChannel) key.channel();

            if (channel.read(attachment.inputBuffer) <= 0) {
                System.out.println("All data was read, closing connection");
                close(key);
            }
            else if (attachment.state == State.GREETING) {
                System.out.println("Reading greeting message");
                Handshake.greeting(key);
            } else if (attachment.state == State.CONNECTING) {
                System.out.println("Reading connecting message");
                Handshake.connecting(key);
                //((proxy.Attachment) key.attachment()).state = proxy.State.REQUEST;
            } else if (attachment.state == State.REQUEST) {
                System.out.println("Read request");
                attachment.inputBuffer.flip();
                key.interestOps(key.interestOps() ^ SelectionKey.OP_READ);
                ((Attachment) key.attachment()).state = State.DATA;
                ((Attachment) ((Attachment) key.attachment()).connectedKey.attachment()).state = State.REQUEST;
                attachment.connectedKey.interestOpsOr(SelectionKey.OP_WRITE);
            }
            else {
                System.out.println("Read data");
                attachment.inputBuffer.flip();
                key.interestOps(key.interestOps() ^ SelectionKey.OP_READ);
                attachment.connectedKey.interestOpsOr(SelectionKey.OP_WRITE);
            }
        }
    }

    private void write(SelectionKey key) throws IOException {
        Attachment attachment = (Attachment) key.attachment();
        if (attachment.state == State.DNS) {
            DatagramChannel channel = (DatagramChannel) key.channel();

            System.out.println("Sending DNS request...");
            if (channel.write(attachment.outputBuffer) == -1) {
                System.out.println("write return -1");
                close(key);
            } else if (attachment.outputBuffer.remaining() == 0) {
                System.out.println("DNS request was sent");
                attachment.outputBuffer.clear();
                //attachment.state = proxy.State.DNS;
                key.interestOpsOr(SelectionKey.OP_READ);
                key.interestOpsAnd(~SelectionKey.OP_WRITE);
            }
        } else {
            SocketChannel channel = (SocketChannel) key.channel();

            if (channel.write(attachment.outputBuffer) == -1) {
                System.out.println("write return -1");
                close(key);
            } else if (attachment.outputBuffer.remaining() == 0) {
                if (attachment.state == State.GREETING) {
                    System.out.println("Greeting reply was sent");
                    attachment.outputBuffer.clear();
                    key.interestOps(SelectionKey.OP_READ);
                    attachment.state = State.CONNECTING;
                } else if (attachment.state == State.CONNECTING) {
                    System.out.println("Connecting reply was sent");
                    attachment.outputBuffer.clear();
                    key.interestOps(SelectionKey.OP_READ);
                    attachment.state = State.REQUEST;
                } else if (attachment.state == State.ERROR) {
                    System.out.println("Error reply was sent");
                    close(key);
                } else if (attachment.state == State.REQUEST) {
                    System.out.println("Writing request...");

                    attachment.outputBuffer.clear();
                    key.interestOps(key.interestOps() ^ SelectionKey.OP_WRITE);
                    //attachment.connectedKey.interestOpsOr(SelectionKey.OP_READ);
                    key.interestOpsOr(SelectionKey.OP_READ); //перестаем писать серверу запрос и начинаем читать от него ответ
                    ((Attachment) key.attachment()).state = State.DATA;
                } else {
                    System.out.println("Writing data...");

                    attachment.outputBuffer.clear();

                    if (attachment.connectedKey == null) {
                        System.out.println("Write: connected key is null. Close connection...");
                        close(key);
                        return;
                    }

                    key.interestOps(key.interestOps() ^ SelectionKey.OP_WRITE);
                    attachment.connectedKey.interestOpsOr(SelectionKey.OP_READ);
                }


            }
        }
    }

    private void close(SelectionKey key) throws IOException {
        System.out.println("closing...");
        key.cancel();
        key.channel().close();
        Attachment attachment = (Attachment) key.attachment();
        if (attachment == null) return;
        SelectionKey anotherKey = attachment.connectedKey;
        if (anotherKey != null) {
            Attachment anotherAttachment = (Attachment) anotherKey.attachment();
            anotherAttachment.connectedKey = null;
            if ((anotherKey.interestOps() & SelectionKey.OP_WRITE) == 0) {
                ((Attachment) anotherKey.attachment()).inputBuffer.flip();
            }
            anotherKey.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private static void closeEnd(SelectionKey key) throws IOException {
        System.out.println("close end " + key);
        key.cancel();
        key.channel().close();
    }


    public static void registerServer(InetAddress connectAddress, int connectPort, SelectionKey clientKey) throws IOException {
        System.out.println("Start connecting to server " + connectAddress + ":" + connectPort);

        SocketChannel serverSocket = SocketChannel.open();
        serverSocket.configureBlocking(false);
        serverSocket.connect(new InetSocketAddress(connectAddress, connectPort));
        SelectionKey serverKey = serverSocket.register(clientKey.selector(), SelectionKey.OP_CONNECT);


        Attachment clientAttachment = (Attachment) clientKey.attachment();
        clientAttachment.connectedKey = serverKey;
        clientAttachment.ip = connectAddress;
        //clientAttachment.port = connectPort;

        Attachment serverAttachment = new Attachment();
        serverAttachment.connectedKey = clientKey;
        serverKey.attach(serverAttachment);

        clientAttachment.inputBuffer.clear();
    }

    public static void createHostResolveRequest(String host, int port, SelectionKey clientKey) throws IOException {
        DatagramChannel resolvingChannel = DatagramChannel.open();
        resolvingChannel.configureBlocking(false);
        resolvingChannel.connect(ResolverConfig.getCurrentConfig().server());

        SelectionKey dnsServerKey = resolvingChannel.register(clientKey.selector(), SelectionKey.OP_WRITE);

        Attachment attachment = new Attachment();
        attachment.state = State.DNS;
        attachment.port = port;
        attachment.connectedKey = clientKey;
        attachment.inputBuffer = ByteBuffer.allocate(BUFFER_SIZE);

        System.out.println("host: " + host + " " + port);

        Record record = Record.newRecord(Name.fromString(host + '.').canonicalize(), org.xbill.DNS.Type.A, DClass.IN);
        Message queryMessage = new Message();
        queryMessage.addRecord(record, Section.QUESTION);

        Header header = queryMessage.getHeader();
        header.setFlag(Flags.AD);
        header.setFlag(Flags.RD);

        attachment.inputBuffer.put(queryMessage.toWire());
        attachment.inputBuffer.flip();
        attachment.outputBuffer = attachment.inputBuffer;

        dnsServerKey.attach(attachment);
        System.out.println("DNS request was created");
    }

    private void sendError(SelectionKey key) {
        Attachment attachment = (Attachment) key.attachment();
        attachment.outputBuffer = attachment.inputBuffer;
        attachment.outputBuffer.clear();
        attachment.outputBuffer.put(ProtocolParams.ERROR_REPLY).flip();
        attachment.state = State.ERROR;

        key.interestOps(SelectionKey.OP_WRITE);
    }
}
