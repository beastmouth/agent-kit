package com.beastmouth.agentkit.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.beastmouth.agentkit.memory.AgentMemory;
import org.apache.ibatis.annotations.Mapper;

/** 长期记忆 Mapper。 */
@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemory> {
}
