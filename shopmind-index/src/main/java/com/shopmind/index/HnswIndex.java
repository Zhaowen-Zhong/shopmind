package com.shopmind.index;

import com.shopmind.common.vector.Distance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * HNSW（Hierarchical Navigable Small World）分层可导航小世界图索引，Java 自研实现。
 *
 * <p>结构：每个节点被随机分配到 0..level 层，第 0 层包含全部节点且连接最密，
 * 层数越高节点越少、边越长，形成"先跳远、再找近"的多尺度导航结构。
 * 检索从最高层的入口点开始贪心下降，逐层收敛到目标邻域，最后在第 0 层用 ef 宽度做局部搜索。</p>
 *
 * <p>三个参数的分工：M 控制每节点在高层（及第 0 层的一半上限）的连接数，决定图的分辨率；
 * efConstruction 控制建图时每层候选集合的大小，越大图质量越好但构建越慢；
 * efSearch 控制查询时维护的候选集合大小，形成"召回-延迟"的可调曲线。</p>
 */
public final class HnswIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(HnswIndex.class);

    /** 单条候选记录：节点下标 + 到查询点的距离。 */
    private record Cand(int node, float dist) implements Comparable<Cand> {
        @Override
        public int compareTo(Cand other) {
            return Float.compare(dist, other.dist);
        }
    }

    private static final class Node {
        final long docId;
        final float[] vector;
        final int level;
        /** 按层存放邻居下标：links.get(layer)。 */
        final List<List<Integer>> links;
        volatile boolean deleted;

        Node(long docId, float[] vector, int level) {
            this.docId = docId;
            this.vector = vector;
            this.level = level;
            this.links = new ArrayList<>(level + 1);
            for (int i = 0; i <= level; i++) {
                this.links.add(new ArrayList<>(4));
            }
        }
    }

    private final int m;
    private final int mMax0;
    private final int efConstruction;
    private final double mL;
    private final java.util.Random random;

    private final List<Node> nodes = new ArrayList<>();
    private final Map<Long, Integer> idToNode = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 访问标记数组（线程内复用）+ 自增时间戳。
     *
     * <p>早期实现每次查询都 new 一个 HashSet 记录访问过的节点，ef=100 时单次查询会产生上千次装箱与扩容，
     * 在 1000 QPS 下直接把 P99 从毫秒级推到数百毫秒（GC 抖动）。
     * 改为"每个线程一个 int[]，用本次查询的 stamp 写入标记"后，热路径上零分配。</p>
     */
    private static final ThreadLocal<int[]> VISIT_MARKS = ThreadLocal.withInitial(() -> new int[0]);
    private final java.util.concurrent.atomic.AtomicInteger stampGenerator =
            new java.util.concurrent.atomic.AtomicInteger(1);

    private int entryPoint = -1;
    private int maxLevel = -1;
    private int dimension = -1;
    private int liveCount;

    public HnswIndex() {
        this(16, 200, 20261115L);
    }

    public HnswIndex(int m, int efConstruction, long seed) {
        if (m < 2) {
            throw new IllegalArgumentException("M 至少为 2: " + m);
        }
        if (efConstruction < m) {
            throw new IllegalArgumentException("efConstruction 不能小于 M: " + efConstruction + " < " + m);
        }
        this.m = m;
        this.mMax0 = m * 2;
        this.efConstruction = efConstruction;
        this.mL = 1.0 / Math.log(m);
        this.random = new java.util.Random(seed);
    }

    @Override
    public IndexKind kind() {
        return IndexKind.HNSW;
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

    public int m() {
        return m;
    }

    public int efConstruction() {
        return efConstruction;
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

            float[] v = Distance.normalizeInPlace(vector.clone());
            int level = randomLevel();
            Node node = new Node(docId, v, level);
            int idx = nodes.size();
            nodes.add(node);
            idToNode.put(docId, idx);
            liveCount++;

            if (entryPoint < 0) {
                entryPoint = idx;
                maxLevel = level;
                return;
            }

            int ep = entryPoint;
            // 第一阶段：从最高层贪心下降到插入层之上，只做导航不建边。
            for (int lc = maxLevel; lc > level; lc--) {
                ep = greedyNearest(v, ep, lc);
            }

            // 第二阶段：自插入层向下逐层建边。
            for (int lc = Math.min(level, maxLevel); lc >= 0; lc--) {
                List<Cand> candidates = searchLayer(v, ep, efConstruction, lc);
                List<Integer> selected = selectNeighbors(candidates, m);

                for (int neighbor : selected) {
                    node.links.get(lc).add(neighbor);
                    Node other = nodes.get(neighbor);
                    other.links.get(lc).add(idx);
                    if (other.links.get(lc).size() > maxLinks(lc)) {
                        shrink(other, lc);
                    }
                }
                if (!candidates.isEmpty()) {
                    ep = candidates.get(0).node();
                }
            }

            if (level > maxLevel) {
                entryPoint = idx;
                maxLevel = level;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(long docId) {
        lock.writeLock().lock();
        try {
            Integer idx = idToNode.remove(docId);
            if (idx == null || nodes.get(idx).deleted) {
                return false;
            }
            nodes.get(idx).deleted = true;
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
            if (entryPoint < 0 || liveCount == 0) {
                return List.of();
            }
            if (dimension >= 0 && query.length != dimension) {
                throw new IllegalArgumentException("查询维度不一致: 期望 " + dimension + "，实际 " + query.length);
            }
            float[] q = Distance.normalizeInPlace(query.clone());
            int ef = Math.max(params.efSearch(), params.topK());

            int ep = entryPoint;
            for (int lc = maxLevel; lc > 0; lc--) {
                ep = greedyNearest(q, ep, lc);
            }
            List<Cand> candidates = searchLayer(q, ep, ef, 0);

            List<Neighbor> result = new ArrayList<>(Math.min(params.topK(), candidates.size()));
            for (Cand cand : candidates) {
                Node node = nodes.get(cand.node());
                if (node.deleted) {
                    continue;
                }
                result.add(new Neighbor(node.docId, 1f - cand.dist()));
                if (result.size() >= params.topK()) {
                    break;
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public IndexStats stats() {
        lock.readLock().lock();
        try {
            long links = 0;
            for (Node node : nodes) {
                links += node.links.get(0).size();
            }
            double avgDegree = liveCount == 0 ? 0.0 : (double) links / liveCount;
            long memory = (long) nodes.size() * (dimension < 0 ? 0 : dimension) * 4L + linksTotal() * 4L;
            return new IndexStats(IndexKind.HNSW, liveCount, dimension, maxLevel, avgDegree, memory);
        } finally {
            lock.readLock().unlock();
        }
    }

    private long linksTotal() {
        long total = 0;
        for (Node node : nodes) {
            for (List<Integer> layerLinks : node.links) {
                total += layerLinks.size();
            }
        }
        return total;
    }

    private int maxLinks(int layer) {
        return layer == 0 ? mMax0 : m;
    }

    private int randomLevel() {
        double r = random.nextDouble();
        if (r <= 0.0) {
            r = 1e-12;
        }
        return (int) Math.floor(-Math.log(r) * mL);
    }

    /** 单层贪心下降：在当前层反复走到更近的邻居，直到邻域内无可改进。 */
    private int greedyNearest(float[] q, int entry, int layer) {
        int curr = entry;
        float currDist = Distance.cosineDistance(q, nodes.get(curr).vector);
        boolean improved = true;
        while (improved) {
            improved = false;
            List<Integer> links = nodes.get(curr).links.get(layer);
            for (int i = 0; i < links.size(); i++) {
                int next = links.get(i);
                float dist = Distance.cosineDistance(q, nodes.get(next).vector);
                if (dist < currDist) {
                    currDist = dist;
                    curr = next;
                    improved = true;
                }
            }
        }
        return curr;
    }

    /**
     * 单层候选搜索：用一个最小堆维护待探索节点、一个最大堆维护当前最优结果集，
     * 当"最优结果已经比待探索节点更近"且结果集已满 ef 时提前终止。
     */
    private List<Cand> searchLayer(float[] q, int entry, int ef, int layer) {
        int stamp = nextStamp();
        int nodeCount = nodes.size();
        int[] marks = VISIT_MARKS.get();
        if (marks.length < nodeCount) {
            marks = new int[Math.max(64, Integer.highestOneBit(nodeCount) * 2)];
            VISIT_MARKS.set(marks);
        }

        PriorityQueue<Cand> candidates = new PriorityQueue<>();
        PriorityQueue<Cand> results = new PriorityQueue<>((a, b) -> Float.compare(b.dist(), a.dist()));

        float d0 = Distance.cosineDistance(q, nodes.get(entry).vector);
        candidates.add(new Cand(entry, d0));
        results.add(new Cand(entry, d0));
        marks[entry] = stamp;

        while (!candidates.isEmpty()) {
            Cand current = candidates.poll();
            if (results.size() >= ef && current.dist() > results.peek().dist()) {
                break;
            }
            List<Integer> links = nodes.get(current.node()).links.get(layer);
            for (int i = 0; i < links.size(); i++) {
                int next = links.get(i);
                if (marks[next] == stamp) {
                    continue;
                }
                marks[next] = stamp;
                float dist = Distance.cosineDistance(q, nodes.get(next).vector);
                if (results.size() < ef || dist < results.peek().dist()) {
                    candidates.add(new Cand(next, dist));
                    results.add(new Cand(next, dist));
                    if (results.size() > ef) {
                        results.poll();
                    }
                }
            }
        }

        List<Cand> sorted = new ArrayList<>(results);
        sorted.sort(null);
        return sorted;
    }

    /** 取一个查询专用的时间戳；溢出时清空标记并重置计数，保证标记不会串到旧查询。 */
    private int nextStamp() {
        int stamp = stampGenerator.getAndIncrement();
        if (stamp <= 0) {
            synchronized (stampGenerator) {
                stampGenerator.set(1);
            }
            java.util.Arrays.fill(VISIT_MARKS.get(), 0);
            stamp = 1;
        }
        return stamp;
    }

    /**
     * 启发式邻居选择（对应 HNSW 论文 Algorithm 4）。
     *
     * <p>不取"最近的 M 个"，而是逐个检查候选：只有当它与查询点的距离比它与任一已选邻居的距离都近时才保留。
     * 这一步让邻接边在空间中分布得更分散，避免所有边都指向同一个局部簇，从而提升图的连通性与召回。</p>
     */
    private List<Integer> selectNeighbors(List<Cand> candidates, int limit) {
        List<Integer> selected = new ArrayList<>(limit);
        for (Cand cand : candidates) {
            if (selected.size() >= limit) {
                break;
            }
            boolean keep = true;
            for (int chosen : selected) {
                float between = Distance.cosineDistance(
                        nodes.get(cand.node()).vector, nodes.get(chosen).vector);
                if (between <= cand.dist()) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                selected.add(cand.node());
            }
        }
        return selected;
    }

    /** 邻居数超限时按同一启发式收缩，保持边数与 M 的上界一致。 */
    private void shrink(Node node, int layer) {
        List<Cand> candidates = new ArrayList<>();
        for (int neighbor : node.links.get(layer)) {
            candidates.add(new Cand(neighbor, Distance.cosineDistance(node.vector, nodes.get(neighbor).vector)));
        }
        candidates.sort(null);
        List<Integer> kept = selectNeighbors(candidates, maxLinks(layer));
        node.links.get(layer).clear();
        node.links.get(layer).addAll(kept);
    }
}
