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

import static io.netty.buffer.PoolThreadCache.*;

/**
 * SizeClasses requires {@code pageShifts} to be defined prior to inclusion,
 * and it in turn defines:
 * <p>
 *   LOG2_SIZE_CLASS_GROUP: Log of size class count for each size doubling.
 *   LOG2_MAX_LOOKUP_SIZE: Log of max size class in the lookup table.
 *   sizeClasses: Complete table of [index, log2Group, log2Delta, nDelta, isMultiPageSize,
 *                 isSubPage, log2DeltaLookup] tuples.
 *     index: Size class index.
 *     log2Group: Log of group base size (no deltas added).
 *     log2Delta: Log of delta to previous size class.
 *     nDelta: Delta multiplier.
 *     isMultiPageSize: 'yes' if a multiple of the page size, 'no' otherwise.
 *     isSubPage: 'yes' if a subpage size class, 'no' otherwise.
 *     log2DeltaLookup: Same as log2Delta if a lookup table size class, 'no'
 *                      otherwise.
 * <p>
 *   nSubpages: Number of subpages size classes.
 *   nSizes: Number of size classes.
 *   nPSizes: Number of size classes that are multiples of pageSize.
 *
 *   smallMaxSizeIdx: Maximum small size class index.
 *
 *   lookupMaxClass: Maximum size class included in lookup table.
 *   log2NormalMinClass: Log of minimum normal size class.
 * <p>
 *   The first size class and spacing are 1 << LOG2_QUANTUM.
 *   Each group has 1 << LOG2_SIZE_CLASS_GROUP of size classes.
 *
 *   size = 1 << log2Group + nDelta * (1 << log2Delta)
 *
 *   The first size class has an unusual encoding, because the size has to be
 *   split between group and delta*nDelta.
 *
 *   If pageShift = 13, sizeClasses looks like this:
 *
 *   (index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup)
 * <p>
 *   ( 0,     4,        4,         0,       no,             yes,        4)
 *   ( 1,     4,        4,         1,       no,             yes,        4)
 *   ( 2,     4,        4,         2,       no,             yes,        4)
 *   ( 3,     4,        4,         3,       no,             yes,        4)
 * <p>
 *   ( 4,     6,        4,         1,       no,             yes,        4)
 *   ( 5,     6,        4,         2,       no,             yes,        4)
 *   ( 6,     6,        4,         3,       no,             yes,        4)
 *   ( 7,     6,        4,         4,       no,             yes,        4)
 * <p>
 *   ( 8,     7,        5,         1,       no,             yes,        5)
 *   ( 9,     7,        5,         2,       no,             yes,        5)
 *   ( 10,    7,        5,         3,       no,             yes,        5)
 *   ( 11,    7,        5,         4,       no,             yes,        5)
 *   ...
 *   ...
 *   ( 72,    23,       21,        1,       yes,            no,        no)
 *   ( 73,    23,       21,        2,       yes,            no,        no)
 *   ( 74,    23,       21,        3,       yes,            no,        no)
 *   ( 75,    23,       21,        4,       yes,            no,        no)
 * <p>
 *   ( 76,    24,       22,        1,       yes,            no,        no)
 *
 * 这是一个极其重要类，它在内部维护一个二维数组，这个数组存储与内存规格有关的详细信息。我们先看看这张表长什么样子的:
 * | index | log2Group | log2Delta | nDelta | isMultiPageSize | isSubPage | log2DeltaLookup |
 * |   0   |     4     |     4     |   0    |        0        |     1     |        4        |
 * |   1   |     4     |     4     |   1    |        0        |     1     |        4        |
 * |   2   |     4     |     4     |   2    |        0        |     1     |        4        |
 * |   3   |     4     |     4     |   3    |        0        |     1     |        4        |
 * |   4   |     6     |     4     |   1    |        0        |     1     |        4        |
 * |   5   |     6     |     4     |   2    |        0        |     1     |        4        |
 * |   6   |     6     |     4     |   3    |        0        |     1     |        4        |
 * |   7   |     6     |     4     |   4    |        0        |     1     |        4        |
 * |   8   |     7     |     5     |   1    |        0        |     1     |        5        |
 * |   9   |     7     |     5     |   2    |        0        |     1     |        5        |
 * |  10   |     7     |     5     |   3    |        0        |     1     |        5        |
 * |  11   |     7     |     5     |   4    |        0        |     1     |        5        |
 * |  12   |     8     |     6     |   1    |        0        |     1     |        6        |
 * |  13   |     8     |     6     |   2    |        0        |     1     |        6        |
 * |  14   |     8     |     6     |   3    |        0        |     1     |        6        |
 * |  15   |     8     |     6     |   4    |        0        |     1     |        6        |
 * |  16   |     9     |     7     |   1    |        0        |     1     |        7        |
 * |  17   |     9     |     7     |   2    |        0        |     1     |        7        |
 * |  18   |     9     |     7     |   3    |        0        |     1     |        7        |
 * |  19   |     9     |     7     |   4    |        0        |     1     |        7        |
 * |  20   |    10     |     8     |   1    |        0        |     1     |        8        |
 * |  21   |    10     |     8     |   2    |        0        |     1     |        8        |
 * |  22   |    10     |     8     |   3    |        0        |     1     |        8        |
 * |  23   |    10     |     8     |   4    |        0        |     1     |        8        |
 * |  24   |    11     |     9     |   1    |        0        |     1     |        9        |
 * |  25   |    11     |     9     |   2    |        0        |     1     |        9        |
 * |  26   |    11     |     9     |   3    |        0        |     1     |        9        |
 * |  27   |    11     |     9     |   4    |        0        |     1     |        9        |
 * |  28   |    12     |    10     |   1    |        0        |     1     |        0        |
 * |  29   |    12     |    10     |   2    |        0        |     1     |        0        |
 * |  30   |    12     |    10     |   3    |        0        |     1     |        0        |
 * |  31   |    12     |    10     |   4    |        1        |     1     |        0        |
 * |  32   |    13     |    11     |   1    |        0        |     1     |        0        |
 * |  33   |    13     |    11     |   2    |        0        |     1     |        0        |
 * |  34   |    13     |    11     |   3    |        0        |     1     |        0        |
 * |  35   |    13     |    11     |   4    |        1        |     1     |        0        |
 * |  36   |    14     |    12     |   1    |        0        |     1     |        0        |
 * |  37   |    14     |    12     |   2    |        1        |     1     |        0        |
 * |  38   |    14     |    12     |   3    |        0        |     1     |        0        |
 * |  39   |    14     |    12     |   4    |        1        |     0     |        0        |
 * |  40   |    15     |    13     |   1    |        1        |     0     |        0        |
 * |  41   |    15     |    13     |   2    |        1        |     0     |        0        |
 * |  42   |    15     |    13     |   3    |        1        |     0     |        0        |
 * |  43   |    15     |    13     |   4    |        1        |     0     |        0        |
 * |  44   |    16     |    14     |   1    |        1        |     0     |        0        |
 * |  45   |    16     |    14     |   2    |        1        |     0     |        0        |
 * |  46   |    16     |    14     |   3    |        1        |     0     |        0        |
 * |  47   |    16     |    14     |   4    |        1        |     0     |        0        |
 * |  48   |    17     |    15     |   1    |        1        |     0     |        0        |
 * |  49   |    17     |    15     |   2    |        1        |     0     |        0        |
 * |  50   |    17     |    15     |   3    |        1        |     0     |        0        |
 * |  51   |    17     |    15     |   4    |        1        |     0     |        0        |
 * |  52   |    18     |    16     |   1    |        1        |     0     |        0        |
 * |  53   |    18     |    16     |   2    |        1        |     0     |        0        |
 * |  54   |    18     |    16     |   3    |        1        |     0     |        0        |
 * |  55   |    18     |    16     |   4    |        1        |     0     |        0        |
 * |  56   |    19     |    17     |   1    |        1        |     0     |        0        |
 * |  57   |    19     |    17     |   2    |        1        |     0     |        0        |
 * |  58   |    19     |    17     |   3    |        1        |     0     |        0        |
 * |  59   |    19     |    17     |   4    |        1        |     0     |        0        |
 * |  60   |    20     |    18     |   1    |        1        |     0     |        0        |
 * |  61   |    20     |    18     |   2    |        1        |     0     |        0        |
 * |  62   |    20     |    18     |   3    |        1        |     0     |        0        |
 * |  63   |    20     |    18     |   4    |        1        |     0     |        0        |
 * |  64   |    21     |    19     |   1    |        1        |     0     |        0        |
 * |  65   |    21     |    19     |   2    |        1        |     0     |        0        |
 * |  66   |    21     |    19     |   3    |        1        |     0     |        0        |
 * |  67   |    21     |    19     |   4    |        1        |     0     |        0        |
 * |  68   |    22     |    20     |   1    |        1        |     0     |        0        |
 * |  69   |    22     |    20     |   2    |        1        |     0     |        0        |
 * |  70   |    22     |    20     |   3    |        1        |     0     |        0        |
 * |  71   |    22     |    20     |   4    |        1        |     0     |        0        |
 * |  72   |    23     |    21     |   1    |        1        |     0     |        0        |
 * |  73   |    23     |    21     |   2    |        1        |     0     |        0        |
 * |  74   |    23     |    21     |   3    |        1        |     0     |        0        |
 * |  75   |    23     |    21     |   4    |        1        |     0     |        0        |
 * 从上表中可知，数组长度 76。每一列表示的含义如下:
 *     index: 由 0 开始的自增序列号，表示每个 size 类型的索引。
 *     log2Group: 表示每个 size 它所对应的组。以每 4 行为一组，一共有 19 组。第 0 组比较特殊，它是单独初始化的。因此，我们应该从第 1 组开始，起始值为 6，每组的 log2Group 是在上一组的值 +1。
 *     log2Delta: 表示当前序号所对应的 size 和前一个序号所对应的 size 的差值的 log2 的值。比如 index=6 对应的 size = 112，index=7 对应的 size= 128，因此 index=7 的 log2Delta(7) = log2(128-112)=4。不知道你们有没有发现，其实 log2Delta=log2Group-2。
 *     nDelta: 表示组内增量的倍数。第 0 组也是比较特殊，nDelta 是从 0 开始 + 1。而其余组是从 1 开始 +1。
 *     isMultiPageSize: 表示当前 size 是否是 pageSize（默认值: 8192） 的整数倍。后续会把 isMultiPageSize=1 的行单独整理成一张表，你会发现有 40 个 isMultiPageSize=1 的行。
 *     isSubPage: 表示当前 size 是否为一个 subPage 类型，jemalloc4 会根据这个值采取不同的内存分配策略。
 *     log2DeltaLookup: 当 index<=27 时，其值和 log2Delta 相等，当index>27，其值为 0。但是在代码中没有看到具体用来做什么。
 * 有了上面的信息并不够，因为最想到得到的是 index 与 size 的对应关系。
 * 在 SizeClasses 表中，无论哪一行的 size 都是由 _size = (1 << log2Group) + nDelta * (1 << log2Delta)_ 公式计算得到。因此通过计算可得出每行的 size:
 *
 | index | log2Group | log2Delta | nDelta | isMultiPageSize | isSubPage | log2DeltaLookup |   size   | Unit: MB |
 | :---: | :-------: | :-------: | :----: | :-------------: | :-------: | :-------------: | :------: | :------: |
 |   0   |     4     |     4     |   0    |        0        |     1     |        4        |    16    |          |
 |   1   |     4     |     4     |   1    |        0        |     1     |        4        |    32    |          |
 |   2   |     4     |     4     |   2    |        0        |     1     |        4        |    48    |          |
 |   3   |     4     |     4     |   3    |        0        |     1     |        4        |    64    |          |
 |   4   |     6     |     4     |   1    |        0        |     1     |        4        |    80    |          |
 |   5   |     6     |     4     |   2    |        0        |     1     |        4        |    96    |          |
 |   6   |     6     |     4     |   3    |        0        |     1     |        4        |   112    |          |
 |   7   |     6     |     4     |   4    |        0        |     1     |        4        |   128    |          |
 |   8   |     7     |     5     |   1    |        0        |     1     |        5        |   160    |          |
 |   9   |     7     |     5     |   2    |        0        |     1     |        5        |   192    |          |
 |  10   |     7     |     5     |   3    |        0        |     1     |        5        |   224    |          |
 |  11   |     7     |     5     |   4    |        0        |     1     |        5        |   256    |          |
 |  12   |     8     |     6     |   1    |        0        |     1     |        6        |   320    |          |
 |  13   |     8     |     6     |   2    |        0        |     1     |        6        |   384    |          |
 |  14   |     8     |     6     |   3    |        0        |     1     |        6        |   448    |          |
 |  15   |     8     |     6     |   4    |        0        |     1     |        6        |   512    |          |
 |  16   |     9     |     7     |   1    |        0        |     1     |        7        |   640    |          |
 |  17   |     9     |     7     |   2    |        0        |     1     |        7        |   768    |          |
 |  18   |     9     |     7     |   3    |        0        |     1     |        7        |   896    |          |
 |  19   |     9     |     7     |   4    |        0        |     1     |        7        |   1024   |          |
 |  20   |    10     |     8     |   1    |        0        |     1     |        8        |   1280   |          |
 |  21   |    10     |     8     |   2    |        0        |     1     |        8        |   1536   |          |
 |  22   |    10     |     8     |   3    |        0        |     1     |        8        |   1792   |          |
 |  23   |    10     |     8     |   4    |        0        |     1     |        8        |   2048   |          |
 |  24   |    11     |     9     |   1    |        0        |     1     |        9        |   2560   |          |
 |  25   |    11     |     9     |   2    |        0        |     1     |        9        |   3072   |          |
 |  26   |    11     |     9     |   3    |        0        |     1     |        9        |   3584   |          |
 |  27   |    11     |     9     |   4    |        0        |     1     |        9        |   4096   |          |
 |  28   |    12     |    10     |   1    |        0        |     1     |        0        |   5120   |          |
 |  29   |    12     |    10     |   2    |        0        |     1     |        0        |   6144   |          |
 |  30   |    12     |    10     |   3    |        0        |     1     |        0        |   7168   |          |
 |  31   |    12     |    10     |   4    |        1        |     1     |        0        |   8192   |   8KB    |
 |  32   |    13     |    11     |   1    |        0        |     1     |        0        |  10240   |   10KB   |
 |  33   |    13     |    11     |   2    |        0        |     1     |        0        |  12288   |   12KB   |
 |  34   |    13     |    11     |   3    |        0        |     1     |        0        |  14336   |   14KB   |
 |  35   |    13     |    11     |   4    |        1        |     1     |        0        |  16384   |   16KB   |
 |  36   |    14     |    12     |   1    |        0        |     1     |        0        |  20480   |   20KB   |
 |  37   |    14     |    12     |   2    |        1        |     1     |        0        |  24576   |   24KB   |
 |  38   |    14     |    12     |   3    |        0        |     1     |        0        |  28672   |   28KB   |
 |  39   |    14     |    12     |   4    |        1        |     0     |        0        |  32768   |   32KB   |
 |  40   |    15     |    13     |   1    |        1        |     0     |        0        |  40960   |   40KB   |
 |  41   |    15     |    13     |   2    |        1        |     0     |        0        |  49152   |   48KB   |
 |  42   |    15     |    13     |   3    |        1        |     0     |        0        |  57344   |   56KB   |
 |  43   |    15     |    13     |   4    |        1        |     0     |        0        |  65536   |   64KB   |
 |  44   |    16     |    14     |   1    |        1        |     0     |        0        |  81920   |   80KB   |
 |  45   |    16     |    14     |   2    |        1        |     0     |        0        |  98304   |   96KB   |
 |  46   |    16     |    14     |   3    |        1        |     0     |        0        |  114688  |  112KB   |
 |  47   |    16     |    14     |   4    |        1        |     0     |        0        |  131072  |  128KB   |
 |  48   |    17     |    15     |   1    |        1        |     0     |        0        |  163840  |  160KB   |
 |  49   |    17     |    15     |   2    |        1        |     0     |        0        |  196608  |  192KB   |
 |  50   |    17     |    15     |   3    |        1        |     0     |        0        |  229376  |  224KB   |
 |  51   |    17     |    15     |   4    |        1        |     0     |        0        |  262144  |  256KB   |
 |  52   |    18     |    16     |   1    |        1        |     0     |        0        |  327680  |  320KB   |
 |  53   |    18     |    16     |   2    |        1        |     0     |        0        |  393216  |  384KB   |
 |  54   |    18     |    16     |   3    |        1        |     0     |        0        |  458752  |  448KB   |
 |  55   |    18     |    16     |   4    |        1        |     0     |        0        |  524288  |  512KB   |
 |  56   |    19     |    17     |   1    |        1        |     0     |        0        |  655360  |  640KB   |
 |  57   |    19     |    17     |   2    |        1        |     0     |        0        |  786432  |  768KB   |
 |  58   |    19     |    17     |   3    |        1        |     0     |        0        |  917504  |  896KB   |
 |  59   |    19     |    17     |   4    |        1        |     0     |        0        | 1048576  |  1.0MB   |
 |  60   |    20     |    18     |   1    |        1        |     0     |        0        | 1310720  |  1.25MB  |
 |  61   |    20     |    18     |   2    |        1        |     0     |        0        | 1572864  |  1.5MB   |
 |  62   |    20     |    18     |   3    |        1        |     0     |        0        | 1835008  |  1.75MB  |
 |  63   |    20     |    18     |   4    |        1        |     0     |        0        | 2097152  |   2MB    |
 |  64   |    21     |    19     |   1    |        1        |     0     |        0        | 2621440  |  2.5MB   |
 |  65   |    21     |    19     |   2    |        1        |     0     |        0        | 3145728  |   3MB    |
 |  66   |    21     |    19     |   3    |        1        |     0     |        0        | 3670016  |  3.5MB   |
 |  67   |    21     |    19     |   4    |        1        |     0     |        0        | 4194304  |   4MB    |
 |  68   |    22     |    20     |   1    |        1        |     0     |        0        | 5242880  |   5MB    |
 |  69   |    22     |    20     |   2    |        1        |     0     |        0        | 6291456  |   6MB    |
 |  70   |    22     |    20     |   3    |        1        |     0     |        0        | 7340032  |   7MB    |
 |  71   |    22     |    20     |   4    |        1        |     0     |        0        | 8388608  |   8MB    |
 |  72   |    23     |    21     |   1    |        1        |     0     |        0        | 10485760 |   10MB   |
 |  73   |    23     |    21     |   2    |        1        |     0     |        0        | 12582912 |   12MB   |
 |  74   |    23     |    21     |   3    |        1        |     0     |        0        | 14680064 |   14MB   |
 |  75   |    23     |    21     |   4    |        1        |     0     |        0        | 16777216 |   16MB   |
 * 从表中可以发现，不管对于哪种内存规格，它都有更细粒度的内存大小的划分。
 * 比如在 512Byte~8192Byte 范围内，现在可分为 512、640、768 等等，不再是 jemalloc3 只有 512、1024、2048 … 这种粒度比较大的规格值了。
 * 这就是 jemalloc4 最大的提升。
 *
 */
abstract class SizeClasses implements SizeClassesMetric {

    static final int LOG2_QUANTUM = 4;

    private static final int LOG2_SIZE_CLASS_GROUP = 2;
    private static final int LOG2_MAX_LOOKUP_SIZE = 12;

    private static final int INDEX_IDX = 0;
    private static final int LOG2GROUP_IDX = 1;
    private static final int LOG2DELTA_IDX = 2;
    private static final int NDELTA_IDX = 3;
    private static final int PAGESIZE_IDX = 4;
    private static final int SUBPAGE_IDX = 5;
    private static final int LOG2_DELTA_LOOKUP_IDX = 6;

    private static final byte no = 0, yes = 1;

    protected final int pageSize;
    protected final int pageShifts;
    protected final int chunkSize;
    protected final int directMemoryCacheAlignment;

    final int nSizes;
    final int nSubpages;
    final int nPSizes;
    final int lookupMaxSize;
    final int smallMaxSizeIdx;

    /**
     * 有很多同学对这个数组表示疑惑，这个数组可以用来做些什么? 这个数组用来加速 size<=lookupMaxSize(默认值: 4096) 的索引查找。
     * 也就是说，当我们需要通过 size 查找 SizeClasses 对应的数组索引时，如果此时 size<=lookupMaxSize 成立，
     * 那么经过计算得到相应 pageIdx2sizeTab 的索引值，然后获取存储在 pageIdx2sizeTab 的值就是对应 SizeClasses 的 index。
     * 那如何计算得到相应 pageIdx2sizeTab 的索引值呢? 是由 idx=(size-1)/16 求得。比如当 size=4096，
     * 由公式求得 idx=255，此时 pageIdx2sizeTab[255]=27，因此 size=4096 对应的 SizeClasses 索引值为 27。
     */
    private final int[] pageIdx2sizeTab;

    // lookup table for sizeIdx <= smallMaxSizeIdx
    /**
     * 这个表格存的则是索引和对应的size的对应表格
     */
    private final int[] sizeIdx2sizeTab;

    // lookup table used for size <= lookupMaxClass
    // spacing is 1 << LOG2_QUANTUM, so the size of array is lookupMaxClass >> LOG2_QUANTUM
    private final int[] size2idxTab;

    protected SizeClasses(int pageSize, int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
        int group = log2(chunkSize) - LOG2_QUANTUM - LOG2_SIZE_CLASS_GROUP + 1;

        //generate size classes
        //[index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
        short[][] sizeClasses = new short[group << LOG2_SIZE_CLASS_GROUP][7];

        int normalMaxSize = -1;
        int nSizes = 0;
        int size = 0;

        int log2Group = LOG2_QUANTUM;
        int log2Delta = LOG2_QUANTUM;
        int ndeltaLimit = 1 << LOG2_SIZE_CLASS_GROUP;

        //First small group, nDelta start at 0.
        //first size class is 1 << LOG2_QUANTUM
        for (int nDelta = 0; nDelta < ndeltaLimit; nDelta++, nSizes++) {
            short[] sizeClass = newSizeClass(nSizes, log2Group, log2Delta, nDelta, pageShifts);
            sizeClasses[nSizes] = sizeClass;
            size = sizeOf(sizeClass, directMemoryCacheAlignment);
        }

        log2Group += LOG2_SIZE_CLASS_GROUP;

        //All remaining groups, nDelta start at 1.
        for (; size < chunkSize; log2Group++, log2Delta++) {
            for (int nDelta = 1; nDelta <= ndeltaLimit && size < chunkSize; nDelta++, nSizes++) {
                short[] sizeClass = newSizeClass(nSizes, log2Group, log2Delta, nDelta, pageShifts);
                sizeClasses[nSizes] = sizeClass;
                size = normalMaxSize = sizeOf(sizeClass, directMemoryCacheAlignment);
            }
        }

        //chunkSize must be normalMaxSize
        assert chunkSize == normalMaxSize;

        int smallMaxSizeIdx = 0;
        int lookupMaxSize = 0;
        int nPSizes = 0;
        int nSubpages = 0;
        for (int idx = 0; idx < nSizes; idx++) {
            short[] sz = sizeClasses[idx];
            if (sz[PAGESIZE_IDX] == yes) {
                nPSizes++;
            }
            if (sz[SUBPAGE_IDX] == yes) {
                nSubpages++;
                smallMaxSizeIdx = idx;
            }
            if (sz[LOG2_DELTA_LOOKUP_IDX] != no) {
                lookupMaxSize = sizeOf(sz, directMemoryCacheAlignment);
            }
        }
        this.smallMaxSizeIdx = smallMaxSizeIdx;
        this.lookupMaxSize = lookupMaxSize;
        this.nPSizes = nPSizes;
        this.nSubpages = nSubpages;
        this.nSizes = nSizes;

        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        this.directMemoryCacheAlignment = directMemoryCacheAlignment;

        //generate lookup tables
        sizeIdx2sizeTab = newIdx2SizeTab(sizeClasses, nSizes, directMemoryCacheAlignment);
        pageIdx2sizeTab = newPageIdx2sizeTab(sizeClasses, nSizes, nPSizes, directMemoryCacheAlignment);
        size2idxTab = newSize2idxTab(lookupMaxSize, sizeClasses);
    }

    //calculate size class
    private static short[] newSizeClass(int index, int log2Group, int log2Delta, int nDelta, int pageShifts) {
        short isMultiPageSize;
        if (log2Delta >= pageShifts) {
            isMultiPageSize = yes;
        } else {
            int pageSize = 1 << pageShifts;
            int size = calculateSize(log2Group, nDelta, log2Delta);

            isMultiPageSize = size == size / pageSize * pageSize? yes : no;
        }

        int log2Ndelta = nDelta == 0? 0 : log2(nDelta);

        byte remove = 1 << log2Ndelta < nDelta? yes : no;

        int log2Size = log2Delta + log2Ndelta == log2Group? log2Group + 1 : log2Group;
        if (log2Size == log2Group) {
            remove = yes;
        }

        short isSubpage = log2Size < pageShifts + LOG2_SIZE_CLASS_GROUP? yes : no;

        int log2DeltaLookup = log2Size < LOG2_MAX_LOOKUP_SIZE ||
                              log2Size == LOG2_MAX_LOOKUP_SIZE && remove == no
                ? log2Delta : no;

        return new short[] {
                (short) index, (short) log2Group, (short) log2Delta,
                (short) nDelta, isMultiPageSize, isSubpage, (short) log2DeltaLookup
        };
    }

    private static int[] newIdx2SizeTab(short[][] sizeClasses, int nSizes, int directMemoryCacheAlignment) {
        int[] sizeIdx2sizeTab = new int[nSizes];

        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            sizeIdx2sizeTab[i] = sizeOf(sizeClass, directMemoryCacheAlignment);
        }
        return sizeIdx2sizeTab;
    }

    private static int calculateSize(int log2Group, int nDelta, int log2Delta) {
        return (1 << log2Group) + (nDelta << log2Delta);
    }

    private static int sizeOf(short[] sizeClass, int directMemoryCacheAlignment) {
        int log2Group = sizeClass[LOG2GROUP_IDX];
        int log2Delta = sizeClass[LOG2DELTA_IDX];
        int nDelta = sizeClass[NDELTA_IDX];

        int size = calculateSize(log2Group, nDelta, log2Delta);

        return alignSizeIfNeeded(size, directMemoryCacheAlignment);
    }

    private static int[] newPageIdx2sizeTab(short[][] sizeClasses, int nSizes, int nPSizes,
                                            int directMemoryCacheAlignment) {
        int[] pageIdx2sizeTab = new int[nPSizes];
        int pageIdx = 0;
        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            if (sizeClass[PAGESIZE_IDX] == yes) {
                pageIdx2sizeTab[pageIdx++] = sizeOf(sizeClass, directMemoryCacheAlignment);
            }
        }
        return pageIdx2sizeTab;
    }

    private static int[] newSize2idxTab(int lookupMaxSize, short[][] sizeClasses) {
        int[] size2idxTab = new int[lookupMaxSize >> LOG2_QUANTUM];
        int idx = 0;
        int size = 0;

        for (int i = 0; size <= lookupMaxSize; i++) {
            int log2Delta = sizeClasses[i][LOG2DELTA_IDX];
            int times = 1 << log2Delta - LOG2_QUANTUM;

            while (size <= lookupMaxSize && times-- > 0) {
                size2idxTab[idx++] = i;
                size = idx + 1 << LOG2_QUANTUM;
            }
        }
        return size2idxTab;
    }

    @Override
    public int sizeIdx2size(int sizeIdx) {
        return sizeIdx2sizeTab[sizeIdx];
    }

    @Override
    public int sizeIdx2sizeCompute(int sizeIdx) {
        int group = sizeIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = sizeIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int groupSize = group == 0? 0 :
                1 << LOG2_QUANTUM + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int lgDelta = shift + LOG2_QUANTUM - 1;
        int modSize = mod + 1 << lgDelta;

        return groupSize + modSize;
    }

    @Override
    public long pageIdx2size(int pageIdx) {
        return pageIdx2sizeTab[pageIdx];
    }

    @Override
    public long pageIdx2sizeCompute(int pageIdx) {
        int group = pageIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = pageIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        long groupSize = group == 0? 0 :
                1L << pageShifts + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int log2Delta = shift + pageShifts - 1;
        int modSize = mod + 1 << log2Delta;

        return groupSize + modSize;
    }

    /**
     * Normalizes request size up to the nearest size class.
     * 将请求的大小规格化为最接近的值，返回该值对应的size索引
     * 比如申请大小为14，则返回数组索引值为0，后续通过该数组索引就能得到对应的size的值
     * @param size request size
     * @return sizeIdx of the size class
     */
    @Override
    public int size2SizeIdx(int size) {
        if (size == 0) {
            return 0;
        }
        if (size > chunkSize) {
            return nSizes;
        }

        size = alignSizeIfNeeded(size, directMemoryCacheAlignment);

        if (size <= lookupMaxSize) {
            //size-1 / MIN_TINY
            return size2idxTab[size - 1 >> LOG2_QUANTUM];
        }

        int x = log2((size << 1) - 1);
        int shift = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM);

        int group = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;

        int mod = size - 1 >> log2Delta & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        return group + mod;
    }

    @Override
    public int pages2pageIdx(int pages) {
        return pages2pageIdxCompute(pages, false);
    }

    @Override
    public int pages2pageIdxFloor(int pages) {
        return pages2pageIdxCompute(pages, true);
    }

    private int pages2pageIdxCompute(int pages, boolean floor) {
        int pageSize = pages << pageShifts;
        if (pageSize > chunkSize) {
            return nPSizes;
        }

        int x = log2((pageSize << 1) - 1);

        int shift = x < LOG2_SIZE_CLASS_GROUP + pageShifts
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + pageShifts);

        int group = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + pageShifts + 1?
                pageShifts : x - LOG2_SIZE_CLASS_GROUP - 1;

        int mod = pageSize - 1 >> log2Delta & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int pageIdx = group + mod;

        if (floor && pageIdx2sizeTab[pageIdx] > pages << pageShifts) {
            pageIdx--;
        }

        return pageIdx;
    }

    // Round size up to the nearest multiple of alignment.
    private static int alignSizeIfNeeded(int size, int directMemoryCacheAlignment) {
        if (directMemoryCacheAlignment <= 0) {
            return size;
        }
        int delta = size & directMemoryCacheAlignment - 1;
        return delta == 0? size : size + directMemoryCacheAlignment - delta;
    }

    @Override
    public int normalizeSize(int size) {
        if (size == 0) {
            return sizeIdx2sizeTab[0];
        }
        size = alignSizeIfNeeded(size, directMemoryCacheAlignment);
        if (size <= lookupMaxSize) {
            int ret = sizeIdx2sizeTab[size2idxTab[size - 1 >> LOG2_QUANTUM]];
            assert ret == normalizeSizeCompute(size);
            return ret;
        }
        return normalizeSizeCompute(size);
    }

    private static int normalizeSizeCompute(int size) {
        int x = log2((size << 1) - 1);
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;
        int delta = 1 << log2Delta;
        int delta_mask = delta - 1;
        return size + delta_mask & ~delta_mask;
    }
}
