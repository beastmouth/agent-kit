package com.beastmouth.agentkit.eval;

import com.beastmouth.agentkit.eval.mapper.EvalMapper;
import com.beastmouth.agentkit.obs.AiMetrics;
import com.beastmouth.agentkit.obs.IdGen;
import com.beastmouth.agentkit.obs.ObsHub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 用户显式反馈（点踩/点赞）。
 *
 * <p>这是质量三源里的 C 源（人工），但比"每周 30 条标注"便宜得多：用户看到错误时顺手一点，
 * 就得到一条带 runId 的精确标注——<b>比任何 judge 都准，因为它是当事人</b>。</p>
 *
 * <p>只收结构化标签，不收自由文本评论以外的隐私数据；评论截断到 200 字，
 * 避免用户把账号密码之类的东西粘进来（这类数据一旦进库就很难追回）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    /** 允许的评分值：只认这三种，别让前端传 "1"/"good"/"怒" 把报表打乱 */
    private static final Set<String> RATINGS = Set.of("UP", "DOWN", "NEUTRAL");

    /** 允许的标签：与方案 §3.4 的失败分类对齐，自由文本标签会让聚类失效 */
    private static final Set<String> TAGS = Set.of("答非所问", "数据不对", "太慢", "编造", "太啰嗦", "其他");

    private final EvalMapper mapper;

    /**
     * 记录一次反馈。
     *
     * @return 实际落库的 runId；找不到可反馈的任务时返回 null
     */
    public Long submit(Long runId, Long sessionId, String subjectId, String rating, String tags, String comment) {
        Long target = runId;
        if ((target == null || target <= 0) && sessionId != null && sessionId > 0) {
            // 前端只知道"刚才那句话"，不知道 runId：允许按会话倒查最近一条
            target = mapper.findLatestRunIdBySession(sessionId);
        }
        if (target == null || target <= 0) {
            log.debug("FeedbackService#submit#找不到可反馈的任务 runId={} session={}", runId, sessionId);
            return null;
        }
        String safeRating = rating == null ? "DOWN" : rating.trim().toUpperCase();
        if (!RATINGS.contains(safeRating)) {
            safeRating = "DOWN";
        }
        FeedbackRow row = new FeedbackRow();
        row.setId(IdGen.next());
        row.setRunId(target);
        row.setSubjectId(subjectId);
        row.setRating(safeRating);
        row.setTags(normalizeTags(tags));
        row.setComment(truncate(comment, 200));
        row.setCreatedAt(LocalDateTime.now());
        mapper.upsertFeedback(row);
        metrics().feedback(safeRating);
        log.info("FeedbackService#submit#run={} subject={} rating={} tags={}", target, subjectId, safeRating,
                row.getTags());
        return target;
    }

    /** 会话里最近一条任务 id；没有就返回 null（前端据此隐藏"点踩"入口，而不是点了报错） */
    public Long latestRunId(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            return null;
        }
        return mapper.findLatestRunIdBySession(sessionId);
    }

    private String normalizeTags(String tags) {        if (tags == null || tags.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String raw : tags.split("[,，;；]")) {
            String tag = raw.trim();
            if (tag.isEmpty() || !TAGS.contains(tag)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(tag);
        }
        return sb.length() == 0 ? null : truncate(sb.toString(), 255);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private AiMetrics metrics() {
        return ObsHub.metrics();
    }
}
