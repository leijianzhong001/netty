package io.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.util.Scanner;

public class Client {
    public static void main(String[] args) throws InterruptedException {
        // 客户端线程池
        NioEventLoopGroup eventExecutors = new NioEventLoopGroup();

        try {
            // 创建客户端启动对象
            // 注意客户但启动对象使用的是 Bootstrap 而不是 ServerBootstrap
            Bootstrap bootstrap = new Bootstrap();

            // 设置客户端启动参数
            bootstrap.group(eventExecutors)
                    .channel(NioSocketChannel.class) // 设置客户端通道的实现(反射)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new StringEncoder());
                            ch.pipeline().addLast(new StringDecoder());
                            ch.pipeline().addLast(new ClientHandler());
                        }
                    });

            System.out.println("Netty client is ok...");

            /*
             * 下面这两步都涉及到Netty的异步模型
             */
            // 启动客户端去连接服务器
            ChannelFuture channelFuture = bootstrap.connect("127.0.0.1", 6668).sync();
            Channel channel = channelFuture.channel();
            // 客户端需要输入信息，创建一个扫描器
            Scanner scanner = new Scanner(System.in);
            while(scanner.hasNextLine()) {
                String msg = scanner.nextLine();
                // 通过channel发送到服务器
                channel.writeAndFlush(msg + "\r\n");
            }
        }finally {
            eventExecutors.shutdownGracefully();
        }

    }
}
