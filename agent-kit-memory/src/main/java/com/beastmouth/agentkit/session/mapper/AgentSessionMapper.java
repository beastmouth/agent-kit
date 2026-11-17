package com.beastmouth.agentkit.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beastmouth.agentkit.session.AgentSession;
import org.apache.ibatis.annotations.Mapper;

/** 会话表 Mapper。查询一律在 Service 层带上 namespace / owner 条件。 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSession> {
}
