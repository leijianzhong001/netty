package io.netty;

public class DefaultJvmHeapSize {
    public static void main(String[] args) {
        // 返回Java虚拟机中的堆内存的初始大小
        long initialMemory = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        // java虚拟机试图使用的最大堆内存大小
        long maxMemory = Runtime.getRuntime().maxMemory() / 1024 / 1024;

        // 默认堆内存的初始值为当前可用物理内存的1/64
        System.out.println("初始内存大小-Xms：" + initialMemory);
        // 默认堆内存的最大值为当前可用物理内存的1/4
        System.out.println("最大内存-Xmx：" + maxMemory);

        System.out.println("系统内存大小为：" + initialMemory * 64.0 / 1024 + "G");
        System.out.println("jvm可使用的最大内存为：" + maxMemory * 4.0 / 1024 + "G");
    }
}
