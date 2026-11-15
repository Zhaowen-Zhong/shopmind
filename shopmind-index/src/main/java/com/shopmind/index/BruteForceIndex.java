package com.shopmind.index;

import com.shopmind.common.vector.Distance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 暴力检索索引。
 *
 * <p>它的价值不在于性能，而在于它是召回质量的"精确基准"：任何近似索引的 Recall@K
 * 都以它的结果为分母。因此这里刻意选择最朴素的全量点积扫描，不做任何近似，
 * 并用读写锁保证扫描期间数据不被修改（删除采用墓碑标记，避免数组搬移导致读线程读错位置）。</p>
 */
public final class BruteForceIndex implements VectorIndex {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private float[][] vectors = new float[64][];
    private long[] docIds = new long[64];
    private boolean[] deleted = new boolean[64];
    /** 已使用过的槽位数，只增不减；删除只打墓碑，不回收槽位。 */
    private int slotCount;
    /** 有效（未删除）向量条数。 */
    private int liveCount;

    private final Map<Long, Integer> idToIndex = new HashMap<>();
    private int dimension = -1;

    @Override
    public IndexKind kind() {
        return IndexKind.BRUTE_FORCE;
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
            ensureCapacity(slotCount + 1);
            vectors[slotCount] = Distance.normalizeInPlace(vector.clone());
            docIds[slotCount] = docId;
            deleted[slotCount] = false;
            idToIndex.put(docId, slotCount);
            slotCount++;
            liveCount++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(long docId) {
        lock.writeLock().lock();
        try {
            Integer index = idToIndex.get(docId);
            if (index == null || deleted[index]) {
                return false;
            }
            deleted[index] = true;
            idToIndex.remove(docId);
            liveCount--;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<Neighbor> search(float[] query, SearchParams params) {
        if (query == null || query.length == 0) {
            throw new IllegalArgumentException("查询向量不能为空");
        }
        lock.readLock().lock();
        try {
            if (liveCount == 0) {
                return List.of();
            }
            if (dimension >= 0 && query.length != dimension) {
                throw new IllegalArgumentException("查询维度不一致: 期望 " + dimension + "，实际 " + query.length);
            }
            float[] q = Distance.normalizeInPlace(query.clone());
            int k = params.topK();

            // 小顶堆保存当前 Top-K：堆顶是已选结果里最差的一条，便于 O(1) 判断是否淘汰。
            PriorityQueue<Neighbor> heap = new PriorityQueue<>(
                    Math.min(k, liveCount) + 1, (a, b) -> Float.compare(a.score(), b.score()));

            for (int i = 0; i < slotCount; i++) {
                if (deleted[i]) {
                    continue;
                }
                float score = Distance.dot(q, vectors[i]);
                if (heap.size() < k) {
                    heap.offer(new Neighbor(docIds[i], score));
                } else if (score > heap.peek().score()) {
                    heap.poll();
                    heap.offer(new Neighbor(docIds[i], score));
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
            long memory = (long) liveCount * (dimension < 0 ? 0 : dimension) * 4L;
            return new IndexStats(IndexKind.BRUTE_FORCE, liveCount, dimension, 0, 0.0, memory);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void ensureCapacity(int required) {
        if (required <= vectors.length) {
            return;
        }
        int newCapacity = Math.max(required, vectors.length * 2);
        float[][] newVectors = new float[newCapacity][];
        long[] newIds = new long[newCapacity];
        boolean[] newDeleted = new boolean[newCapacity];
        System.arraycopy(vectors, 0, newVectors, 0, vectors.length);
        System.arraycopy(docIds, 0, newIds, 0, docIds.length);
        System.arraycopy(deleted, 0, newDeleted, 0, deleted.length);
        vectors = newVectors;
        docIds = newIds;
        deleted = newDeleted;
    }
}
