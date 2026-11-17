package com.beastmouth.agentkit.eval;

import lombok.Data;

/** 对应表 {@code ai_golden_case}：离线黄金集用例 */
@Data
public class GoldenCaseRow {

    private Long id;
    private String name;
    /** NORMAL / COMPOUND / VAGUE / FORBIDDEN / EMPTY / NEW_BEHAVIOR，六层分类见方案 §5.8 */
    private String layer;
    private String input;
    /** 期望调用的工具，逗号分隔表示"其中之一即可"；空表示不校验 */
    private String expectedTool;
    /**
     * 期望的返回条数语义：
     * ANY 不校验 / GT0 必须有数据 / EQ0 必须为空 / FAIL 必须失败（越权类）。
     *
     * <p><b>写操作只能填 ANY。</b>新增、修改、删除不返回条数（{@code rows=null}），
     * 填 GT0 会永远失败；写操作是否成功由 rubric v3 的 success 维度单独判，
     * 不需要也不应该用条数表达。</p>
     */
    private String expectedRows;
    /** 1 = 期望触发澄清反问 */
    private Integer expectClarify;
    private Integer enabled;
}
