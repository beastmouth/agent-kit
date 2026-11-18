package com.beastmouth.agentkit.context;

import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文压缩。
 *
 * <p>这些用例钉的是同一个目标：<b>压缩不能改变「同一前缀 → 同一摘要」这条性质</b>。
 * 一旦摘要每轮重新生成，前缀缓存就整段失效，长会话会越压越贵——这个 bug 不会报错，
 * 只会在账单上体现出来。所以「第二次必须复用、且不再调模型」是本类最重要的断言。</p>
 */
class LlmContextCompactorTest {

    /** 触发阈值设小，让「20 条消息」稳定超限；keepMessages=4 便于断言尾部条数 */
    private static CompactionProperties props(boolean enabled, int triggerTokens, int keepMessages) {
        CompactionProperties properties = new CompactionProperties();
        properties.setEnabled(enabled);
        properties.setTriggerTokens(triggerTokens);
        properties.setKeepMessages(keepMessages);
        properties.setMaxSummaryChars(400);
        return properties;
    }

    private static List<ChatMessage> history(int turns, int charsPerMessage, String tag) {
        List<ChatMessage> messages = new ArrayList<>();
        String filler = "x".repeat(Math.max(0, charsPerMessage));
        for (int i = 0; i < turns; i++) {
            messages.add(ChatMessage.user(tag + " 问" + i + " " + filler));
            messages.add(ChatMessage.assistant(tag + " 答" + i + " " + filler));
        }
        return messages;
    }

    @Test
    @DisplayName("未开启时直接跳过，不碰存储也不调模型")
    void disabledSkipsEverything() {
        StubCompactor compactor = new StubCompactor(props(false, 1, 4), new InMemorySummaryStore(props(false, 1, 4)),
                "摘要", false);
        List<ChatMessage> history = history(10, 100, "a");

        CompactOutcome outcome = compactor.compact(CompactRequest.of("u1", history, "现在呢"));

        assertSame(history, outcome.history(), "未开启必须原样返回同一个引用");
        assertFalse(outcome.compacted());
        assertEquals(0, compactor.calls, "未开启不许调模型");
    }

    @Test
    @DisplayName("未超阈值时原样返回，不压缩")
    void underTriggerPassesThrough() {
        CompactionProperties properties = props(true, 1_000_000, 4);
        StubCompactor compactor = new StubCompactor(properties, new InMemorySummaryStore(properties), "摘要", false);
        List<ChatMessage> history = history(10, 100, "a");

        CompactOutcome outcome = compactor.compact(CompactRequest.of("u1", history, "现在呢"));

        assertSame(history, outcome.history());
        assertFalse(outcome.compacted());
        assertEquals(0, compactor.calls);
    }

    @Test
    @DisplayName("超阈值后结构固定：一条摘要消息 + 保留的尾部原文")
    void compactBuildsSummaryPlusTail() {
        CompactionProperties properties = props(true, 100, 4);
        StubCompactor compactor = new StubCompactor(properties, new InMemorySummaryStore(properties), "用户在记账", false);
        List<ChatMessage> history = history(10, 100, "a");

        CompactOutcome outcome = compactor.compact(CompactRequest.of("u1", history, "现在呢"));

        assertTrue(outcome.compacted());
        List<ChatMessage> compacted = outcome.history();
        assertEquals(5, compacted.size(), "1 条摘要 + 4 条尾部");
        assertEquals("user", compacted.get(0).role(), "摘要用 user 角色：两套引擎都会丢弃历史里的 system");
        assertTrue(compacted.get(0).content().startsWith(LlmContextCompactor.SUMMARY_MARK));
        assertTrue(compacted.get(0).content().contains("用户在记账"));
        assertEquals(16, outcome.coveredCount(), "20 条里压掉 16 条");
        assertFalse(outcome.reused());
        assertNotNull(outcome.coveredKey());
        // 尾部必须是原文，不能是重新生成的内容
        assertEquals(history.get(18).content(), compacted.get(3).content());
    }

    @Test
    @DisplayName("同一前缀第二次复用摘要，且不再调模型")
    void reusesSummaryForSamePrefix() {
        CompactionProperties properties = props(true, 100, 4);
        StubCompactor compactor = new StubCompactor(properties, new InMemorySummaryStore(properties), "用户在记账", false);
        List<ChatMessage> history = history(10, 100, "a");

        CompactOutcome first = compactor.compact(CompactRequest.of("u1", history, "现在呢"));
        CompactOutcome second = compactor.compact(CompactRequest.of("u1", history, "现在呢"));

        assertFalse(first.reused());
        assertTrue(second.reused(), "同一前缀必须复用，否则前缀缓存每轮失效");
        assertEquals(1, compactor.calls, "复用不许再调一次模型");
        assertEquals(first.summary(), second.summary(), "摘要正文必须逐字一致");
    }

    @Test
    @DisplayName("前缀变了就重新生成一份，不复用旧摘要")
    void regeneratesWhenPrefixChanges() {
        CompactionProperties properties = props(true, 100, 4);
        StubCompactor compactor = new StubCompactor(properties, new InMemorySummaryStore(properties), "摘要", false);

        compactor.compact(CompactRequest.of("u1", history(10, 100, "a"), "现在呢"));
        CompactOutcome changed = compactor.compact(CompactRequest.of("u1", history(10, 100, "b"), "现在呢"));

        assertFalse(changed.reused());
        assertEquals(2, compactor.calls);
    }

    @Test
    @DisplayName("摘要模型异常时降级为不压缩，不抛给调用方")
    void degradesOnModelFailure() {
        CompactionProperties properties = props(true, 100, 4);
        StubCompactor compactor = new StubCompactor(properties, new InMemorySummaryStore(properties), "摘要", true);
        List<ChatMessage> history = history(10, 100, "a");

        CompactOutcome outcome = compactor.compact(CompactRequest.of("u1", history, "现在呢"));

        assertSame(history, outcome.history());
        assertFalse(outcome.compacted());
    }

    @Test
    @DisplayName("模型返回空串同样降级，不写空摘要进缓存")
    void degradesOnEmptySummary() {
        CompactionProperties properties = props(true, 100, 4);
        InMemorySummaryStore store = new InMemorySummaryStore(properties);
        StubCompactor compactor = new StubCompactor(properties, store, "   ", false);
        List<ChatMessage> history = history(10, 100, "a");

        CompactOutcome outcome = compactor.compact(CompactRequest.of("u1", history, "现在呢"));

        assertSame(history, outcome.history());
        // 调了一次模型但拿到空白输出：本轮降级不压缩，且不能把空摘要写进缓存
        assertEquals(1, compactor.calls);
        assertFalse(outcome.compacted());
    }

    @Test
    @DisplayName("force 模式跳过阈值，用于上下文溢出兜底")
    void forceIgnoresTrigger() {
        CompactionProperties properties = props(true, 1_000_000, 4);
        StubCompactor compactor = new StubCompactor(properties, new InMemorySummaryStore(properties), "摘要", false);
        List<ChatMessage> history = history(10, 100, "a");

        CompactOutcome outcome = compactor.compact(CompactRequest.of("u1", history, "现在呢").forced());

        assertTrue(outcome.compacted(), "force 必须忽略阈值");
        assertTrue(outcome.history().size() < history.size());
    }

    @Test
    @DisplayName("历史比保留窗口还短时不压缩：硬压会把上下文清空")
    void refusesWhenHistoryShorterThanKeep() {
        CompactionProperties properties = props(true, 1, 8);
        StubCompactor compactor = new StubCompactor(properties, new InMemorySummaryStore(properties), "摘要", false);
        List<ChatMessage> history = history(2, 50, "a");

        CompactOutcome outcome = compactor.compact(CompactRequest.of("u1", history, "现在呢"));

        assertSame(history, outcome.history());
        assertEquals(0, compactor.calls);
    }

    @Test
    @DisplayName("retryOnOverflow 跟随开关：关闭时引擎不做溢出重试")
    void retryFlagFollowsConfig() {
        CompactionProperties on = props(true, 100, 4);
        CompactionProperties off = props(true, 100, 4);
        off.setOverflowRetry(false);

        assertTrue(new StubCompactor(on, new InMemorySummaryStore(on), "s", false).retryOnOverflow());
        assertFalse(new StubCompactor(off, new InMemorySummaryStore(off), "s", false).retryOnOverflow());
    }

    /** 用子类覆盖模型调用，避免为了测压缩去搭一整套 provider 装配 */
    private static final class StubCompactor extends LlmContextCompactor {

        private final String reply;
        private final boolean fail;
        private int calls;

        private StubCompactor(CompactionProperties properties, SummaryStore store, String reply, boolean fail) {
            super(properties, store, null);
            this.reply = reply;
            this.fail = fail;
        }

        @Override
        protected ModelReply callModel(List<ChatMessage> prompt) {
            calls++;
            if (fail) {
                throw new IllegalStateException("摘要服务挂了");
            }
            return new ModelReply(reply, 10, 5, 1L);
        }
    }
}
