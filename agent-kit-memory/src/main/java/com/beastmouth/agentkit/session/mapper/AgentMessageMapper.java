package com.beastmouth.agentkit.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beastmouth.agentkit.session.AgentMessage;
import org.apache.ibatis.annotations.Mapper;

/** 会话消息 Mapper。 */
@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {
}
