package com.shopmind.service.retrieval;

import com.shopmind.common.vector.Distance;
import com.shopmind.index.Neighbor;
import com.shopmind.service.model.ProductDoc;

import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 内存增量缓冲（delta layer）：写入热路径把文档放这里即可见，
 * 后台 applier 按序把记录落到 H2 与三套主索引后再移除。
 *
 * <p>两个结构配合：{@link LinkedBlockingQueue} 让 applier 阻塞等待新工作，
 * {@link ConcurrentLinkedDeque} 支持检索线程无锁并发扫描；
 * 扫描期间恰好被 applier 移除的条目由查询合并阶段的 id 去重处理，不会产生重复结果。</p>
 */
public final class DeltaBuffer {

    /** 待应用记录：文档、已归一化向量、WAL 偏移。 */
    record Pending(ProductDoc doc, float[] vector, long walOffset) {
    }

    private final ConcurrentLinkedDeque<Pending> pending = new ConcurrentLinkedDeque<>();
    private final LinkedBlockingQueue<Pending> workQueue = new LinkedBlockingQueue<>();

    /** 热路径提交：入双端队列供检索扫描，入阻塞队列供 applier 领取。 */
    public void submit(Pending entry) {
        pending.addLast(entry);
        workQueue.add(entry);
    }

    /** 后台 applier 阻塞等待下一条记录。 */
    public Pending take() throws InterruptedException {
        return workQueue.take();
    }

    /** 非阻塞领取，批量 drain 时使用。 */
    public Pending poll() {
        return workQueue.poll();
    }

    /** 记录已落库后从扫描队列移除。 */
    public void markApplied(Pending entry) {
        pending.remove(entry);
    }

    /** 删除尚未应用的记录（管理操作），两个队列同时移除。 */
    public boolean removeByDocId(long docId) {
        boolean removed = pending.removeIf(p -> p.doc.id() == docId);
        workQueue.removeIf(p -> p.doc.id() == docId);
        return removed;
    }

    public int size() {
        return pending.size();
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /**
     * 对 delta 内全部向量做精确点积扫描，返回 Top-K。
     * 调用方传入的查询向量须已 L2 归一化。
     */
    public List<Neighbor> scan(float[] normalizedQuery, int topK) {
        PriorityQueue<Neighbor> heap = new PriorityQueue<>(
                Math.min(topK, Math.max(1, pending.size())) + 1,
                (a, b) -> Float.compare(a.score(), b.score()));
        for (Pending entry : pending) {
            float score = Distance.dot(normalizedQuery, entry.vector);
            if (heap.size() < topK) {
                heap.offer(new Neighbor(entry.doc.id(), score));
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.offer(new Neighbor(entry.doc.id(), score));
            }
        }
        List<Neighbor> result = new java.util.ArrayList<>(heap);
        result.sort((a, b) -> Float.compare(b.score(), a.score()));
        return result;
    }

    static Pending pendingOf(ProductDoc doc, float[] vector, long walOffset) {
        return new Pending(doc, vector, walOffset);
    }
}
