package com.shopmind.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BM25 关键词检索（倒排索引，纯 Java 实现）。
 *
 * <p>公式采用经典 Okapi BM25：idf 用 "ln(1 + (N - df + 0.5)/(df + 0.5))" 以抑制高频词的负值问题；
 * tf 部分用饱和函数，配合文档长度归一化，避免长文档靠词数堆分数。
 * 它承担混合检索中的"精确匹配"角色——向量检索擅长语义相近，BM25 擅长型号、品牌等字面命中。</p>
 */
public final class Bm25Index {

    /** k1 控制词频饱和度，b 控制文档长度归一化强度。 */
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private record Posting(int slot, int tf) {
    }

    private final Map<String, List<Posting>> postings = new HashMap<>();
    private final List<Long> docIds = new ArrayList<>();
    private final List<Integer> docLengths = new ArrayList<>();
    private final List<Boolean> deleted = new ArrayList<>();
    private final Map<Long, Integer> idToSlot = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private long totalLength;
    private int liveCount;

    public void add(long docId, String text) {
        List<String> tokens = TextTokenizer.tokenize(text);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("文档分词结果为空: " + docId);
        }
        lock.writeLock().lock();
        try {
            int slot = docIds.size();
            docIds.add(docId);
            docLengths.add(tokens.size());
            deleted.add(false);
            idToSlot.put(docId, slot);
            totalLength += tokens.size();
            liveCount++;

            Map<String, Integer> tfMap = new HashMap<>();
            for (String token : tokens) {
                tfMap.merge(token, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : tfMap.entrySet()) {
                postings.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(new Posting(slot, entry.getValue()));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean remove(long docId) {
        lock.writeLock().lock();
        try {
            Integer slot = idToSlot.remove(docId);
            if (slot == null || deleted.get(slot)) {
                return false;
            }
            deleted.set(slot, true);
            liveCount--;
            totalLength -= docLengths.get(slot);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return liveCount;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int vocabularySize() {
        lock.readLock().lock();
        try {
            return postings.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 检索 Top-K。返回的 score 为 BM25 分值（越大越相关），与向量相似度不可直接比较，
     * 混合打分时由 {@link HybridRanker} 做归一化。
     */
    public List<Neighbor> search(String query, int topK) {
        List<String> queryTokens = TextTokenizer.tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            if (liveCount == 0) {
                return List.of();
            }
            double avgdl = (double) totalLength / liveCount;
            Map<Integer, Double> scores = new HashMap<>();

            for (String token : queryTokens) {
                List<Posting> list = postings.get(token);
                if (list == null) {
                    continue;
                }
                // 统计该词的文档频率时跳过已删除文档，避免 idf 被墓碑污染。
                int df = 0;
                for (Posting p : list) {
                    if (!deleted.get(p.slot())) {
                        df++;
                    }
                }
                if (df == 0) {
                    continue;
                }
                double idf = Math.log(1.0 + (liveCount - df + 0.5) / (df + 0.5));
                for (Posting p : list) {
                    if (deleted.get(p.slot())) {
                        continue;
                    }
                    double dl = docLengths.get(p.slot());
                    double tf = p.tf();
                    double denom = tf + K1 * (1 - B + B * dl / avgdl);
                    double score = idf * (tf * (K1 + 1)) / denom;
                    scores.merge(p.slot(), score, Double::sum);
                }
            }

            PriorityQueue<Neighbor> heap = new PriorityQueue<>(
                    Math.min(topK, scores.size()) + 1, (a, b) -> Float.compare(a.score(), b.score()));
            for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
                float score = entry.getValue().floatValue();
                if (heap.size() < topK) {
                    heap.offer(new Neighbor(docIds.get(entry.getKey()), score));
                } else if (score > heap.peek().score()) {
                    heap.poll();
                    heap.offer(new Neighbor(docIds.get(entry.getKey()), score));
                }
            }
            List<Neighbor> result = new ArrayList<>(heap);
            result.sort((a, b) -> Float.compare(b.score(), a.score()));
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }
}
