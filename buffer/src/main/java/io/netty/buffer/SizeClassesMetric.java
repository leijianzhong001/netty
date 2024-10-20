/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer;

/**
 * Expose metrics for an SizeClasses.
 *  Netty 会通过 size 找到索引值 index（size2SizeIdx），也可以通过 index 找到对应的 size（sizeIdx2size）。
 */
public interface SizeClassesMetric {

    /**
     * Computes size from lookup table according to sizeIdx.
     * 根据数组索引值获取size大小（根据索引值直接从sizeIdx2sizeTab[]数组中读取即可）
     * @return size
     */
    int sizeIdx2size(int sizeIdx);

    /**
     * Computes size according to sizeIdx.
     * 根据数组索引值计算size大小
     * @return size
     */
    int sizeIdx2sizeCompute(int sizeIdx);

    /**
     * Computes size from lookup table according to pageIdx.
     * 根据页索引值获取对应的size
     * @return size which is multiples of pageSize.
     */
    long pageIdx2size(int pageIdx);

    /**
     * Computes size according to pageIdx.
     * 根据页索引值计算对应的size
     * @return size which is multiples of pageSize
     */
    long pageIdx2sizeCompute(int pageIdx);

    /**
     * Normalizes request size up to the nearest size class.
     * 将请求的大小规格化为最接近的值，返回该值对应的size索引
     * 比如申请大小为14，则返回数组索引值为0，后续通过该数组索引就能得到对应的size的值
     * @param size request size
     *
     * @return sizeIdx of the size class
     */
    int size2SizeIdx(int size);

    /**
     * Normalizes request size up to the nearest pageSize class.
     * 对页的数量进行规格化计算，获取最接近的pages容量大小的数组索引。
     * 比如参数pages=5， 表示需要分配 5*pages大小的内存块，因此会从SizeClass数组中查找最接近 5*pages 大小的规格 对应的索引值
     * @param pages multiples of pageSizes
     *
     * @return pageIdx of the pageSize class
     */
    int pages2pageIdx(int pages);

    /**
     * Normalizes request size down to the nearest pageSize class.
     * 对页的数量进行规格化计算，向下取整获取最接近的pages容量大小的数组索引
     * @param pages multiples of pageSizes
     *
     * @return pageIdx of the pageSize class
     */
    int pages2pageIdxFloor(int pages);

    /**
     * Normalizes usable size that would result from allocating an object with the
     * specified size and alignment.
     * 对size进行规格化，在内存对齐时使用。
     * @param size request size
     *
     * @return normalized size
     */
    int normalizeSize(int size);
}
