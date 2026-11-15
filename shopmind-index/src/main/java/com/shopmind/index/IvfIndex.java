package com.shopmind.index;

import com.shopmind.common.vector.Distance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * IVF（倒排聚类）索引，Java 自研实现。
 *
 * <p>思路：先用 k-means 把向量聚成 nlist 个簇，检索时只扫描与查询最近的 nprobe 个簇。
 * 它存在的意义是给 HNSW 提供第二种近似路径——IVF 的召回随 nprobe 单调上升但受簇边界影响，
 * HNSW 的召回随 efSearch 上升且图连通性更好，两者的对比正好构成报告里的实验对照。</p>
 */
public final class IvfIndex implements VectorIndex {

    private final int nlist;
    private final long seed;

    private final List<float[]> vectors = new ArrayList<>();
    private final List<Long> docIds = new ArrayList<>();
    private final List<Boolean> deleted = new ArrayList<>();
    private final Map<Long, Integer> idToSlot = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile float[][] centroids;
    private volatile int[][] lists;
    private volatile boolean built;
    private int dimension = -1;
    private int liveCount;

    public IvfIndex() {
        this(256, 20261115L);
    }

    public IvfIndex(int nlist, long seed) {
        if (nlist <= 0) {
            throw new IllegalArgumentException("nlist 必须为正数: " + nlist);
        }
        this.nlist = nlist;
        this.seed = seed;
    }

    @Override
    public IndexKind kind() {
        return IndexKind.IVF;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return liveCount;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void add(long docId, float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }
        lock.writeLock().lock();
        try {
            if (dimension < 0) {
                dimension = vector.length;
            } else if (vector.length != dimension) {
                throw new IllegalArgumentException("向量维度不一致: 期望 " + dimension + "，实际 " + vector.length);
            }
            int slot = vectors.size();
            vectors.add(Distance.normalizeInPlace(vector.clone()));
            docIds.add(docId);
            deleted.add(false);
            idToSlot.put(docId, slot);
            liveCount++;

            if (built) {
                // 已建索引后增量写入：直接挂到最近簇的倒排链上，无需重建全量索引。
                int c = nearestCentroid(vectors.get(slot));
                int[] oldList = lists[c];
                int[] newList = Arrays.copyOf(oldList, oldList.length + 1);
                newList[oldList.length] = slot;
                lists[c] = newList;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(long docId) {
        lock.writeLock().lock();
        try {
            Integer slot = idToSlot.remove(docId);
            if (slot == null || deleted.get(slot)) {
                return false;
            }
            deleted.set(slot, true);
            liveCount--;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 构建聚类：初次检索前自动调用，也可由评测代码显式调用以单独统计构建耗时。 */
    public void build() {
        lock.writeLock().lock();
        try {
            int n = vectors.size();
            if (n == 0) {
                built = true;
                return;
            }
            int k = Math.min(nlist, n);
            Random random = new Random(seed);

            float[][] centroidArray = new float[k][];
            for (int i = 0; i < k; i++) {
                centroidArray[i] = vectors.get(random.nextInt(n)).clone();
            }

            int[] assignment = new int[n];
            Arrays.fill(assignment, -1);
            for (int iter = 0; iter < 10; iter++) {
                for (int i = 0; i < n; i++) {
                    assignment[i] = nearest(centroidArray, vectors.get(i));
                }
                float[][] sums = new float[k][dimension];
                int[] counts = new int[k];
                for (int i = 0; i < n; i++) {
                    int c = assignment[i];
                    counts[c]++;
                    float[] v = vectors.get(i);
                    float[] sum = sums[c];
                    for (int d = 0; d < dimension; d++) {
                        sum[d] += v[d];
                    }
                }
                for (int c = 0; c < k; c++) {
                    if (counts[c] == 0) {
                        // 空簇重新播种，避免簇心退化后长期失效。
                        centroidArray[c] = vectors.get(random.nextInt(n)).clone();
                        continue;
                    }
                    for (int d = 0; d < dimension; d++) {
                        sums[c][d] /= counts[c];
                    }
                    Distance.normalizeInPlace(sums[c]);
                    centroidArray[c] = sums[c];
                }
            }

            int[] counts = new int[k];
            for (int i = 0; i < n; i++) {
                assignment[i] = nearest(centroidArray, vectors.get(i));
                counts[assignment[i]]++;
            }
            int[][] listArray = new int[k][];
            for (int c = 0; c < k; c++) {
                listArray[c] = new int[counts[c]];
            }
            int[] cursor = new int[k];
            for (int i = 0; i < n; i++) {
                int c = assignment[i];
                listArray[c][cursor[c]++] = i;
            }

            this.centroids = centroidArray;
            this.lists = listArray;
            this.built = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<Neighbor> search(float[] query, SearchParams params) {
        if (query == null || query.length == 0) {
            throw new IllegalArgumentException("查询向量不能为空");
        }
        if (!built) {
            build();
        }
        lock.readLock().lock();
        try {
            float[][] centroidArray = centroids;
            int[][] listArray = lists;
            if (centroidArray == null || listArray == null || liveCount == 0) {
                return List.of();
            }
            if (dimension >= 0 && query.length != dimension) {
                throw new IllegalArgumentException("查询维度不一致: 期望 " + dimension + "，实际 " + query.length);
            }
            float[] q = Distance.normalizeInPlace(query.clone());

            int probe = Math.min(params.nprobe(), centroidArray.length);
            Integer[] order = new Integer[centroidArray.length];
            float[] distanceToCentroid = new float[centroidArray.length];
            for (int i = 0; i < centroidArray.length; i++) {
                order[i] = i;
                distanceToCentroid[i] = Distance.cosineDistance(q, centroidArray[i]);
            }
            Arrays.sort(order, Comparator.comparingDouble(i -> distanceToCentroid[i]));

            int k = params.topK();
            PriorityQueue<Neighbor> heap = new PriorityQueue<>(
                    Math.min(k, liveCount) + 1, (a, b) -> Float.compare(a.score(), b.score()));
            for (int p = 0; p < probe; p++) {
                int[] list = listArray[order[p]];
                for (int slot : list) {
                    if (deleted.get(slot)) {
                        continue;
                    }
                    float score = Distance.dot(q, vectors.get(slot));
                    if (heap.size() < k) {
                        heap.offer(new Neighbor(docIds.get(slot), score));
                    } else if (score > heap.peek().score()) {
                        heap.poll();
                        heap.offer(new Neighbor(docIds.get(slot), score));
                    }
                }
            }
            List<Neighbor> result = new ArrayList<>(heap);
            result.sort((a, b) -> Float.compare(b.score(), a.score()));
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public IndexStats stats() {
        lock.readLock().lock();
        try {
            int maxLevel = centroids == null ? 0 : centroids.length;
            double avgDegree = centroids == null || centroids.length == 0
                    ? 0.0 : (double) vectors.size() / centroids.length;
            long memory = (long) vectors.size() * (dimension < 0 ? 0 : dimension) * 4L;
            return new IndexStats(IndexKind.IVF, liveCount, dimension, maxLevel, avgDegree, memory);
        } finally {
            lock.readLock().unlock();
        }
    }

    private int nearestCentroid(float[] vector) {
        return nearest(centroids, vector);
    }

    private int nearest(float[][] centroidArray, float[] vector) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < centroidArray.length; i++) {
            float dist = Distance.cosineDistance(vector, centroidArray[i]);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }
}
