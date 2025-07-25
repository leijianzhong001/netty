package io.netty.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;

public class TestHttpServer {
    public static void main(String[] args) {
        // bossGroup 一般只需要一个，所以指定一下
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        // workerGroup 指定8个
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();

            serverBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class) // 设置服务端Channel的实现
                    .childHandler(new ChannelInitializer<SocketChannel>(){
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            // 如果是服务端，则需要请求解码器和响应编码器
                            pipeline.addLast(new HttpRequestDecoder());
                            pipeline.addLast(new HttpResponseEncoder());
                            pipeline.addLast(new TestHttpServerHandler());
                        }
                    }); // 设置处理器

            ChannelFuture cf = serverBootstrap.bind(8080).sync();
            cf.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
