package client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class Client {
    public static void main(String[] args) throws IOException {
        String proxyHost = "localhost";
        int proxyPort = 5000;

        // Создаем сокет и подключаемся к прокси
        Socket proxySocket = new Socket(proxyHost, proxyPort);

        // Отправляем сообщение о начале проксирования (версия SOCKS5, один метод - без аутентификации)
        OutputStream proxyOut = proxySocket.getOutputStream();
        proxyOut.write(new byte[]{ 0x05, 0x01, 0x00 });

        // Читаем ответ от прокси
        InputStream proxyIn = proxySocket.getInputStream();
        byte[] response = new byte[2];
        proxyIn.read(response);
        System.out.println(response[0] + " " + response[1]);
        if (response[0] != 0x05 || response[1] != 0x00) {
            throw new IOException("Прокси не поддерживает требуемый метод");
        }

        // Резолвим ipv4 адрес хоста example.com
//        byte[] hostAddress = {(byte) 93, (byte) 184, (byte) 216, (byte) 34}; // IPv4 адрес example.com
//
//        // Отправляем запрос на подключение к example.com на порт 80
//        byte[] request = new byte[]{
//                0x05, // Версия SOCKS5
//                0x01, // Команда - установка TCP-соединения
//                0x00, // Зарезервировано
//                0x01, // Тип адреса - IPv4
//                hostAddress[0], hostAddress[1], hostAddress[2], hostAddress[3], // Адрес example.com в виде байтов
//                (byte) (80 >> 8), (byte) 80 // Порт (80) в виде байтов
//        };

//        String host = "lib.ru";
        //String host = "example.com";
        String host = "speedtest.tele2.net";
        byte[] request = new byte[]{
                0x05, // Версия SOCKS5
                0x01, // Команда - установка TCP-соединения
                0x00, // Зарезервировано
                0x03,
                (byte) host.length(), // Длина адреса
                // Адрес example.com в виде байтов
                //'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'c', 'o', 'm',
                //'l', 'i', 'b', '.', 'r', 'u',
                's', 'p', 'e', 'e', 'd', 't', 'e', 's', 't', '.', 't', 'e', 'l', 'e', '2', '.', 'n', 'e', 't',
                (byte) (80 >> 8), (byte) 80 // Порт (80) в виде байтов
        };
        proxyOut.write(request);

        // Читаем ответ от прокси
        byte[] response2 = new byte[18];
        int readBytes = proxyIn.read(response2);

        // Печатаем ответ от прокси
        for(int i = 0; i < readBytes; ++i) {
            System.out.println(response2[i]);
        }
//        int port = ((response2[8] & 0xFF) << 8) + (response2[9] & 0xFF);
//        System.out.println("port: " + port);

        // Отправляем HTTP-запрос на example.com
        //String httpRequest = "GET / HTTP/1.0\r\nHost: example.com\r\n\r\n";
        String httpRequest = "GET /10MB.zip HTTP/1.0\r\nHost: speedtest.tele2.net\r\n\r\n";
        proxyOut.write(httpRequest.getBytes());

        System.out.println("Request was sent");

        // Читаем ответ от прокси
        byte[] response3 = new byte[1024];
        System.out.println("reading...");
        while (true) {
            int bytesRead = proxyIn.read(response3);
            if (bytesRead == -1) {
//                System.out.println("return -1");
//                continue;
                break;
            }
            // Печатаем ответ от прокси
            System.out.println(new String(response3, 0, bytesRead));
        }

        // Закрываем соединение с прокси
        //proxySocket.close();//
    }
}
