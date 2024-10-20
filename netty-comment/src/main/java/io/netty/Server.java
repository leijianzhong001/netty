package io.netty;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;


public class Server {
    public static void main(String[] args) {

        // bossGroup 一般只需要一个，所以指定一下
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        // workerGroup 指定8个
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        // `ServerBootstrap`是服务端程序的引导类，其机制是**通过将一系列的参数组合出足够的信息，然后基于这些信息创建一个监听指定端口的应用程序**。
        ServerBootstrap bootstrap = new ServerBootstrap();

        // 在`ServerBootstrap`引导类的指定线程组的`group`方法中，会将bossGroup赋给自己的`group`成员变量，将`workerGroup`赋给自己的`childGroup`变量。
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class) // 本例中指定的通道实现是 `NioServerSocketChannel`， 即之后一旦有请求到达服务器，**会使用 `NioServerSocketChannel` 类型的`accept()`来创建一个 `NioSocketChannel`进行数据读写。**
                .option(ChannelOption.SO_BACKLOG, 128) // 内部使用一个叫`options`的成员变量来保存 设置的所有参数， 其类型是一个map，这里其实对应的是一个map.put操作
                .childOption(ChannelOption.SO_KEEPALIVE, true) // `childOption`的基本原理和上面的一致，只不过换成了`childOptions` 成员变量
                // .childOption(ChannelOption.ALLOCATOR, new UnpooledByteBufAllocator(false)) // 使用非池化的ByteBuf实现
                // .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT) // 使用池化的ByteBuf实现，默认实现
                .handler(new LoggingHandler(LogLevel.INFO)) // `handler`方法主要是给我们创建的 `ServerSocketChannel` 对象添加一些必要的业务处理，事实上这个地方和下面的`childHandler` 方法一样，可以通过两种方式添加业务处理器。一种是直接添加一个`ChannelHandler` 对象。
                .childHandler(new ChannelInitializer<SocketChannel>() { // 另一种方式是通过`ChannelInitializer` 一起添加多个业务处理器。之所以可以这样是因为`ChannelInitializer` 也同样继承了`ChannelInboundHandlerAdapter`,它同时也是一个`ChannelHandler` 对象。
                    // 给pipeline设置处理器
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        System.out.println("客户socket Channel hashcode = " + ch.hashCode());
                        // 服务端使用自定义的编解码器来发送和接收消息
                        ch.pipeline().addLast(new StringEncoder());
                        ch.pipeline().addLast(new StringDecoder());
                        ch.pipeline().addLast(new ServerHandler());
                    }
                }); // 给我们的 workerGroup 的 EventLoop 对应的管道设置处理器

        System.out.println("server is ready...");

        try {
            // 绑定一个端口并进行同步，生成了一个 ChannelFuture 对象
            // 启动服务器并绑定端口
            // 这里返回的这个 ChannelFuture实际上是一个 DefaultChannelPromise， 它会在 NioServerSocketChannel 被成功绑定到端口上之后被设置为成功
            ChannelFuture cf = bootstrap.bind(6668).sync();
            cf.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        System.out.println("监听服务器 6668 端口成功");
                    } else {
                        System.out.println("监听服务器 6668 端口失败");
                    }
                }
            });
            // 主线程阻塞在对closeFuture的同步操作中
            // 这里的closeFuture也是一个 DefaultChannelPromise 对象，其sync()方法最终其实调用的是Object.wait()方法。
            cf.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            // 发生异常时关闭线程池
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}