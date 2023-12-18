package proxy;

public class Main {
    public static void main(String ... args) {
        if (args.length != 1) {
            System.err.println("usage: ./socks5-proxy PORT");
            return;
        }

        int port = Integer.parseInt(args[0]);
        String host = "localhost";

        System.out.println("Start proxy...");
        Proxy proxy = new Proxy(host, port);
        proxy.run();
    }
}
