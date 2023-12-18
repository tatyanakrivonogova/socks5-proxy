package proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Proxy {
    private static final Logger log = LoggerFactory.getLogger(Proxy.class);

    private static Proxy instance;

    private Selector selector;
    private Map<SelectableChannel, Handler> channelHandlers;

    private Proxy() {}
    public static Proxy getInstance() {
        if (instance == null) {
            instance = new Proxy();
        }
        return instance;
    }

    public void start(String host, int proxyPort, int dnsPort) {

        channelHandlers = new ConcurrentHashMap<>();

        try {
            selector = SelectorProvider.provider().openSelector();
            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.socket().bind(new InetSocketAddress(host, proxyPort));
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

            DnsResolver.getInstance().start(host, dnsPort, selector);
        }
        catch (IOException e) {
            log.error(e.toString());
            System.exit(1);
        }

        log.info("Proxy server started. Host : " + host + ". Port : " + proxyPort);
        try {
            while (selector.select() > -1) {
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (key.isValid()) {
                        if (key.isAcceptable()) {
                            accept(key);
                        }
                        else {
                            channelHandlers.get(key.channel()).handleKey();
                        }
                    }
                }
            }
        }
        catch (IOException e) {
            log.error(e.toString());
        }
    }

    private void accept(SelectionKey key) {
        try {
            ClientHandler clientHandler = new ClientHandler(key);
            putNewChannel(clientHandler.getClientChannel(), clientHandler);
            log.info("New client accepted.");
        }
        catch (IOException e) {
            log.error(e.toString());
        }
    }

    public void putNewChannel(SelectableChannel channel, Handler handler) {
        channelHandlers.put(channel, handler);
    }

    public void removeChannelFromMap(SelectableChannel channel) {
        channelHandlers.remove(channel);
    }
}